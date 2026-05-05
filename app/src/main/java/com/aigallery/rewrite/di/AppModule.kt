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

    // Task DAOs
    @Provides
    fun provideTaskDao(database: AIGalleryDatabase): TaskDao {
        return database.taskDao()
    }

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): SettingsDataStore {
        return SettingsDataStore(context)
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
        gson: Gson
    ): MemoryRepository {
        return MemoryRepositoryImpl(
            memoryDao,
            workingMemoryDao,
            shortTermMemoryDao,
            longTermMemoryDao,
            knowledgeBaseMemoryDao,
            gson
        )
    }

    // ==================== 设备控制模块 - 爱马仕级全操控 ====================
    
    /**
     * 设备控制服务 - 提供系统服务控制能力
     * 包括WiFi、蓝牙、音量、亮度、手电筒等设备控制
     */
    @Provides
    @Singleton
    fun provideDeviceControlService(
        @ApplicationContext context: Context
    ): DeviceControlService {
        return DeviceControlService(context)
    }

    /**
     * 设备控制管理器 - 设备控制总入口
     * 单例模式，管理所有设备控制能力
     * 提供统一的API接口给UI层和LLM层调用
     */
    @Provides
    @Singleton
    fun provideDeviceControlManager(
        @ApplicationContext context: Context,
        deviceControlService: DeviceControlService
    ): DeviceControlManager {
        return DeviceControlManager.getInstance(context).also { manager ->
            // 初始化设备状态
            // 注意：这里不能直接调用设备状态获取，因为可能还未启用
        }
    }
}
