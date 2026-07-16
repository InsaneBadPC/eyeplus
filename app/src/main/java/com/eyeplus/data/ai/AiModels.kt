package com.eyeplus.data.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Result of AI analysis on a camera frame.
 */
@Serializable
data class FrameAnalysis(
    @SerialName("people_detected") val peopleDetected: Boolean = false,
    @SerialName("person_count") val personCount: Int = 0,
    @SerialName("familiar_person") val familiarPerson: Boolean = false,
    @SerialName("suspicious_activity") val suspiciousActivity: Boolean = false,
    val description: String = "",
    @SerialName("alert_level") val alertLevel: String = "none", // none, low, medium, high
    val activity: String = "",
    val error: String? = null
)

/**
 * Result from ML Kit on-device person detection.
 */
data class OnDeviceDetection(
    val hasPerson: Boolean = false,
    val personCount: Int = 0,
    val confidence: Float = 0f
)

/**
 * Chat message in the AI conversation history.
 */
data class ChatMessage(
    val role: String, // "user" or "model" or "system"
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false
)

/**
 * System prompt for the AI security guard.
 */
val SECURITY_SYSTEM_PROMPT = """
You are an AI security camera guard named EyePlus, monitoring a PTZ camera.

Your responsibilities:
1. Analyze surveillance camera images for people and activity
2. Distinguish between familiar people (homeowners) and strangers
3. Report security-relevant observations
4. Be concise and factual in your analysis
5. When asked questions, answer based on your surveillance history

OUTPUT FORMAT - Always respond in this JSON structure for image analysis:
{
    "people_detected": true/false,
    "person_count": number,
    "familiar_person": true/false,
    "suspicious_activity": true/false,
    "description": "brief scene description",
    "alert_level": "none" | "low" | "medium" | "high",
    "activity": "what people are doing"
}

For text conversation, respond naturally and helpfully. You are monitoring
a building and can report on what you've observed.
""".trimIndent()
