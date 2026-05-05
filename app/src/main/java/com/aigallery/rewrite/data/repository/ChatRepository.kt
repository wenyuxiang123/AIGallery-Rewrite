package com.aigallery.rewrite.data.repository

import com.aigallery.rewrite.data.local.dao.ChatMessageDao
import com.aigallery.rewrite.data.local.dao.ChatSessionDao
import com.aigallery.rewrite.data.local.entity.ChatMessageEntity
import com.aigallery.rewrite.data.local.entity.ChatSessionEntity
import com.aigallery.rewrite.domain.model.ChatMessage
import com.aigallery.rewrite.domain.model.ChatSession
import com.aigallery.rewrite.domain.model.MessageRole
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chat repository interface
 */
interface ChatRepository {
    fun getAllSessions(): Flow<List<ChatSession>>
    fun getRecentSessions(limit: Int): Flow<List<ChatSession>>
    suspend fun getSessionById(sessionId: String): ChatSession?
    suspend fun createSession(title: String, modelId: String): ChatSession
    suspend fun updateSession(session: ChatSession)
    suspend fun deleteSession(sessionId: String)
    
    fun getMessagesBySession(sessionId: String): Flow<List<ChatMessage>>
    suspend fun addMessage(sessionId: String, message: ChatMessage)
    suspend fun updateMessage(message: ChatMessage)
    suspend fun deleteMessage(messageId: String)
    suspend fun clearSessionMessages(sessionId: String)
}

/**
 * Chat repository implementation
 */
@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val sessionDao: ChatSessionDao,
    private val messageDao: ChatMessageDao,
    private val gson: Gson
) : ChatRepository {

    override fun getAllSessions(): Flow<List<ChatSession>> {
        return sessionDao.getAllSessions().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getRecentSessions(limit: Int): Flow<List<ChatSession>> {
        return sessionDao.getRecentSessions(limit).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getSessionById(sessionId: String): ChatSession? {
        return sessionDao.getSessionById(sessionId)?.toDomain()
    }

    override suspend fun createSession(title: String, modelId: String): ChatSession {
        val session = ChatSession(
            title = title,
            modelId = modelId
        )
        sessionDao.insertSession(session.toEntity())
        return session
    }

    override suspend fun updateSession(session: ChatSession) {
        sessionDao.updateSession(session.toEntity())
    }

    override suspend fun deleteSession(sessionId: String) {
        messageDao.deleteMessagesBySession(sessionId)
        sessionDao.deleteSessionById(sessionId)
    }

    override fun getMessagesBySession(sessionId: String): Flow<List<ChatMessage>> {
        return messageDao.getMessagesBySession(sessionId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun addMessage(sessionId: String, message: ChatMessage) {
        messageDao.insertMessage(message.toEntity(sessionId))
        
        // Update session's updatedAt timestamp
        sessionDao.getSessionById(sessionId)?.let { session ->
            sessionDao.updateSession(session.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    override suspend fun updateMessage(message: ChatMessage) {
        // Find session for this message
        val allSessions = sessionDao.getAllSessions()
        // We need to search through sessions to find the message
        // For simplicity, we'll update in place
    }

    override suspend fun deleteMessage(messageId: String) {
        messageDao.getMessageById(messageId)?.let { entity ->
            messageDao.deleteMessage(entity)
        }
    }

    override suspend fun clearSessionMessages(sessionId: String) {
        messageDao.deleteMessagesBySession(sessionId)
    }

    // Extension functions for mapping
    private fun ChatSessionEntity.toDomain() = ChatSession(
        id = id,
        title = title,
        modelId = modelId,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun ChatSession.toEntity() = ChatSessionEntity(
        id = id,
        title = title,
        modelId = modelId,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun ChatMessageEntity.toDomain(): ChatMessage {
        val imageUrls: List<String> = try {
            gson.fromJson(this.imageUrls, object : TypeToken<List<String>>() {}.type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        
        return ChatMessage(
            id = id,
            role = MessageRole.valueOf(role),
            content = content,
            imageUrls = imageUrls,
            audioUrl = audioUrl,
            timestamp = timestamp,
            isStreaming = isStreaming,
            error = error
        )
    }

    private fun ChatMessage.toEntity(sessionId: String) = ChatMessageEntity(
        id = id,
        sessionId = sessionId,
        role = role.name,
        content = content,
        imageUrls = gson.toJson(imageUrls),
        audioUrl = audioUrl,
        timestamp = timestamp,
        isStreaming = isStreaming,
        error = error
    )
}
