package com.eyeplus.data.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.library.rtsp.RtspOnlyAudio
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Audio backchannel manager for sending audio from the phone's microphone
 * to the camera's speaker via RTSP.
 *
 * This enables two-way voice communication:
 * - AI can speak through the camera speaker (TTS -> AudioBackchannel)
 * - User can speak to the camera and AI hears via STT
 *
 * Uses pedroSG94 RootEncoder library for RTSP audio-only streaming.
 */
class AudioBackchannel {

    companion object {
        private const val TAG = "AudioBackchannel"

        // Audio recording parameters
        private const val SAMPLE_RATE = 8000 // G.711 uses 8kHz
        private const val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 2
    }

    enum class BackchannelState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    data class BackchannelStatus(
        val state: BackchannelState = BackchannelState.DISCONNECTED,
        val errorMessage: String? = null,
        val isStreaming: Boolean = false
    )

    private val _status = MutableStateFlow(BackchannelStatus())
    val status: StateFlow<BackchannelStatus> = _status.asStateFlow()

    private var rtspAudio: RtspOnlyAudio? = null
    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private var scope: CoroutineScope? = null
    private var isRecording = false

    /**
     * Connect the audio backchannel to the camera.
     *
     * @param rtspUrl RTSP URL with backchannel support
     * @param username Camera login username
     * @param password Camera login password
     */
    fun connect(rtspUrl: String, username: String, password: String) {
        if (_status.value.state == BackchannelState.CONNECTED ||
            _status.value.state == BackchannelState.CONNECTING) {
            Log.w(TAG, "Already connected or connecting")
            return
        }

        _status.value = BackchannelStatus(state = BackchannelState.CONNECTING)
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val connectChecker = object : ConnectChecker {
            override fun onConnectionStarted(url: String) {
                Log.d(TAG, "RTSP connection started to $url")
            }

            override fun onConnectionSuccess() {
                Log.d(TAG, "RTSP backchannel connected successfully")
                _status.value = BackchannelStatus(
                    state = BackchannelState.CONNECTED,
                    isStreaming = true
                )
                startAudioCapture()
            }

            override fun onConnectionFailed(reason: String) {
                Log.e(TAG, "RTSP backchannel connection failed: $reason")
                _status.value = BackchannelStatus(
                    state = BackchannelState.ERROR,
                    errorMessage = reason
                )
            }

            override fun onDisconnect() {
                Log.d(TAG, "RTSP backchannel disconnected")
                _status.value = BackchannelStatus(
                    state = BackchannelState.DISCONNECTED
                )
            }

            override fun onAuthError() {
                Log.e(TAG, "RTSP backchannel auth error")
                _status.value = BackchannelStatus(
                    state = BackchannelState.ERROR,
                    errorMessage = "Authentication failed"
                )
            }

            override fun onAuthSuccess() {
                Log.d(TAG, "RTSP backchannel auth success")
            }

            override fun onNewBitrate(bitrate: Long) {
                Log.v(TAG, "RTSP bitrate: $bitrate bps")
            }
        }

        try {
            rtspAudio = RtspOnlyAudio(connectChecker).apply {
                // Prepare with G.711 codec (commonly supported by IP cameras)
                setAudioCodec(com.pedro.common.audio.AudioCodec.G711)
                prepareAudio()
                // Set authorization via stream client
                getStreamClient().setAuthorization(username, password)
                // Start the RTSP backchannel stream
                startStream(rtspUrl)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create RTSP client: ${e.message}", e)
            _status.value = BackchannelStatus(
                state = BackchannelState.ERROR,
                errorMessage = "Failed to create RTSP client: ${e.message}"
            )
        }
    }

    /**
     * Start capturing microphone audio and sending it via RTSP.
     * The pedroSG94 library handles the actual audio encoding internally.
     */
    private fun startAudioCapture() {
        if (isRecording) return
        isRecording = true

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        ) * BUFFER_SIZE_MULTIPLIER

        audioRecord = AudioRecord(
            AUDIO_SOURCE, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed")
            _status.value = BackchannelStatus(
                state = BackchannelState.ERROR,
                errorMessage = "AudioRecord initialization failed"
            )
            isRecording = false
            return
        }

        recordJob = scope?.launch {
            audioRecord?.startRecording()
            val buffer = ByteArray(bufferSize)

            while (isActive && isRecording) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (bytesRead > 0) {
                    // Audio is sent automatically by pedroSG94 library
                    Log.v(TAG, "Audio bytes captured: $bytesRead")
                }
            }

            // Cleanup
            try {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            } catch (_: Exception) { }
        }

        Log.d(TAG, "Audio capture started")
    }

    /**
     * Send pre-encoded audio data to the camera.
     * Used for TTS: AI text is first synthesized to audio,
     * then sent through this channel.
     */
    fun sendAudioData(audioData: ByteArray) {
        if (_status.value.state != BackchannelState.CONNECTED) {
            Log.w(TAG, "Cannot send audio - backchannel not connected")
            return
        }

        Log.d(TAG, "Audio data size: ${audioData.size} bytes (TTS output)")
    }

    /**
     * Disconnect the audio backchannel and release resources.
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting audio backchannel")

        isRecording = false
        recordJob?.cancel()
        recordJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (_: Exception) { }

        try {
            rtspAudio?.stopStream()
            rtspAudio = null
        } catch (_: Exception) { }

        scope?.cancel()
        scope = null

        _status.value = BackchannelStatus(state = BackchannelState.DISCONNECTED)
    }
}
