package com.aigallery.rewrite.domain.model

import java.util.UUID

/**
 * Five-layer memory system domain models
 * 
 * Memory Architecture:
 * ├── 1. Working Memory - Current session context, auto-cleared
 * ├── 2. Short-term Memory - Recent N turns, sliding window
 * ├── 3. Long-term Memory - Vector DB storage, RAG retrieval
 * ├── 4. Knowledge Base - User-uploaded docs, web content
 * └── 5. Persona Memory - System persona, habits, preferences
 */
sealed class MemoryItem(
    open val id: String = UUID.randomUUID().toString(),
    open val content: String,
    open val createdAt: Long = System.currentTimeMillis(),
    open val updatedAt: Long = System.currentTimeMillis(),
    open val isEnabled: Boolean = true,
    open val tags: List<String> = emptyList()
) {
    abstract val memoryLayer: MemoryLayer
    abstract val summary: String
}

/**
 * Layer 1: Working Memory - Current session context
 * Auto-cleared when session ends
 */
data class WorkingMemory(
    override val id: String = UUID.randomUUID().toString(),
    override val content: String,
    override val createdAt: Long = System.currentTimeMillis(),
    override val updatedAt: Long = System.currentTimeMillis(),
    override val isEnabled: Boolean = true,
    override val tags: List<String> = emptyList(),
    val sessionId: String? = null
) : MemoryItem(id, content, createdAt, updatedAt, isEnabled, tags) {
    override val memoryLayer = MemoryLayer.WORKING
    override val summary: String = content.take(50) + if (content.length > 50) "..." else ""
}

/**
 * Layer 2: Short-term Memory - Recent N turns with sliding window
 */
data class ShortTermMemory(
    override val id: String = UUID.randomUUID().toString(),
    override val content: String,
    override val createdAt: Long = System.currentTimeMillis(),
    override val updatedAt: Long = System.currentTimeMillis(),
    override val isEnabled: Boolean = true,
    override val tags: List<String> = emptyList(),
    val importance: Float = 0.5f,
    val turnCount: Int = 1
) : MemoryItem(id, content, createdAt, updatedAt, isEnabled, tags) {
    override val memoryLayer = MemoryLayer.SHORT_TERM
    override val summary: String = content.take(80) + if (content.length > 80) "..." else ""
}

/**
 * Layer 3: Long-term Memory - Semantic storage with RAG
 */
data class LongTermMemory(
    override val id: String = UUID.randomUUID().toString(),
    override val content: String,
    override val createdAt: Long = System.currentTimeMillis(),
    override val updatedAt: Long = System.currentTimeMillis(),
    override val isEnabled: Boolean = true,
    override val tags: List<String> = emptyList(),
    val embedding: List<Float>? = null,
    val sourceConversationId: String? = null,
    val accessCount: Int = 0,
    val lastAccessedAt: Long = System.currentTimeMillis()
) : MemoryItem(id, content, createdAt, updatedAt, isEnabled, tags) {
    override val memoryLayer = MemoryLayer.LONG_TERM
    override val summary: String = content.take(100) + if (content.length > 100) "..." else ""
}

/**
 * Layer 4: Knowledge Base - User uploaded documents and web content
 */
data class KnowledgeBaseMemory(
    override val id: String = UUID.randomUUID().toString(),
    override val content: String,
    override val createdAt: Long = System.currentTimeMillis(),
    override val updatedAt: Long = System.currentTimeMillis(),
    override val isEnabled: Boolean = true,
    override val tags: List<String> = emptyList(),
    val title: String,
    val sourceType: KnowledgeSourceType,
    val sourceUrl: String? = null,
    val filePath: String? = null,
    val chunkIndex: Int = 0,
    val totalChunks: Int = 1
) : MemoryItem(id, content, createdAt, updatedAt, isEnabled, tags) {
    override val memoryLayer = MemoryLayer.KNOWLEDGE_BASE
    override val summary: String = "[$title] ${content.take(60)}${if (content.length > 60) "..." else ""}"
}

enum class KnowledgeSourceType {
    PDF_DOCUMENT,
    TEXT_FILE,
    WEB_PAGE,
    NOTIFICATION,
    USER_NOTES
}

/**
 * Layer 5: Persona Memory - System persona, habits, preferences
 */
data class PersonaMemory(
    override val id: String = UUID.randomUUID().toString(),
    override val content: String,
    override val createdAt: Long = System.currentTimeMillis(),
    override val updatedAt: Long = System.currentTimeMillis(),
    override val isEnabled: Boolean = true,
    override val tags: List<String> = emptyList(),
    val personaType: PersonaType,
    val personalityTraits: Map<String, Float> = emptyMap()
) : MemoryItem(id, content, createdAt, updatedAt, isEnabled, tags) {
    override val memoryLayer = MemoryLayer.PERSONA
    override val summary: String = "[${personaType.displayName}] ${content.take(50)}${if (content.length > 50) "..." else ""}"
}

enum class PersonaType(val displayName: String) {
    SYSTEM_PROMPT("系统提示词"),
    USER_HABITS("用户习惯"),
    PREFERENCES("偏好设置"),
    CUSTOM_PERSONA("自定义人设")
}

/**
 * Memory layers enumeration
 */
enum class MemoryLayer(val displayName: String, val description: String) {
    WORKING("工作记忆", "当前会话上下文，自动清空"),
    SHORT_TERM("短期记忆", "最近N轮对话，滑动窗口保留"),
    LONG_TERM("长期记忆", "向量数据库存储，RAG检索召回"),
    KNOWLEDGE_BASE("知识库", "用户上传文档、网页内容索引"),
    PERSONA("角色记忆", "系统角色设定、人设、习惯偏好")
}

/**
 * Memory system configuration
 */
data class MemoryConfig(
    val workingMemoryEnabled: Boolean = true,
    val shortTermMemoryEnabled: Boolean = true,
    val shortTermWindowSize: Int = 10,
    val longTermMemoryEnabled: Boolean = true,
    val longTermRetrievalLimit: Int = 5,
    val knowledgeBaseEnabled: Boolean = true,
    val personaMemoryEnabled: Boolean = true,
    val memoryFusionStrategy: MemoryFusionStrategy = MemoryFusionStrategy.UNIFIED
)

enum class MemoryFusionStrategy {
    UNIFIED,      // 统一注入所有启用的记忆
    HIERARCHICAL, // 按层级优先级注入
    SELECTIVE      // 只注入与当前上下文相关的记忆
}

/**
 * Memory retrieval result
 */
data class MemoryRetrievalResult(
    val memories: List<MemoryItem>,
    val totalCount: Int,
    val layerBreakdown: Map<MemoryLayer, Int>,
    val retrievalTimeMs: Long
)

/**
 * Five-layer memory state
 */
data class MemoryState(
    val workingMemories: List<WorkingMemory> = emptyList(),
    val shortTermMemories: List<ShortTermMemory> = emptyList(),
    val longTermMemories: List<LongTermMemory> = emptyList(),
    val knowledgeBaseMemories: List<KnowledgeBaseMemory> = emptyList(),
    val personaMemories: List<PersonaMemory> = emptyList(),
    val config: MemoryConfig = MemoryConfig(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val searchResults: List<MemoryItem> = emptyList()
)
