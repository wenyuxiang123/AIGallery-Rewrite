package com.aigallery.rewrite.data.local.dao

import androidx.room.*
import com.aigallery.rewrite.data.local.entity.ChatMessageEntity
import com.aigallery.rewrite.data.local.entity.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Chat session DAO
 */
@Dao
interface ChatSessionDao {
    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC")
    fun getAllSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: String): ChatSessionEntity?

    @Query("SELECT * FROM chat_sessions ORDER BY updatedAt DESC LIMIT :limit")
    fun getRecentSessions(limit: Int): Flow<List<ChatSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity)

    @Update
    suspend fun updateSession(session: ChatSessionEntity)

    @Delete
    suspend fun deleteSession(session: ChatSessionEntity)

    @Query("DELETE FROM chat_sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: String)

    @Query("DELETE FROM chat_sessions")
    suspend fun deleteAllSessions()

    @Query("SELECT COUNT(*) FROM chat_sessions")
    suspend fun getSessionCount(): Int

    @Query("DELETE FROM chat_sessions WHERE id IN (SELECT id FROM chat_sessions ORDER BY updatedAt ASC LIMIT :count)")
    suspend fun deleteOldSessions(count: Int)
}

/**
 * Chat message DAO
 */
@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesBySession(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesBySessionSync(sessionId: String): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): ChatMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ChatMessageEntity>)

    @Update
    suspend fun updateMessage(message: ChatMessageEntity)

    @Delete
    suspend fun deleteMessage(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesBySession(sessionId: String)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId AND role = 'USER' AND timestamp < :beforeTimestamp")
    suspend fun deleteOldUserMessages(sessionId: String, beforeTimestamp: Long)

    @Query("SELECT COUNT(*) FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun getMessageCount(sessionId: String): Int

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(sessionId: String, limit: Int): List<ChatMessageEntity>


    /**
     * P2: FTS5 full-text search across chat messages
     * Searches message content using FTS5 MATCH instead of LIKE
     */
    @Query("""
        SELECT m.* FROM chat_messages m 
        INNER JOIN chat_messages_fts fts ON m.rowid = fts.rowid 
        WHERE fts.content MATCH :query AND m.sessionId = :sessionId
        ORDER BY m.timestamp DESC 
        LIMIT :limit
    """)
    suspend fun searchMessagesBySession(sessionId: String, query: String, limit: Int = 20): List<ChatMessageEntity>

    /**
     * P2: FTS5 search across all sessions
     */
    @Query("""
        SELECT m.* FROM chat_messages m 
        INNER JOIN chat_messages_fts fts ON m.rowid = fts.rowid 
        WHERE fts.content MATCH :query
        ORDER BY m.timestamp DESC 
        LIMIT :limit
    """)
    suspend fun searchAllMessages(query: String, limit: Int = 50): List<ChatMessageEntity>

}
