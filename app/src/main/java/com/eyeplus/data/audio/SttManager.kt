package com.eyeplus.data.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.*

/**
 * Speech-to-Text manager using Android's built-in speech recognizer.
 *
 * 100% offline and free (download offline language pack first).
 * Used for:
 * - Voice commands for camera control ("turn left", "zoom in")
 * - Conversational voice interaction with AI
 * - Hands-free operation
 */
class SttManager(private val context: Context) {

    companion object {
        private const val TAG = "SttManager"
    }

    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private val resultsChannel = Channel<SttResult>(Channel.CONFLATED)

    /**
     * Possible states of speech recognition.
     */
    sealed class SttResult {
        data class Result(val text: String, val isFinal: Boolean) : SttResult()
        data class Partial(val text: String) : SttResult()
        data object Error : SttResult()
        data object SilenceDetected : SttResult()
        data object NoMatch : SttResult()
    }

    /**
     * Start listening for speech input.
     * Returns a Flow of recognition results (partial and final).
     */
    fun startListening( languageTag: String = "cs-CZ"): Flow<SttResult> {
        stopListening()

        recognizer = SpeechRecognizer.createSpeechRecognizer(context)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.forLanguageTag(languageTag))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)

            // For offline operation
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                Log.d(TAG, "Ready for speech")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Speech detected")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "End of speech")
            }

            override fun onError(error: Int) {
                isListening = false
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> {
                        resultsChannel.trySend(SttResult.NoMatch)
                        "No match"
                    }
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        resultsChannel.trySend(SttResult.SilenceDetected)
                        "Speech timeout"
                    }
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "No permission"
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Language not supported"
                    else -> "Unknown error: $error"
                }
                Log.e(TAG, "STT error: $errorMsg")
                if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    // Already sent above
                } else {
                    resultsChannel.trySend(SttResult.Error)
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                Log.d(TAG, "Final result: $text")
                resultsChannel.trySend(SttResult.Result(text, isFinal = true))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                if (text.isNotEmpty()) {
                    resultsChannel.trySend(SttResult.Partial(text))
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer?.startListening(intent)
        Log.d(TAG, "Listening started")

        return resultsChannel.receiveAsFlow()
    }

    /**
     * Stop listening if currently active.
     */
    fun stopListening() {
        if (isListening) {
            recognizer?.stopListening()
            recognizer?.destroy()
            recognizer = null
            isListening = false
            Log.d(TAG, "Listening stopped")
        }
    }

    /**
     * Check if currently listening.
     */
    fun isListening(): Boolean = isListening

    /**
     * Cancel current recognition without destroying the recognizer.
     */
    fun cancel() {
        recognizer?.cancel()
        isListening = false
    }
}
