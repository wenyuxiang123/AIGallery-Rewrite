package com.aigallery.rewrite.domain.model

import java.util.UUID

/**
 * Chat message domain model
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val imageUrls: List<String> = emptyList(),
    val audioUrl: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val error: String? = null
)

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

/**
 * Chat session domain model
 */
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val modelId: String,
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * LLM chat state
 */
data class LLMChatState(
    val currentSession: ChatSession? = null,
    val sessions: List<ChatSession> = emptyList(),
    val isLoading: Boolean = false,
    val isStreaming: Boolean = false,
    val error: String? = null,
    val selectedModel: AIModel? = null
)

/**
 * Streaming response state
 */
data class StreamingState(
    val currentText: String = "",
    val isComplete: Boolean = false,
    val error: String? = null
)
