package com.eyeplus.data.ai

import android.graphics.Bitmap
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeBackend
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Integration with Google Gemini 2.5 Flash API via Firebase AI Logic SDK.
 *
 * Provides:
 * - Frame analysis (image + prompt → JSON)
 * - Text chat (conversation with history)
 * - Conversation history management
 *
 * Uses the Gemini Free Tier (1,500 requests/day, no credit card needed).
 */
class GeminiAnalyzer(private val apiKey: String? = null) {

    companion object {
        private const val TAG = "GeminiAnalyzer"
        private const val MODEL_NAME = "gemini-2.5-flash"
        private const val MAX_RETRIES = 3
        private const val MAX_HISTORY_SIZE = 20

        // Max image dimension for API calls (1024px recommended)
        private const val MAX_IMAGE_DIMENSION = 1024
    }

    private val json = Json { ignoreUnknownKeys = true }

    // Generative model instance
    private val model = Firebase.ai(
        backend = if (apiKey != null) {
            GenerativeBackend.googleAI(apiKey, "https://generativelanguage.googleapis.com")
        } else {
            GenerativeBackend.googleAI()
        }
    ).generativeModel(
        modelName = MODEL_NAME,
        systemInstruction = SECURITY_SYSTEM_PROMPT,
        generationConfig = generationConfig {
            temperature = 0.2f
            maxOutputTokens = 500
        }
    )

    // Conversation history for chat mode
    private val messageHistory = mutableListOf<ChatMessage>()

    /**
     * Analyze a camera frame for people and activity.
     *
     * @param bitmap The camera frame to analyze (will be downscaled to max 1024px)
     * @return Structured analysis result as FrameAnalysis
     */
    suspend fun analyzeFrame(bitmap: Bitmap): FrameAnalysis {
        return withContext(Dispatchers.IO) {
            var lastError: Exception? = null

            for (attempt in 1..MAX_RETRIES) {
                try {
                    val scaled = downscaleBitmap(bitmap, MAX_IMAGE_DIMENSION)

                    val prompt = content {
                        image(scaled)
                        text("""
                            Analyze this surveillance camera image. Return JSON:
                            {
                                "people_detected": boolean,
                                "person_count": number,
                                "familiar_person": boolean,
                                "suspicious_activity": boolean,
                                "description": "what's happening",
                                "alert_level": "none|low|medium|high",
                                "activity": "what people are doing"
                            }
                        """.trimIndent())
                    }

                    val response = model.generateContent(prompt)
                    val text = response.text ?: "{}"

                    return@withContext try {
                        json.decodeFromString<FrameAnalysis>(cleanJsonResponse(text))
                    } catch (e: Exception) {
                        Log.w(TAG, "JSON parse failed: ${e.message}, raw: $text")
                        FrameAnalysis(
                            description = text.take(200),
                            error = "Parse error"
                        )
                    }

                } catch (e: com.google.firebase.ai.type.FirebaseGenerativeAIException) {
                    when (e.statusCode) {
                        429 -> {
                            // Rate limited - exponential backoff
                            val waitMs = 1000L * (1 shl attempt) // 2s, 4s, 8s
                            Log.w(TAG, "Rate limited, retrying in ${waitMs}ms")
                            delay(waitMs)
                            lastError = e
                        }
                        403 -> return@withContext FrameAnalysis(
                            error = "Authentication failed - check your API key"
                        )
                        413 -> {
                            // Request too large - downscale more
                            val smaller = downscaleBitmap(bitmap, MAX_IMAGE_DIMENSION / 2)
                            return@withContext analyzeFrame(smaller)
                        }
                        else -> {
                            lastError = e
                            delay(1000L)
                        }
                    }
                } catch (e: Exception) {
                    lastError = e
                    delay(1000L)
                }
            }

            FrameAnalysis(error = "Failed after $MAX_RETRIES retries: ${lastError?.message}")
        }
    }

    /**
     * Send a text message in the chat conversation.
     * Maintains conversation history for context.
     */
    suspend fun chat(message: String): ChatMessage {
        return withContext(Dispatchers.IO) {
            try {
                // Build content with history for context
                val history = messageHistory
                    .takeLast(MAX_HISTORY_SIZE)
                    .filter { !it.isStreaming }

                val chatContent = history.map { msg ->
                    content(if (msg.role == "user") "user" else "model") {
                        text(msg.text)
                    }
                } + content("user") { text(message) }

                val response = model.generateContent(chatContent)
                val reply = response.text ?: "I couldn't process that request."

                // Update history
                messageHistory.add(ChatMessage(role = "user", text = message))
                val modelMessage = ChatMessage(role = "model", text = reply)
                messageHistory.add(modelMessage)

                // Trim history if too long
                if (messageHistory.size > MAX_HISTORY_SIZE * 2) {
                    messageHistory.removeAt(0)
                    messageHistory.removeAt(0)
                }

                modelMessage
            } catch (e: Exception) {
                Log.e(TAG, "Chat error: ${e.message}", e)
                ChatMessage(
                    role = "model",
                    text = "Error: ${e.message}",
                    isStreaming = false
                )
            }
        }
    }

    /**
     * Streaming version of chat - returns a Flow of text chunks.
     */
    fun chatStream(message: String): Flow<ChatMessage> = flow {
        try {
            // Add user message to history
            messageHistory.add(ChatMessage(role = "user", text = message))

            val history = messageHistory
                .takeLast(MAX_HISTORY_SIZE)
                .filter { !it.isStreaming }
                .map { msg ->
                    content(if (msg.role == "user") "user" else "model") {
                        text(msg.text)
                    }
                } + content("user") { text(message) }

            val fullResponse = StringBuilder()

            model.generateContentStream(history).collect { chunk ->
                chunk.text?.let {
                    fullResponse.append(it)
                    emit(ChatMessage(role = "model", text = fullResponse.toString(), isStreaming = true))
                }
            }

            val finalText = fullResponse.toString()
            messageHistory.add(ChatMessage(role = "model", text = finalText))
            emit(ChatMessage(role = "model", text = finalText, isStreaming = false))

        } catch (e: Exception) {
            emit(ChatMessage(role = "model", text = "Error: ${e.message}", isStreaming = false))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Get the current conversation history.
     */
    fun getHistory(): List<ChatMessage> = messageHistory.toList()

    /**
     * Clear conversation history.
     */
    fun clearHistory() {
        messageHistory.clear()
    }

    /**
     * Add a security event to the conversation context so the AI knows about it.
     */
    fun addSystemMessage(text: String) {
        messageHistory.add(ChatMessage(role = "system", text = "[Event] $text"))
    }

    /**
     * Downscale a bitmap to fit within maxDimension while preserving aspect ratio.
     */
    private fun downscaleBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val ratio = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (ratio >= 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt(),
            (bitmap.height * ratio).toInt(),
            true
        )
    }

    /**
     * Clean the JSON response from the model (strip markdown fences if present).
     */
    private fun cleanJsonResponse(text: String): String {
        return text
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }
}
