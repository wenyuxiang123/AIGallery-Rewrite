package com.aigallery.rewrite.data.repository

import com.aigallery.rewrite.data.local.dao.*
import com.aigallery.rewrite.data.local.entity.*
import com.aigallery.rewrite.domain.model.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Five-layer memory repository
 */
interface MemoryRepository {
    // Working memory operations
    fun getWorkingMemories(sessionId: String): Flow<List<WorkingMemory>>
    suspend fun addWorkingMemory(sessionId: String, content: String): WorkingMemory
    suspend fun clearWorkingMemories(sessionId: String)
    
    // Short-term memory operations
    fun getShortTermMemories(): Flow<List<ShortTermMemory>>
    suspend fun getRecentShortTermMemories(limit: Int): List<ShortTermMemory>
    suspend fun addShortTermMemory(content: String, importance: Float = 0.5f): ShortTermMemory
    suspend fun updateShortTermMemory(memory: ShortTermMemory)
    suspend fun deleteShortTermMemory(id: String)
    suspend fun pruneShortTermMemories(keepCount: Int)
    
    // Long-term memory operations
    fun getLongTermMemories(): Flow<List<LongTermMemory>>
    suspend fun searchLongTermMemories(query: String): List<LongTermMemory>
    suspend fun addLongTermMemory(content: String, tags: List<String> = emptyList()): LongTermMemory
    suspend fun updateLongTermMemory(memory: LongTermMemory)
    suspend fun deleteLongTermMemory(id: String)
    
    // Knowledge base operations
    fun getKnowledgeBaseMemories(): Flow<List<KnowledgeBaseMemory>>
    suspend fun searchKnowledgeBase(query: String): List<KnowledgeBaseMemory>
    suspend fun addKnowledgeBaseMemory(
        title: String,
        content: String,
        sourceType: KnowledgeSourceType,
        sourceUrl: String? = null,
        filePath: String? = null
    ): KnowledgeBaseMemory
    suspend fun deleteKnowledgeBaseMemory(id: String)
    
    // Persona memory operations
    fun getPersonaMemories(): Flow<List<PersonaMemory>>
    suspend fun getPersonaMemoriesSync(): List<PersonaMemory>
    suspend fun addPersonaMemory(
        content: String,
        personaType: PersonaType,
        personalityTraits: Map<String, Float> = emptyMap()
    ): PersonaMemory
    suspend fun updatePersonaMemory(memory: PersonaMemory)
    suspend fun deletePersonaMemory(id: String)
    
    // Unified retrieval - get all relevant memories for context
    suspend fun retrieveAllRelevantMemories(context: String, config: MemoryConfig, sessionId: String? = null): List<MemoryItem>
    
    // Clear all memories
    suspend fun clearAllMemories()
}

