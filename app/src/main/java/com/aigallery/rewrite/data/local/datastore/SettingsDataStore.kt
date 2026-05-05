package com.aigallery.rewrite.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.aigallery.rewrite.domain.model.AppSettings
import com.aigallery.rewrite.domain.model.MemoryConfig
import com.aigallery.rewrite.domain.model.MemoryFusionStrategy
import com.aigallery.rewrite.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Settings DataStore manager
 */
@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object PreferencesKeys {
        val THEME = stringPreferencesKey("theme")
        val LANGUAGE = stringPreferencesKey("language")
        val DEFAULT_MODEL_ID = stringPreferencesKey("default_model_id")
        val ENABLE_MEMORY_SYSTEM = booleanPreferencesKey("enable_memory_system")
        
        // Memory config
        val WORKING_MEMORY_ENABLED = booleanPreferencesKey("working_memory_enabled")
        val SHORT_TERM_MEMORY_ENABLED = booleanPreferencesKey("short_term_memory_enabled")
        val SHORT_TERM_WINDOW_SIZE = intPreferencesKey("short_term_window_size")
        val LONG_TERM_MEMORY_ENABLED = booleanPreferencesKey("long_term_memory_enabled")
        val LONG_TERM_RETRIEVAL_LIMIT = intPreferencesKey("long_term_retrieval_limit")
        val KNOWLEDGE_BASE_ENABLED = booleanPreferencesKey("knowledge_base_enabled")
        val PERSONA_MEMORY_ENABLED = booleanPreferencesKey("persona_memory_enabled")
        val MEMORY_FUSION_STRATEGY = stringPreferencesKey("memory_fusion_strategy")
        
        // App behavior
        val AUTO_DOWNLOAD_ON_WIFI = booleanPreferencesKey("auto_download_on_wifi")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val STREAMING_ENABLED = booleanPreferencesKey("streaming_enabled")
        val MAX_HISTORY_SESSIONS = intPreferencesKey("max_history_sessions")
        val CLEAR_CACHE_ON_EXIT = booleanPreferencesKey("clear_cache_on_exit")
        
        // First launch
        val IS_FIRST_LAUNCH = booleanPreferencesKey("is_first_launch")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }

    val settingsFlow: Flow<AppSettings> = context.settingsDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            AppSettings(
                theme = preferences[PreferencesKeys.THEME]?.let { ThemeMode.valueOf(it) } ?: ThemeMode.SYSTEM,
                language = preferences[PreferencesKeys.LANGUAGE] ?: "zh-CN",
                defaultModelId = preferences[PreferencesKeys.DEFAULT_MODEL_ID],
                enableMemorySystem = preferences[PreferencesKeys.ENABLE_MEMORY_SYSTEM] ?: true,
                memoryConfig = MemoryConfig(
                    workingMemoryEnabled = preferences[PreferencesKeys.WORKING_MEMORY_ENABLED] ?: true,
                    shortTermMemoryEnabled = preferences[PreferencesKeys.SHORT_TERM_MEMORY_ENABLED] ?: true,
                    shortTermWindowSize = preferences[PreferencesKeys.SHORT_TERM_WINDOW_SIZE] ?: 10,
                    longTermMemoryEnabled = preferences[PreferencesKeys.LONG_TERM_MEMORY_ENABLED] ?: true,
                    longTermRetrievalLimit = preferences[PreferencesKeys.LONG_TERM_RETRIEVAL_LIMIT] ?: 5,
                    knowledgeBaseEnabled = preferences[PreferencesKeys.KNOWLEDGE_BASE_ENABLED] ?: true,
                    personaMemoryEnabled = preferences[PreferencesKeys.PERSONA_MEMORY_ENABLED] ?: true,
                    memoryFusionStrategy = preferences[PreferencesKeys.MEMORY_FUSION_STRATEGY]?.let { 
                        MemoryFusionStrategy.valueOf(it) 
                    } ?: MemoryFusionStrategy.UNIFIED
                ),
                autoDownloadOnWifi = preferences[PreferencesKeys.AUTO_DOWNLOAD_ON_WIFI] ?: true,
                notificationsEnabled = preferences[PreferencesKeys.NOTIFICATIONS_ENABLED] ?: true,
                streamingEnabled = preferences[PreferencesKeys.STREAMING_ENABLED] ?: true,
                maxHistorySessions = preferences[PreferencesKeys.MAX_HISTORY_SESSIONS] ?: 50,
                clearCacheOnExit = preferences[PreferencesKeys.CLEAR_CACHE_ON_EXIT] ?: false
            )
        }

    val isFirstLaunch: Flow<Boolean> = context.settingsDataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.IS_FIRST_LAUNCH] ?: true
        }

    val onboardingCompleted: Flow<Boolean> = context.settingsDataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.ONBOARDING_COMPLETED] ?: false
        }

    suspend fun updateSettings(settings: AppSettings) {
        context.settingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.THEME] = settings.theme.name
            preferences[PreferencesKeys.LANGUAGE] = settings.language
            settings.defaultModelId?.let { preferences[PreferencesKeys.DEFAULT_MODEL_ID] = it }
            preferences[PreferencesKeys.ENABLE_MEMORY_SYSTEM] = settings.enableMemorySystem
            
            // Memory config
            preferences[PreferencesKeys.WORKING_MEMORY_ENABLED] = settings.memoryConfig.workingMemoryEnabled
            preferences[PreferencesKeys.SHORT_TERM_MEMORY_ENABLED] = settings.memoryConfig.shortTermMemoryEnabled
            preferences[PreferencesKeys.SHORT_TERM_WINDOW_SIZE] = settings.memoryConfig.shortTermWindowSize
            preferences[PreferencesKeys.LONG_TERM_MEMORY_ENABLED] = settings.memoryConfig.longTermMemoryEnabled
            preferences[PreferencesKeys.LONG_TERM_RETRIEVAL_LIMIT] = settings.memoryConfig.longTermRetrievalLimit
            preferences[PreferencesKeys.KNOWLEDGE_BASE_ENABLED] = settings.memoryConfig.knowledgeBaseEnabled
            preferences[PreferencesKeys.PERSONA_MEMORY_ENABLED] = settings.memoryConfig.personaMemoryEnabled
            preferences[PreferencesKeys.MEMORY_FUSION_STRATEGY] = settings.memoryConfig.memoryFusionStrategy.name
            
            // App behavior
            preferences[PreferencesKeys.AUTO_DOWNLOAD_ON_WIFI] = settings.autoDownloadOnWifi
            preferences[PreferencesKeys.NOTIFICATIONS_ENABLED] = settings.notificationsEnabled
            preferences[PreferencesKeys.STREAMING_ENABLED] = settings.streamingEnabled
            preferences[PreferencesKeys.MAX_HISTORY_SESSIONS] = settings.maxHistorySessions
            preferences[PreferencesKeys.CLEAR_CACHE_ON_EXIT] = settings.clearCacheOnExit
        }
    }

    suspend fun updateTheme(theme: ThemeMode) {
        context.settingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.THEME] = theme.name
        }
    }

    suspend fun updateDefaultModel(modelId: String?) {
        context.settingsDataStore.edit { preferences ->
            if (modelId != null) {
                preferences[PreferencesKeys.DEFAULT_MODEL_ID] = modelId
            } else {
                preferences.remove(PreferencesKeys.DEFAULT_MODEL_ID)
            }
        }
    }

    suspend fun updateMemoryConfig(config: MemoryConfig) {
        context.settingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.WORKING_MEMORY_ENABLED] = config.workingMemoryEnabled
            preferences[PreferencesKeys.SHORT_TERM_MEMORY_ENABLED] = config.shortTermMemoryEnabled
            preferences[PreferencesKeys.SHORT_TERM_WINDOW_SIZE] = config.shortTermWindowSize
            preferences[PreferencesKeys.LONG_TERM_MEMORY_ENABLED] = config.longTermMemoryEnabled
            preferences[PreferencesKeys.LONG_TERM_RETRIEVAL_LIMIT] = config.longTermRetrievalLimit
            preferences[PreferencesKeys.KNOWLEDGE_BASE_ENABLED] = config.knowledgeBaseEnabled
            preferences[PreferencesKeys.PERSONA_MEMORY_ENABLED] = config.personaMemoryEnabled
            preferences[PreferencesKeys.MEMORY_FUSION_STRATEGY] = config.memoryFusionStrategy.name
        }
    }

    suspend fun setFirstLaunch(isFirst: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_FIRST_LAUNCH] = isFirst
        }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[PreferencesKeys.ONBOARDING_COMPLETED] = completed
        }
    }

    suspend fun clearSettings() {
        context.settingsDataStore.edit { it.clear() }
    }
}
