package com.aigallery.rewrite.data.local.dao

import androidx.room.*
import com.aigallery.rewrite.data.local.entity.*
import kotlinx.coroutines.flow.Flow

/**
 * Generic memory DAO for base operations
 */
@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories WHERE isEnabled = 1 ORDER BY updatedAt DESC")
    fun getAllEnabledMemories(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE layer = :layer AND isEnabled = 1 ORDER BY updatedAt DESC")
    fun getMemoriesByLayer(layer: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun getMemoryById(id: String): MemoryEntity?

    @Query("SELECT * FROM memories WHERE content LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%'")
    suspend fun searchMemories(query: String): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity)

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Delete
    suspend fun deleteMemory(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemoryById(id: String)

    @Query("DELETE FROM memories WHERE layer = :layer")
    suspend fun deleteMemoriesByLayer(layer: String)

    @Query("DELETE FROM memories")
    suspend fun deleteAllMemories()


    /**
     * P2: FTS4 full-text search across memories
     * Replaces LIKE-based search with proper full-text search
     */
    @Query("""
        SELECT m.* FROM memories m 
        INNER JOIN memories_fts fts ON m.rowid = fts.rowid 
        WHERE fts.content MATCH :query OR fts.tags MATCH :query
        ORDER BY m.createdAt DESC
        LIMIT :limit
    """)
    suspend fun searchMemoriesFts(query: String, limit: Int = 20): List<MemoryEntity>

}

/**
 * Working memory DAO
 */
@Dao
interface WorkingMemoryDao {
    @Query("SELECT * FROM working_memories WHERE sessionId = :sessionId AND isEnabled = 1")
    fun getWorkingMemories(sessionId: String): Flow<List<WorkingMemoryEntity>>

    @Query("SELECT * FROM working_memories WHERE isEnabled = 1")
    fun getAllEnabledWorkingMemories(): Flow<List<WorkingMemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: WorkingMemoryEntity)

    @Update
    suspend fun update(memory: WorkingMemoryEntity)

    @Query("DELETE FROM working_memories WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM working_memories")
    suspend fun deleteAll()
}

/**
 * Short-term memory DAO
 */
@Dao
interface ShortTermMemoryDao {
    @Query("SELECT * FROM short_term_memories WHERE isEnabled = 1 ORDER BY createdAt DESC")
    fun getAllEnabledShortTermMemories(): Flow<List<ShortTermMemoryEntity>>

    @Query("SELECT * FROM short_term_memories WHERE isEnabled = 1 ORDER BY importance DESC, createdAt DESC LIMIT :limit")
    suspend fun getTopMemories(limit: Int): List<ShortTermMemoryEntity>

    @Query("SELECT * FROM short_term_memories WHERE isEnabled = 1 ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentMemories(limit: Int): List<ShortTermMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: ShortTermMemoryEntity)

    @Update
    suspend fun update(memory: ShortTermMemoryEntity)

    @Delete
    suspend fun delete(memory: ShortTermMemoryEntity)

    @Query("DELETE FROM short_term_memories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM short_term_memories WHERE isEnabled = 0")
    suspend fun deleteDisabled()

    @Query("SELECT COUNT(*) FROM short_term_memories")
    suspend fun getCount(): Int

    @Query("DELETE FROM short_term_memories WHERE id IN (SELECT id FROM short_term_memories ORDER BY createdAt ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)
}

/**
 * Long-term memory DAO
 */
@Dao
interface LongTermMemoryDao {
    @Query("SELECT * FROM long_term_memories WHERE isEnabled = 1 ORDER BY lastAccessedAt DESC")
    fun getAllEnabledLongTermMemories(): Flow<List<LongTermMemoryEntity>>

    @Query("SELECT * FROM long_term_memories WHERE isEnabled = 1 ORDER BY accessCount DESC LIMIT :limit")
    suspend fun getMostAccessedMemories(limit: Int): List<LongTermMemoryEntity>

    @Query("SELECT * FROM long_term_memories WHERE isEnabled = 1 AND tags LIKE '%' || :tag || '%'")
    suspend fun getMemoriesByTag(tag: String): List<LongTermMemoryEntity>

    @Query("SELECT * FROM long_term_memories WHERE content LIKE '%' || :query || '%'")
    suspend fun searchMemories(query: String): List<LongTermMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: LongTermMemoryEntity)

    @Update
    suspend fun update(memory: LongTermMemoryEntity)

    @Query("UPDATE long_term_memories SET accessCount = accessCount + 1, lastAccessedAt = :timestamp WHERE id = :id")
    suspend fun incrementAccess(id: String, timestamp: Long)

    @Delete
    suspend fun delete(memory: LongTermMemoryEntity)

    @Query("DELETE FROM long_term_memories WHERE id = :id")
    suspend fun deleteById(id: String)
}

/**
 * Knowledge base memory DAO
 */
@Dao
interface KnowledgeBaseMemoryDao {
    @Query("SELECT * FROM knowledge_base_memories WHERE isEnabled = 1 ORDER BY createdAt DESC")
    fun getAllEnabledKnowledgeBaseMemories(): Flow<List<KnowledgeBaseMemoryEntity>>

    @Query("SELECT * FROM knowledge_base_memories WHERE isEnabled = 1 AND title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%'")
    suspend fun searchKnowledgeBase(query: String): List<KnowledgeBaseMemoryEntity>

    @Query("SELECT * FROM knowledge_base_memories WHERE sourceType = :sourceType AND isEnabled = 1")
    fun getBySourceType(sourceType: String): Flow<List<KnowledgeBaseMemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: KnowledgeBaseMemoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(memories: List<KnowledgeBaseMemoryEntity>)

    @Update
    suspend fun update(memory: KnowledgeBaseMemoryEntity)

    @Delete
    suspend fun delete(memory: KnowledgeBaseMemoryEntity)

    @Query("DELETE FROM knowledge_base_memories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM knowledge_base_memories WHERE filePath = :filePath")
    suspend fun deleteByFilePath(filePath: String)
}

/**
 * Persona memory DAO
 */
@Dao
interface PersonaMemoryDao {
    @Query("SELECT * FROM persona_memories WHERE isEnabled = 1 ORDER BY createdAt DESC")
    fun getAllEnabledPersonaMemories(): Flow<List<PersonaMemoryEntity>>

    @Query("SELECT * FROM persona_memories WHERE personaType = :type AND isEnabled = 1")
    fun getByPersonaType(type: String): Flow<List<PersonaMemoryEntity>>

    @Query("SELECT * FROM persona_memories WHERE isEnabled = 1")
    suspend fun getAllEnabledSync(): List<PersonaMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: PersonaMemoryEntity)

    @Update
    suspend fun update(memory: PersonaMemoryEntity)

    @Delete
    suspend fun delete(memory: PersonaMemoryEntity)

    @Query("DELETE FROM persona_memories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM persona_memories WHERE personaType = :type")
    suspend fun deleteByType(type: String)
}
