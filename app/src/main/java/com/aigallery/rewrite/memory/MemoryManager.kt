package com.aigallery.rewrite.memory

import android.content.Context
import com.aigallery.rewrite.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记忆管理器
 * 负责自动提取、存储和检索对话中的重要信息
 */
@Singleton
class MemoryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vectorStore: VectorStore
) {

    private val TAG = "MemoryManager"

    /**
     * 处理一条新消息，提取并存储记忆
     */
    suspend fun processMessage(
        message: String,
        role: MessageRole,
        sessionId: String = "default"
    ) {
        withContext(Dispatchers.Default) {
            // 1. 先存入瞬时记忆（所有消息都存入）
            if (message.length > 10) { // 太短的消息不记录
                val instantMemory = MemoryItem(
                    content = message,
                    type = MemoryType.INSTANT,
                    importance = 3
                )
                vectorStore.addMemory(instantMemory)
                FileLogger.d(TAG, "Stored to instant memory: ${message.take(30)}...")
            }

            // 2. 分析并提取重要信息到短期/长期记忆
            if (role == MessageRole.USER) {
                analyzeAndExtractImportantMemory(message, sessionId)
            }
        }
    }

    /**
     * 分析消息内容，提取重要记忆
     */
    private suspend fun analyzeAndExtractImportantMemory(message: String, sessionId: String) {
        // 简单的规则分析（真实实现可使用 NLP 模型）
        val lowerMessage = message.lowercase()

        // 检测用户偏好
        if (lowerMessage.contains("我喜欢") || lowerMessage.contains("我偏好") ||
            lowerMessage.contains("我想要") || lowerMessage.contains("我希望")) {
            storeCoreMemory(message, "user_preference")
            return
        }

        // 检测个人信息
        if (lowerMessage.contains("我的名字") || lowerMessage.contains("我叫") ||
            lowerMessage.contains("我是") && message.length < 20) {
            storeCoreMemory(message, "user_info")
            return
        }

        // 检测任务相关
        if (lowerMessage.contains("任务") || lowerMessage.contains("待办") ||
            lowerMessage.contains("记住") || lowerMessage.contains("别忘了")) {
            storeWorkingMemory(message, sessionId)
            return
        }

        // 检测重要的问答内容
        if (message.length > 50 && (message.contains("?") || message.contains("？"))) {
            storeLongTermMemory(message)
        }
    }

    /**
     * 存储核心记忆（用户偏好、重要个人信息）
     */
    private suspend fun storeCoreMemory(content: String, category: String) {
        val memory = MemoryItem(
            content = content,
            type = MemoryType.CORE,
            importance = 10,
            metadata = mapOf("category" to category)
        )
        vectorStore.addMemory(memory)
        FileLogger.d(TAG, "Stored to CORE memory: ${content.take(50)}...")
    }

    /**
     * 存储工作记忆（任务、待办）
     */
    private suspend fun storeWorkingMemory(content: String, sessionId: String) {
        val memory = MemoryItem(
            content = content,
            type = MemoryType.WORKING,
            importance = 7,
            metadata = mapOf("sessionId" to sessionId)
        )
        vectorStore.addMemory(memory)
        FileLogger.d(TAG, "Stored to WORKING memory: ${content.take(50)}...")
    }

    /**
     * 存储长期记忆（重要对话摘要）
     */
    private suspend fun storeLongTermMemory(content: String) {
        val memory = MemoryItem(
            content = content,
            type = MemoryType.LONG_TERM,
            importance = 6
        )
        vectorStore.addMemory(memory)
        FileLogger.d(TAG, "Stored to LONG_TERM memory: ${content.take(50)}...")
    }

    /**
     * 构建上下文提示词 - 注入相关记忆到对话中
     */
    suspend fun buildContextPrompt(currentQuery: String): String {
        // 搜索相关记忆
        val relevantMemories = vectorStore.getContextMemories(currentQuery, topK = 5)

        if (relevantMemories.isEmpty()) return ""

        val promptBuilder = StringBuilder()
        promptBuilder.append("\n【相关记忆】\n")

        relevantMemories.forEachIndexed { index, memory ->
            val typeLabel = when (memory.type) {
                MemoryType.CORE -> "🔴 核心"
                MemoryType.LONG_TERM -> "🟡 长期"
                MemoryType.WORKING -> "🔵 工作"
                MemoryType.SHORT_TERM -> "🟢 短期"
                MemoryType.INSTANT -> "⚪ 瞬时"
            }
            promptBuilder.append("$index. [$typeLabel] ${memory.content}\n")
        }

        promptBuilder.append("\n请结合以上记忆来回答用户的问题。\n\n")
        return promptBuilder.toString()
    }

    /**
     * 获取指定类型的所有记忆
     */
    suspend fun getMemoriesByType(type: MemoryType): List<MemoryItem> {
        return vectorStore.getMemoriesByType(type)
    }

    /**
     * 获取所有记忆（Flow 形式，支持 UI 实时更新）
     */
    fun getAllMemoriesFlow(): Flow<List<MemoryItem>> {
        return vectorStore.getAllMemoriesFlow()
    }

    /**
     * 搜索记忆
     */
    suspend fun searchMemories(query: String, topK: Int = 10): List<MemorySearchResult> {
        return vectorStore.searchSimilar(query, topK)
    }

    /**
     * 删除记忆
     */
    suspend fun deleteMemory(memoryId: String): Boolean {
        return vectorStore.deleteMemory(memoryId)
    }

    /**
     * 清除指定类型的记忆
     */
    suspend fun clearMemoryType(type: MemoryType): Int {
        return vectorStore.clearMemoryType(type)
    }

    /**
     * 提升记忆层级（从瞬时提升到短期）
     */
    suspend fun promoteMemory(memoryId: String, newType: MemoryType) {
        // TODO: 实现记忆提升逻辑
    }

    /**
     * 会话结束时进行记忆整合
     */
    suspend fun consolidateSession(sessionId: String) {
        FileLogger.d(TAG, "Consolidating memories for session: $sessionId")
        // 1. 获取所有瞬时记忆
        // 2. 去重和摘要
        // 3. 提升重要内容到短期记忆
        vectorStore.consolidateSessionMemories(sessionId)
    }

    /**
     * 获取记忆统计信息
     */
    suspend fun getMemoryStats(): Map<MemoryType, Int> {
        val stats = mutableMapOf<MemoryType, Int>()
        MemoryType.values().forEach { type ->
            stats[type] = getMemoriesByType(type).size
        }
        return stats
    }
}

/**
 * 消息角色（与 ChatSessionViewModel 保持一致）
 */
enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT
}
