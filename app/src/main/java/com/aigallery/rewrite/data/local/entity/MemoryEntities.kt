package com.aigallery.rewrite.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Base memory entity
 */
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey
    val id: String,
    val layer: String, // MemoryLayer name
    val content: String,
    val summary: String,
    val tags: String, // JSON array
    val isEnabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val metadata: String? // JSON for layer-specific data
)

/**
 * Working memory specific entity
 */
@Entity(tableName = "working_memories")
data class WorkingMemoryEntity(
    @PrimaryKey
    val id: String,
    val content: String,
    val sessionId: String?,
    val isEnabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Short-term memory entity
 */
@Entity(tableName = "short_term_memories")
data class ShortTermMemoryEntity(
    @PrimaryKey
    val id: String,
    val content: String,
    val summary: String,
    val importance: Float,
    val turnCount: Int,
    val isEnabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Long-term memory entity
 */
@Entity(tableName = "long_term_memories")
data class LongTermMemoryEntity(
    @PrimaryKey
    val id: String,
    val content: String,
    val summary: String,
    val tags: String,
    val embedding: String?, // JSON array of floats
    val sourceConversationId: String?,
    val accessCount: Int,
    val lastAccessedAt: Long,
    val isEnabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Knowledge base memory entity
 */
@Entity(tableName = "knowledge_base_memories")
data class KnowledgeBaseMemoryEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val content: String,
    val sourceType: String,
    val sourceUrl: String?,
    val filePath: String?,
    val chunkIndex: Int,
    val totalChunks: Int,
    val tags: String,
    val isEnabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Persona memory entity
 */
@Entity(tableName = "persona_memories")
data class PersonaMemoryEntity(
    @PrimaryKey
    val id: String,
    val personaType: String,
    val content: String,
    val personalityTraits: String, // JSON map
    val tags: String,
    val isEnabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)


/**
 * FTS5 full-text search entity for memories
 * Mirrors MemoryEntity content for full-text search capability
 */
@Fts4(contentEntity = MemoryEntity::class)
@Entity(tableName = "memories_fts")
data class MemoryFts(
    val content: String,
    val tags: String
)