@Singleton
class MemoryRepositoryImpl @Inject constructor(
    private val workingMemoryDao: WorkingMemoryDao,
    private val shortTermMemoryDao: ShortTermMemoryDao,
    private val longTermMemoryDao: LongTermMemoryDao,
    private val knowledgeBaseMemoryDao: KnowledgeBaseMemoryDao,
    private val personaMemoryDao: PersonaMemoryDao,
    private val gson: Gson
) : MemoryRepository {

    // Working memory
    override fun getWorkingMemories(sessionId: String): Flow<List<WorkingMemory>> {
        return workingMemoryDao.getWorkingMemories(sessionId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun addWorkingMemory(sessionId: String, content: String): WorkingMemory {
        val memory = WorkingMemory(sessionId = sessionId, content = content)
        workingMemoryDao.insert(memory.toEntity())
        return memory
    }

    override suspend fun clearWorkingMemories(sessionId: String) {
        workingMemoryDao.deleteBySession(sessionId)
    }

    // Short-term memory
    override fun getShortTermMemories(): Flow<List<ShortTermMemory>> {
        return shortTermMemoryDao.getAllEnabledShortTermMemories().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getRecentShortTermMemories(limit: Int): List<ShortTermMemory> {
        return shortTermMemoryDao.getRecentMemories(limit).map { it.toDomain() }
    }

    override suspend fun addShortTermMemory(content: String, importance: Float): ShortTermMemory {
        val memory = ShortTermMemory(content = content, importance = importance)
        shortTermMemoryDao.insert(memory.toEntity())
        return memory
    }

    override suspend fun updateShortTermMemory(memory: ShortTermMemory) {
        shortTermMemoryDao.update(memory.toEntity())
    }

    override suspend fun deleteShortTermMemory(id: String) {
        shortTermMemoryDao.deleteById(id)
    }

    override suspend fun pruneShortTermMemories(keepCount: Int) {
        val count = shortTermMemoryDao.getCount()
        if (count > keepCount) {
            shortTermMemoryDao.deleteOldest(count - keepCount)
        }
    }

    // Long-term memory
    override fun getLongTermMemories(): Flow<List<LongTermMemory>> {
        return longTermMemoryDao.getAllEnabledLongTermMemories().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun searchLongTermMemories(query: String): List<LongTermMemory> {
        return longTermMemoryDao.searchMemories(query).map { it.toDomain() }
    }

    override suspend fun addLongTermMemory(content: String, tags: List<String>): LongTermMemory {
        val memory = LongTermMemory(content = content, tags = tags)
        longTermMemoryDao.insert(memory.toEntity())
        return memory
    }

    override suspend fun updateLongTermMemory(memory: LongTermMemory) {
        longTermMemoryDao.update(memory.toEntity())
    }

    override suspend fun deleteLongTermMemory(id: String) {
        longTermMemoryDao.deleteById(id)
    }

    // Knowledge base
    override fun getKnowledgeBaseMemories(): Flow<List<KnowledgeBaseMemory>> {
        return knowledgeBaseMemoryDao.getAllEnabledKnowledgeBaseMemories().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun searchKnowledgeBase(query: String): List<KnowledgeBaseMemory> {
        return knowledgeBaseMemoryDao.searchKnowledgeBase(query).map { it.toDomain() }
    }

    override suspend fun addKnowledgeBaseMemory(
        title: String,
        content: String,
        sourceType: KnowledgeSourceType,
        sourceUrl: String?,
        filePath: String?
    ): KnowledgeBaseMemory {
        val memory = KnowledgeBaseMemory(
            title = title,
            content = content,
            sourceType = sourceType,
            sourceUrl = sourceUrl,
            filePath = filePath
        )
        knowledgeBaseMemoryDao.insert(memory.toEntity())
        return memory
    }

    override suspend fun deleteKnowledgeBaseMemory(id: String) {
        knowledgeBaseMemoryDao.deleteById(id)
    }

    // Persona memory
    override fun getPersonaMemories(): Flow<List<PersonaMemory>> {
        return personaMemoryDao.getAllEnabledPersonaMemories().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getPersonaMemoriesSync(): List<PersonaMemory> {
        return personaMemoryDao.getAllEnabledSync().map { it.toDomain() }
    }

    override suspend fun addPersonaMemory(
        content: String,
        personaType: PersonaType,
        personalityTraits: Map<String, Float>
    ): PersonaMemory {
        val memory = PersonaMemory(
            content = content,
            personaType = personaType,
            personalityTraits = personalityTraits
        )
        personaMemoryDao.insert(memory.toEntity())
        return memory
    }

    override suspend fun updatePersonaMemory(memory: PersonaMemory) {
        personaMemoryDao.update(memory.toEntity())
    }

    override suspend fun deletePersonaMemory(id: String) {
        personaMemoryDao.deleteById(id)
    }

    // Unified retrieval
    override suspend fun retrieveAllRelevantMemories(context: String, config: MemoryConfig, sessionId: String?): List<MemoryItem> {
        val result = mutableListOf<MemoryItem>()
        
        // Get working memories from current session (most immediate context)
        if (sessionId != null) {
            try {
                val working = workingMemoryDao.getWorkingMemories(sessionId).first()
                result.addAll(working.map { it.toDomain() })
            } catch (e: Exception) { /* working memory retrieval is optional */ }
        }
        
        // Get short-term memories (recent N turns)
        if (config.shortTermMemoryEnabled) {
            val shortTerm = shortTermMemoryDao.getRecentMemories(config.shortTermWindowSize)
            result.addAll(shortTerm.map { it.toDomain() })
        }
        
        // Get long-term memories (semantic search)
        if (config.longTermMemoryEnabled) {
            val longTerm = longTermMemoryDao.searchMemories(context)
                .take(config.longTermRetrievalLimit)
            result.addAll(longTerm.map { it.toDomain() })
        }
        
        // Get knowledge base memories
        if (config.knowledgeBaseEnabled) {
            val knowledge = knowledgeBaseMemoryDao.searchKnowledgeBase(context)
            result.addAll(knowledge.map { it.toDomain() })
        }
        
        // Get persona memories (always included)
        if (config.personaMemoryEnabled) {
            val personas = personaMemoryDao.getAllEnabledSync()
            result.addAll(personas.map { it.toDomain() })
        }
        
        return result
    }

    override suspend fun clearAllMemories() {
        workingMemoryDao.deleteAll()
        shortTermMemoryDao.deleteDisabled()
        // Don't delete long-term, knowledge base, persona - they are user data
    }

    // Mapping functions
    private fun WorkingMemoryEntity.toDomain() = WorkingMemory(
        id = id,
        content = content,
        sessionId = sessionId,
        isEnabled = isEnabled,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun WorkingMemory.toEntity() = WorkingMemoryEntity(
        id = id,
        content = content,
        sessionId = sessionId,
        isEnabled = isEnabled,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun ShortTermMemoryEntity.toDomain() = ShortTermMemory(
        id = id,
        content = content,
        importance = importance,
        turnCount = turnCount,
        isEnabled = isEnabled,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun ShortTermMemory.toEntity() = ShortTermMemoryEntity(
        id = id,
        content = content,
        summary = summary,
        importance = importance,
        turnCount = turnCount,
        isEnabled = isEnabled,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun LongTermMemoryEntity.toDomain(): LongTermMemory {
        val tags: List<String> = try {
            gson.fromJson(this.tags, object : TypeToken<List<String>>() {}.type) ?: emptyList()
        } catch (e: Exception) { emptyList() }
        return LongTermMemory(
            id = id,
            content = content,
            tags = tags,
            embedding = embedding?.let {
                try {
                    gson.fromJson(it, object : TypeToken<List<Float>>() {}.type)
                } catch (e: Exception) { null }
            },
            sourceConversationId = sourceConversationId,
            accessCount = accessCount,
            lastAccessedAt = lastAccessedAt,
            isEnabled = isEnabled,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun LongTermMemory.toEntity() = LongTermMemoryEntity(
        id = id,
        content = content,
        summary = summary,
        tags = gson.toJson(tags),
        embedding = embedding?.let { gson.toJson(it) },
        sourceConversationId = sourceConversationId,
        accessCount = accessCount,
        lastAccessedAt = lastAccessedAt,
        isEnabled = isEnabled,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun KnowledgeBaseMemoryEntity.toDomain() = KnowledgeBaseMemory(
        id = id,
        title = title,
        content = content,
        sourceType = KnowledgeSourceType.valueOf(sourceType),
        sourceUrl = sourceUrl,
        filePath = filePath,
        chunkIndex = chunkIndex,
        totalChunks = totalChunks,
        tags = try {
            gson.fromJson(tags, object : TypeToken<List<String>>() {}.type) ?: emptyList()
        } catch (e: Exception) { emptyList() },
        isEnabled = isEnabled,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun KnowledgeBaseMemory.toEntity() = KnowledgeBaseMemoryEntity(
        id = id,
        title = title,
        content = content,
        sourceType = sourceType.name,
        sourceUrl = sourceUrl,
        filePath = filePath,
        chunkIndex = chunkIndex,
        totalChunks = totalChunks,
        tags = gson.toJson(tags),
        isEnabled = isEnabled,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun PersonaMemoryEntity.toDomain(): PersonaMemory {
        val traits: Map<String, Float> = try {
            gson.fromJson(personalityTraits, object : TypeToken<Map<String, Float>>() {}.type) ?: emptyMap()
        } catch (e: Exception) { emptyMap() }
        return PersonaMemory(
            id = id,
            content = content,
            personaType = PersonaType.valueOf(personaType),
            personalityTraits = traits,
            tags = try {
                gson.fromJson(tags, object : TypeToken<List<String>>() {}.type) ?: emptyList()
            } catch (e: Exception) { emptyList() },
            isEnabled = isEnabled,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun PersonaMemory.toEntity() = PersonaMemoryEntity(
        id = id,
        content = content,
        personaType = personaType.name,
        personalityTraits = gson.toJson(personalityTraits),
        tags = gson.toJson(tags),
        isEnabled = isEnabled,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
