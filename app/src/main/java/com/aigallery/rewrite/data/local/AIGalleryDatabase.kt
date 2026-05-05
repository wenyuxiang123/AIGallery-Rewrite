package com.aigallery.rewrite.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aigallery.rewrite.data.local.dao.*
import com.aigallery.rewrite.data.local.entity.*

/**
 * Room database for AIGallery app
 */
@Database(
    entities = [
        // Chat entities
        ChatMessageEntity::class,
        ChatSessionEntity::class,
        // Memory entities
        MemoryEntity::class,
        WorkingMemoryEntity::class,
        ShortTermMemoryEntity::class,
        LongTermMemoryEntity::class,
        KnowledgeBaseMemoryEntity::class,
        PersonaMemoryEntity::class,
        // Task entities
        CustomSkillEntity::class,
        TaskTemplateEntity::class,
        TaskResultEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AIGalleryDatabase : RoomDatabase() {
    // Chat DAOs
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun chatMessageDao(): ChatMessageDao

    // Memory DAOs
    abstract fun memoryDao(): MemoryDao
    abstract fun workingMemoryDao(): WorkingMemoryDao
    abstract fun shortTermMemoryDao(): ShortTermMemoryDao
    abstract fun longTermMemoryDao(): LongTermMemoryDao
    abstract fun knowledgeBaseMemoryDao(): KnowledgeBaseMemoryDao
    abstract fun personaMemoryDao(): PersonaMemoryDao

    // Task DAOs
    abstract fun customSkillDao(): CustomSkillDao
    abstract fun taskTemplateDao(): TaskTemplateDao
    abstract fun taskResultDao(): TaskResultDao

    companion object {
        const val DATABASE_NAME = "aigallery_database"
    }
}
