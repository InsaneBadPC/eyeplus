package com.eyeplus.data.recording

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages local recording of the camera RTSP stream into MP4 files.
 *
 * Uses MediaCodec for video encoding and MediaMuxer for MP4 container.
 * The recording is done by providing a [Surface] that the ExoPlayer can
 * render to, while we encode and mux the frames.
 */
class RecorderManager(private val context: Context) {

    companion object {
        private const val TAG = "RecorderManager"
        private const val MIME_TYPE = "video/avc" // H.264
        private const val FRAME_RATE = 30
        private const val BIT_RATE = 4_000_000 // 4 Mbps
        private const val I_FRAME_INTERVAL = 2 // seconds

        // Resolution for recording (main stream quality)
        private const val WIDTH = 1920
        private const val HEIGHT = 1080
    }

    sealed class RecordingState {
        data object Idle : RecordingState()
        data object Starting : RecordingState()
        data object Recording : RecordingState()
        data class Error(val message: String) : RecordingState()
        data class Completed(val uri: Uri, val filePath: String) : RecordingState()
    }

    private var recordingJob: Job? = null
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var lastPresentationTimeUs = 0L
    private var frameCount = 0L

    private val stateListeners = mutableListOf<(RecordingState) -> Unit>()

    private val dateFormatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    /** The input surface that ExoPlayer should render to when recording. */
    var inputSurface: Surface? = null
        private set

    private var currentState: RecordingState = RecordingState.Idle
        set(value) {
            field = value
            stateListeners.forEach { it(value) }
        }

    /**
     * Start recording to a new MP4 file.
     * @param outputUri Optional URI for the output file (null = auto-generated)
     * @param width Video width (default: 1920)
     * @param height Video height (default: 1080)
     * @param bitRate Bit rate (default: 4 Mbps)
     * @return The Surface that ExoPlayer should render to
     */
    fun startRecording(
        outputUri: Uri? = null,
        width: Int = WIDTH,
        height: Int = HEIGHT,
        bitRate: Int = BIT_RATE
    ): Surface? {
        if (currentState is RecordingState.Recording ||
            currentState is RecordingState.Starting) {
            Log.w(TAG, "Already recording")
            return null
        }

        currentState = RecordingState.Starting

        try {
            // Create encoder
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_QUALITY, 0)
                }
            }

            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec?.createInputSurface()
            mediaCodec?.start()

            // Create output file
            val uri = outputUri ?: createOutputFile()
            val filePath = getFilePathFromUri(uri) ?: ""

            // Create MediaMuxer
            mediaMuxer = MediaMuxer(filePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Start encoder in background
            recordingJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                encodeAndMux()
            }

            Log.d(TAG, "Recording started: $filePath")
            currentState = RecordingState.Recording

            return inputSurface
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            currentState = RecordingState.Error("Failed to start: ${e.message}")
            cleanup()
            return null
        }
    }

    /**
     * Stop the current recording and finalize the MP4 file.
     */
    fun stopRecording() {
        if (currentState !is RecordingState.Recording &&
            currentState !is RecordingState.Starting) return

        Log.d(TAG, "Stopping recording...")

        recordingJob?.cancel()
        recordingJob = null

        cleanup()

        Log.d(TAG, "Recording stopped")
    }

    /**
     * Encode frames from the input surface and mux into MP4.
     */
    private suspend fun encodeAndMux() = withContext(Dispatchers.IO) {
        val codec = mediaCodec ?: return@withContext
        val muxer = mediaMuxer ?: return@withContext
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            while (isActive) {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)

                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // No output available yet; continue
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            val newFormat = codec.outputFormat
                            trackIndex = muxer.addTrack(newFormat)
                            muxer.start()
                            muxerStarted = true
                            Log.d(TAG, "Muxer started with track $trackIndex")
                        }
                    }
                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && muxerStarted) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                                frameCount++
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }

                // Handle end-of-stream
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    Log.d(TAG, "End of stream reached, frames written: $frameCount")
                    break
                }

                // Small yield to prevent tight loop
                delay(1)
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "Encoding cancelled, frames written: $frameCount")
            // Signal end of stream to encoder
            try {
                codec.signalEndOfInputStream()
                // Drain remaining output
                drainEncoder(codec, muxer)
            } catch (_: Exception) { }
        } catch (e: Exception) {
            Log.e(TAG, "Encoding error: ${e.message}", e)
            currentState = RecordingState.Error("Encoding error: ${e.message}")
        }
    }

    /**
     * Drain remaining frames from the encoder after signaling EOS.
     */
    private fun drainEncoder(codec: MediaCodec, muxer: MediaMuxer) {
        val bufferInfo = MediaCodec.BufferInfo()
        var timedOut = 0

        while (timedOut < 5) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    timedOut++
                }
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && muxerStarted) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                    timedOut = 0
                }
            }
        }
    }

    /**
     * Clean up encoder, muxer, and surface resources.
     */
    private fun cleanup() {
        try {
            // Stop muxer
            if (muxerStarted) {
                mediaMuxer?.stop()
                muxerStarted = false
            }
            mediaMuxer?.release()
            mediaMuxer = null

            // Stop encoder
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null

            inputSurface = null
            trackIndex = -1
            frameCount = 0

        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error: ${e.message}", e)
        }

        if (currentState is RecordingState.Recording) {
            currentState = RecordingState.Idle
        }
    }

    /**
     * Create a new output file in the Movies/EyePlus directory.
     */
    private fun createOutputFile(): Uri {
        val timestamp = dateFormatter.format(Date())
        val fileName = "EyePlus_$timestamp.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use MediaStore for Android 10+
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/EyePlus")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

            // Mark as not pending
            if (uri != null) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            return uri ?: Uri.EMPTY
        } else {
            // Legacy storage for Android 9 and below
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "EyePlus"
            )
            dir.mkdirs()
            val file = File(dir, fileName)
            return Uri.fromFile(file)
        }
    }

    /**
     * Convert a content URI to a file path for MediaMuxer.
     */
    private fun getFilePathFromUri(uri: Uri): String? {
        if (uri == Uri.EMPTY) return null

        // File URI
        if (uri.scheme == "file") return uri.path

        // For MediaStore URIs on Android 10+, we need a temp file path
        // because MediaMuxer requires a file descriptor or file path
        return try {
            // Use the app's cache directory for recording
            val timestamp = dateFormatter.format(Date())
            val cacheFile = File(context.cacheDir, "recordings")
            cacheFile.mkdirs()
            val file = File(cacheFile, "temp_$timestamp.mp4")
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Register a callback for recording state changes.
     */
    fun addStateListener(listener: (RecordingState) -> Unit) {
        stateListeners.add(listener)
    }

    fun removeStateListener(listener: (RecordingState) -> Unit) {
        stateListeners.remove(listener)
    }

    /**
     * Get current recording state.
     */
    fun getState(): RecordingState = currentState

    val isRecording: Boolean get() = currentState is RecordingState.Recording
}
