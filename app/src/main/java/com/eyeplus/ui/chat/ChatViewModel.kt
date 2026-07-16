package com.eyeplus.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eyeplus.data.ai.ChatMessage
import com.eyeplus.data.ai.GeminiAnalyzer
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for the AI chat interface.
 *
 * Manages conversation with Gemini AI, including:
 * - Text message history
 * - Streaming responses
 * - System event contextualization
 */
class ChatViewModel : ViewModel() {

    data class ChatUiState(
        val messages: List<ChatMessage> = emptyList(),
        val isWaitingForResponse: Boolean = false,
        val isStreaming: Boolean = false,
        val currentStreamingText: String = "",
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var geminiAnalyzer: GeminiAnalyzer? = null

    // Track whether the welcome message has been shown
    private var welcomeShown = false

    fun initialize(apiKey: String? = null) {
        if (geminiAnalyzer != null) return

        geminiAnalyzer = GeminiAnalyzer(apiKey = apiKey)

        if (!welcomeShown) {
            welcomeShown = true
            addSystemMessage("Dobrý den! Jsem EyePlus AI asistent. " +
                "Můžete se mnou komunikovat o dění ve vašem monitorovaném prostoru. " +
                "Co potřebujete vědět?")
        }
    }

    /**
     * Send a chat message and get AI response.
     */
    fun sendMessage(text: String) {
        val analyzer = geminiAnalyzer ?: return
        if (text.isBlank() || _uiState.value.isWaitingForResponse) return

        // Add user message immediately
        val userMessage = ChatMessage(role = "user", text = text)
        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                isWaitingForResponse = true,
                isStreaming = true,
                error = null
            )
        }

        viewModelScope.launch {
            try {
                // Use streaming for better UX
                analyzer.chatStream(text).collect { response ->
                    if (response.isStreaming) {
                        _uiState.update { state ->
                            // Replace the last message if it's the streaming one
                            val msgs = state.messages.toMutableList()
                            if (msgs.lastOrNull()?.isStreaming == true) {
                                msgs[msgs.lastIndex] = response
                            } else {
                                msgs.add(response)
                            }
                            state.copy(
                                messages = msgs,
                                isStreaming = true,
                                currentStreamingText = response.text
                            )
                        }
                    } else {
                        _uiState.update { state ->
                            val msgs = state.messages.toMutableList()
                            // Update or add the final message
                            if (msgs.lastOrNull()?.isStreaming == true) {
                                msgs[msgs.lastIndex] = response
                            } else {
                                msgs.add(response)
                            }
                            state.copy(
                                messages = msgs,
                                isWaitingForResponse = false,
                                isStreaming = false,
                                currentStreamingText = ""
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        isWaitingForResponse = false,
                        isStreaming = false,
                        currentStreamingText = "",
                        error = "Chyba komunikace: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Add a system/event message to the conversation context.
     */
    fun addSystemMessage(text: String) {
        val msg = ChatMessage(role = "system", text = text)
        _uiState.update { state ->
            state.copy(messages = state.messages + msg)
        }
    }

    /**
     * Add an AI event notification (e.g., person detected).
     */
    fun addEventNotification(text: String) {
        geminiAnalyzer?.addSystemMessage(text)
        addSystemMessage("🔔 $text")
    }

    /**
     * Clear the conversation history.
     */
    fun clearChat() {
        geminiAnalyzer?.clearHistory()
        _uiState.update {
            ChatUiState()
        }
        welcomeShown = false
        initialize()
    }

    override fun onCleared() {
        super.onCleared()
    }
}
