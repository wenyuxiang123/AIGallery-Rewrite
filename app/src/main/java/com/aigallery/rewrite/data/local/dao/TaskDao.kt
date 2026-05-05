package com.aigallery.rewrite.data.local.dao

import androidx.room.*
import com.aigallery.rewrite.data.local.entity.CustomSkillEntity
import com.aigallery.rewrite.data.local.entity.TaskResultEntity
import com.aigallery.rewrite.data.local.entity.TaskTemplateEntity
import kotlinx.coroutines.flow.Flow

/**
 * Custom skill DAO
 */
@Dao
interface CustomSkillDao {
    @Query("SELECT * FROM custom_skills ORDER BY createdAt DESC")
    fun getAllSkills(): Flow<List<CustomSkillEntity>>

    @Query("SELECT * FROM custom_skills WHERE isEnabled = 1 ORDER BY usageCount DESC")
    fun getEnabledSkills(): Flow<List<CustomSkillEntity>>

    @Query("SELECT * FROM custom_skills WHERE type = :type AND isEnabled = 1")
    fun getSkillsByType(type: String): Flow<List<CustomSkillEntity>>

    @Query("SELECT * FROM custom_skills WHERE id = :id")
    suspend fun getSkillById(id: String): CustomSkillEntity?

    @Query("SELECT * FROM custom_skills WHERE isBuiltIn = 1")
    fun getBuiltInSkills(): Flow<List<CustomSkillEntity>>

    @Query("SELECT * FROM custom_skills WHERE isBuiltIn = 0")
    fun getUserCreatedSkills(): Flow<List<CustomSkillEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(skill: CustomSkillEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(skills: List<CustomSkillEntity>)

    @Update
    suspend fun update(skill: CustomSkillEntity)

    @Query("UPDATE custom_skills SET usageCount = usageCount + 1 WHERE id = :id")
    suspend fun incrementUsage(id: String)

    @Delete
    suspend fun delete(skill: CustomSkillEntity)

    @Query("DELETE FROM custom_skills WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE custom_skills SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)
}

/**
 * Task template DAO
 */
@Dao
interface TaskTemplateDao {
    @Query("SELECT * FROM task_templates ORDER BY usageCount DESC")
    fun getAllTemplates(): Flow<List<TaskTemplateEntity>>

    @Query("SELECT * FROM task_templates WHERE type = :type")
    fun getTemplatesByType(type: String): Flow<List<TaskTemplateEntity>>

    @Query("SELECT * FROM task_templates WHERE isFavorite = 1")
    fun getFavoriteTemplates(): Flow<List<TaskTemplateEntity>>

    @Query("SELECT * FROM task_templates WHERE id = :id")
    suspend fun getTemplateById(id: String): TaskTemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(template: TaskTemplateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(templates: List<TaskTemplateEntity>)

    @Update
    suspend fun update(template: TaskTemplateEntity)

    @Query("UPDATE task_templates SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE task_templates SET usageCount = usageCount + 1 WHERE id = :id")
    suspend fun incrementUsage(id: String)

    @Delete
    suspend fun delete(template: TaskTemplateEntity)
}

/**
 * Task result DAO
 */
@Dao
interface TaskResultDao {
    @Query("SELECT * FROM task_results ORDER BY timestamp DESC")
    fun getAllResults(): Flow<List<TaskResultEntity>>

    @Query("SELECT * FROM task_results ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentResults(limit: Int): Flow<List<TaskResultEntity>>

    @Query("SELECT * FROM task_results WHERE taskType = :type ORDER BY timestamp DESC LIMIT :limit")
    fun getResultsByType(type: String, limit: Int): Flow<List<TaskResultEntity>>

    @Query("SELECT * FROM task_results WHERE id = :id")
    suspend fun getResultById(id: String): TaskResultEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: TaskResultEntity)

    @Delete
    suspend fun delete(result: TaskResultEntity)

    @Query("DELETE FROM task_results WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM task_results")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM task_results")
    suspend fun getResultCount(): Int

    @Query("DELETE FROM task_results WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOldResults(beforeTimestamp: Long)
}
