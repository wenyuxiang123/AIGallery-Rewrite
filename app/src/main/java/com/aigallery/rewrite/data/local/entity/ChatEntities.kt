package com.aigallery.rewrite.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Chat message entity for Room database
 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey
    val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val imageUrls: String, // JSON array
    val audioUrl: String?,
    val timestamp: Long,
    val isStreaming: Boolean,
    val error: String?
)

/**
 * Chat session entity for Room database
 */
@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val modelId: String,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Model download entity
 */
@Entity(tableName = "model_downloads")
data class ModelDownloadEntity(
    @PrimaryKey
    val modelId: String,
    val status: String,
    val progress: Float,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val localPath: String?,
    val errorMessage: String?,
    val startedAt: Long,
    val completedAt: Long?
)


/**
 * FTS5 full-text search entity for chat messages
 * Mirrors ChatMessageEntity content for full-text search capability
 */
@Fts4(contentEntity = ChatMessageEntity::class)
@Entity(tableName = "chat_messages_fts")
data class ChatMessageFts(
    val content: String
)
