package com.aigallery.rewrite.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aigallery.rewrite.data.local.dao.*
import com.aigallery.rewrite.data.local.entity.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for AIGallery app
 */
@Database(
    entities = [
        // Chat entities
        ChatMessageEntity::class,
        ChatMessageFts::class,
        ChatSessionEntity::class,
        // Memory entities - only one MemoryFts needed for FTS search
        MemoryEntity::class,
        MemoryFts::class,
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
    version = 2,
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

        /**
         * P2: Migration from v1 to v2 - Add FTS5 virtual tables
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create FTS5 virtual table for chat messages
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS chat_messages_fts USING FTS5(`content`, content=`chat_messages`)")
                // Create FTS5 virtual table for memories
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING FTS5(`content`, `tags`, content=`memories`)")
                // Populate FTS tables with existing data
                db.execSQL("INSERT INTO chat_messages_fts(`content`) SELECT `content` FROM `chat_messages`")
                db.execSQL("INSERT INTO memories_fts(`content`, `tags`) SELECT `content`, `tags` FROM `memories`")
            }
        }
    }
}
