package com.aigallery.rewrite.di

import android.content.Context
import androidx.room.Room
import com.aigallery.rewrite.data.local.AIGalleryDatabase
import com.aigallery.rewrite.data.local.dao.*
import com.aigallery.rewrite.data.local.datastore.SettingsDataStore
import com.aigallery.rewrite.data.repository.ChatRepository
import com.aigallery.rewrite.data.repository.ChatRepositoryImpl
import com.aigallery.rewrite.data.repository.MemoryRepository
import com.aigallery.rewrite.data.repository.MemoryRepositoryImpl
import com.aigallery.rewrite.devicecontrol.DeviceControlManager
import com.aigallery.rewrite.devicecontrol.DeviceControlService
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder()
        .setLenient()
        .create()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AIGalleryDatabase {
        return Room.databaseBuilder(
            context,
            AIGalleryDatabase::class.java,
            AIGalleryDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    // Chat DAOs
    @Provides
    fun provideChatSessionDao(database: AIGalleryDatabase): ChatSessionDao {
        return database.chatSessionDao()
    }

    @Provides
    fun provideChatMessageDao(database: AIGalleryDatabase): ChatMessageDao {
        return database.chatMessageDao()
    }

    // Memory DAOs
    @Provides
    fun provideMemoryDao(database: AIGalleryDatabase): MemoryDao {
        return database.memoryDao()
    }

    @Provides
    fun provideWorkingMemoryDao(database: AIGalleryDatabase): WorkingMemoryDao {
        return database.workingMemoryDao()
    }

    @Provides
    fun provideShortTermMemoryDao(database: AIGalleryDatabase): ShortTermMemoryDao {
        return database.shortTermMemoryDao()
    }

    @Provides
    fun provideLongTermMemoryDao(database: AIGalleryDatabase): LongTermMemoryDao {
        return database.longTermMemoryDao()
    }

    @Provides
    fun provideKnowledgeBaseMemoryDao(database: AIGalleryDatabase): KnowledgeBaseMemoryDao {
        return database.knowledgeBaseMemoryDao()
    }

    @Provides
    fun providePersonaMemoryDao(database: AIGalleryDatabase): PersonaMemoryDao {
        return database.personaMemoryDao()
    }

    // Task DAOs
    @Provides
    fun provideCustomSkillDao(database: AIGalleryDatabase): CustomSkillDao {
        return database.customSkillDao()
    }

    @Provides
    fun provideTaskTemplateDao(database: AIGalleryDatabase): TaskTemplateDao {
        return database.taskTemplateDao()
    }

    @Provides
    fun provideTaskResultDao(database: AIGalleryDatabase): TaskResultDao {
        return database.taskResultDao()
    }

    // Repositories
    @Provides
    @Singleton
    fun provideChatRepository(
        chatSessionDao: ChatSessionDao,
        chatMessageDao: ChatMessageDao,
        gson: Gson
    ): ChatRepository {
        return ChatRepositoryImpl(chatSessionDao, chatMessageDao, gson)
    }

    @Provides
    @Singleton
    fun provideMemoryRepository(
        memoryDao: MemoryDao,
        workingMemoryDao: WorkingMemoryDao,
        shortTermMemoryDao: ShortTermMemoryDao,
        longTermMemoryDao: LongTermMemoryDao,
        knowledgeBaseMemoryDao: KnowledgeBaseMemoryDao,
        personaMemoryDao: PersonaMemoryDao
    ): MemoryRepository {
        return MemoryRepositoryImpl(
            memoryDao,
            workingMemoryDao,
            shortTermMemoryDao,
            longTermMemoryDao,
            knowledgeBaseMemoryDao,
            personaMemoryDao
        )
    }

    // Settings
    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): SettingsDataStore {
        return SettingsDataStore(context)
    }

    // Device Control
    @Provides
    @Singleton
    fun provideDeviceControlManager(@ApplicationContext context: Context): DeviceControlManager {
        return DeviceControlManager(context)
    }

    @Provides
    @Singleton
    fun provideDeviceControlService(deviceControlManager: DeviceControlManager): DeviceControlService {
        return DeviceControlService(deviceControlManager)
    }
}
