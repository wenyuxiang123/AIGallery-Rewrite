package com.aigallery.rewrite.memory

import android.content.Context
import com.aigallery.rewrite.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * 向量存储引擎
 * 负责记忆的向量存储、相似度检索和分层管理
 */
@Singleton
class VectorStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val TAG = "VectorStore"

    // 内存中的向量缓存 (memoryId -> FloatArray)
    private val vectorCache = mutableMapOf<String, FloatArray>()

    // 内存存储（替代数据库）
    private val memoryStorage = MutableStateFlow<MutableList<MemoryItem>>(mutableListOf())

    /**
     * 添加记忆
     */
    suspend fun addMemory(memory: MemoryItem): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 生成向量嵌入
                val embedding = generateEmbedding(memory.content)

                // 存入内存
                memoryStorage.value.add(memory)
                vectorCache[memory.id] = embedding

                FileLogger.d(TAG, "Memory added: ${memory.id}")
                true
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to add memory", e)
                false
            }
        }
    }

    /**
     * 批量添加记忆
     */
    suspend fun addMemories(memories: List<MemoryItem>): Int {
        var count = 0
        memories.forEach { if (addMemory(it)) count++ }
        return count
    }

    /**
     * 搜索相似记忆
     * @param query 查询文本
     * @param topK 返回最相似的 K 条
     * @param memoryType 按类型过滤
     * @return 相似记忆列表（带相似度分数）
     */
    suspend fun searchSimilar(
        query: String,
        topK: Int = 5,
        memoryType: MemoryType? = null
    ): List<MemorySearchResult> {
        return withContext(Dispatchers.IO) {
            try {
                // 生成查询向量
                val queryVector = generateEmbedding(query)

                // 获取所有候选记忆
                val allMemories = memoryType?.let { type ->
                    memoryStorage.value.filter { it.type == type }
                } ?: memoryStorage.value

                // 计算相似度
                val results = allMemories.mapNotNull { memory ->
                    val memoryVector = vectorCache[memory.id] ?: generateEmbedding(memory.content)
                    vectorCache[memory.id] = memoryVector
                    val similarity = cosineSimilarity(queryVector, memoryVector)

                    // 只返回相似度大于阈值的结果
                    if (similarity > 0.5f) {
                        MemorySearchResult(
                            memory = memory,
                            similarityScore = similarity
                        )
                    } else null
                }

                // 按相似度排序并返回前 K 条
                results.sortedByDescending { it.similarityScore }
                    .take(topK)
            } catch (e: Exception) {
                FileLogger.e(TAG, "Search failed", e)
                emptyList()
            }
        }
    }

    /**
     * 获取上下文相关的记忆（用于 LLM 对话）
     * 结合相似度、重要性、时效性进行排序
     */
    suspend fun getContextMemories(query: String, topK: Int = 8): List<MemoryItem> {
        val searchResults = searchSimilar(query, topK = topK)

        // 混合排序：相似度(60%) + 重要性(30%) + 时效性(10%)
        val now = System.currentTimeMillis()
        val weightedResults = searchResults.map { result ->
            val daysOld = (now - result.memory.createdAt) / (1000 * 60 * 60 * 24f)
            val recencyScore = 1f / (1f + daysOld / 30f) // 30 天衰减一半
            val importanceScore = result.memory.importance / 10f

            val finalScore = (result.similarityScore * 0.6f) +
                    (importanceScore * 0.3f) +
                    (recencyScore * 0.1f)

            result.memory to finalScore
        }

        return weightedResults
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /**
     * 获取某一类型的所有记忆
     */
    suspend fun getMemoriesByType(type: MemoryType): List<MemoryItem> {
        return withContext(Dispatchers.IO) {
            memoryStorage.value.filter { it.type == type }
        }
    }

    /**
     * 获取所有记忆（Flow 形式，UI 可订阅更新）
     */
    fun getAllMemoriesFlow(): Flow<List<MemoryItem>> {
        return memoryStorage.map { it.toList() }
    }

    /**
     * 更新记忆
     */
    suspend fun updateMemory(memory: MemoryItem): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val index = memoryStorage.value.indexOfFirst { it.id == memory.id }
                if (index != -1) {
                    memoryStorage.value[index] = memory
                    // 重新生成向量
                    vectorCache[memory.id] = generateEmbedding(memory.content)
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Update failed", e)
                false
            }
        }
    }

    /**
     * 删除记忆
     */
    suspend fun deleteMemory(memoryId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val removed = memoryStorage.value.removeAll { it.id == memoryId }
                vectorCache.remove(memoryId)
                removed
            } catch (e: Exception) {
                FileLogger.e(TAG, "Delete failed", e)
                false
            }
        }
    }

    /**
     * 清除指定类型的所有记忆
     */
    suspend fun clearMemoryType(type: MemoryType): Int {
        return withContext(Dispatchers.IO) {
            val toRemove = memoryStorage.value.filter { it.type == type }
            memoryStorage.value.removeAll(toRemove)
            toRemove.size
        }
    }

    /**
     * 记录记忆被访问
     */
    suspend fun recordAccess(memoryId: String) {
        withContext(Dispatchers.IO) {
            val index = memoryStorage.value.indexOfFirst { it.id == memoryId }
            if (index != -1) {
                val memory = memoryStorage.value[index]
                memoryStorage.value[index] = memory.copy(
                    accessCount = memory.accessCount + 1,
                    lastAccessedAt = System.currentTimeMillis()
                )
            }
        }
    }

    /**
     * 迁移瞬时记忆到短期记忆（会话结束时调用）
     */
    suspend fun consolidateSessionMemories(sessionId: String) {
        // TODO: 实现记忆合并和迁移逻辑
        // 1. 找到所有标记为瞬时记忆的相关内容
        // 2. 合并相似记忆
        // 3. 提升重要记忆到短期/长期记忆
    }

    /**
     * 生成文本的向量嵌入
     */
    private fun generateEmbedding(text: String): FloatArray {
        // TODO: 使用真实的嵌入模型
        // 当前使用简单的哈希向量作为占位
        val vector = FloatArray(768)
        val hash = text.hashCode()

        for (i in vector.indices) {
            val seed = (hash + i * 137) xor text.length
            vector[i] = ((seed and 0xFF) / 255.0f - 0.5f) * 0.1f
        }

        // 归一化
        var norm = 0f
        for (v in vector) norm += v * v
        norm = sqrt(norm)
        if (norm > 0) {
            for (i in vector.indices) vector[i] /= norm
        }

        return vector
    }

    /**
     * 计算余弦相似度
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator > 0) dotProduct / denominator else 0f
    }
}

/**
 * 记忆类型（5 层记忆系统）
 */
