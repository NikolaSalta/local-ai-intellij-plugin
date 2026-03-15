package com.example.localai.model

/**
 * Represents a role in the chat conversation.
 */
enum class ChatRole {
    USER,
    ASSISTANT,
    TOOL,
    SYSTEM
}

/**
 * Represents the active chat mode.
 */
enum class ChatMode {
    ASK,
    PLAN,
    AGENT
}

/**
 * A single message in the chat conversation.
 */
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
