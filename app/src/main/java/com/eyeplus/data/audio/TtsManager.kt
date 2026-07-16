package com.eyeplus.data.audio

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.*

/**
 * Text-to-Speech manager using Android's built-in TTS engine.
 *
 * 100% offline and free. Used for:
 * - Greeting the user when detected
 * - Reporting security events through the camera speaker
 * - Conversational responses in voice interaction mode
 * - Status announcements
 */
class TtsManager(context: Context) {

    companion object {
        private const val TAG = "TtsManager"
        private const val UTTERANCE_ID = "eyeplus_tts"
    }

    private val tts: TextToSpeech
    private var isInitialized = false
    private var onDone: (() -> Unit)? = null

    /**
     * Broad accent options for multilingual support.
     */
    enum class Language(val tag: String) {
        CZECH("cs-CZ"),
        ENGLISH("en-US"),
        SLOVAK("sk-SK"),
        GERMAN("de-DE"),
        POLISH("pl-PL")
    }

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                setLanguage(Language.CZECH)

                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        onDone?.invoke()
                    }
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS error on $utteranceId")
                        onDone?.invoke()
                    }
                })

                Log.d(TAG, "TTS initialized successfully")
            } else {
                Log.e(TAG, "TTS init failed with status: $status")
            }
        }
    }

    /**
     * Set the TTS language. Falls back to English if requested language unavailable.
     */
    fun setLanguage(language: Language): Boolean {
        val locale = Locale.forLanguageTag(language.tag)
        val result = tts.setLanguage(locale)
        return when (result) {
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_AVAILABLE -> {
                Log.d(TAG, "Language set to ${language.tag}")
                true
            }
            else -> {
                Log.w(TAG, "Language ${language.tag} not available, using default")
                false
            }
        }
    }

    /**
     * Speak text through the device speaker.
     * (For camera speaker, use [speakToCamera])
     */
    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized yet")
            return
        }

        onDone = onComplete

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        } else {
            @Suppress("DEPRECATION")
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null)
        }

        Log.d(TAG, "Speaking: $text")
    }

    /**
     * Speak text through the camera's speaker via audio backchannel.
     * Delegates to [AudioBackchannel.speakText] which handles the full
     * TTS→WAV→PCM→G.711→RTP→RTSP pipeline.
     */
    suspend fun speakToCamera(text: String, audioBackchannel: AudioBackchannel?) {
        if (!isInitialized || audioBackchannel == null) return
        audioBackchannel.speakText(text)
    }

    /**
     * Stop any ongoing speech.
     */
    fun stop() {
        tts.stop()
    }

    /**
     * Check if TTS is currently speaking.
     */
    fun isSpeaking(): Boolean = tts.isSpeaking

    /**
     * Release TTS resources.
     */
    fun shutdown() {
        tts.stop()
        tts.shutdown()
        isInitialized = false
        Log.d(TAG, "TTS shut down")
    }
}