enum class MemoryType {
    INSTANT,     // 瞬时记忆 - 当前对话上下文（滚动窗口）
    SHORT_TERM,  // 短期记忆 - 当前会话全部内容
    WORKING,     // 工作记忆 - 任务执行状态
    LONG_TERM,   // 长期记忆 - 历史对话摘要
    CORE         // 核心记忆 - 用户偏好、关键信息
}

/**
 * 记忆数据项
 */
data class MemoryItem(
    val id: String = generateMemoryId(),
    val content: String,
    val type: MemoryType,
    val importance: Int = 5, // 1-10
    val createdAt: Long = System.currentTimeMillis(),
    val accessCount: Int = 0,
    val lastAccessedAt: Long = createdAt,
    val metadata: Map<String, String>? = null
) {
    /**
     * 获取格式化的时间显示
     */
    fun getFormattedTime(): String {
        val elapsed = System.currentTimeMillis() - createdAt
        val minutes = elapsed / (1000 * 60)
        val hours = minutes / 60
        val days = hours / 24

        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes}分钟前"
            hours < 24 -> "${hours}小时前"
            days < 7 -> "${days}天前"
            else -> "${days / 7}周前"
        }
    }

    /**
     * 获取类型显示名称
     */
    fun getTypeName(): String = when (type) {
        MemoryType.INSTANT -> "瞬时"
        MemoryType.SHORT_TERM -> "短期"
        MemoryType.WORKING -> "工作"
        MemoryType.LONG_TERM -> "长期"
        MemoryType.CORE -> "核心"
    }

    /**
     * 获取重要性星级
     */
    fun getImportanceStars(): String = "★".repeat(importance) + "☆".repeat(10 - importance)
}

/**
 * 记忆搜索结果（带相似度分数）
 */
data class MemorySearchResult(
    val memory: MemoryItem,
    val similarityScore: Float
)

/**
 * 生成唯一记忆 ID
 */
private fun generateMemoryId(): String {
    return "mem_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
}
