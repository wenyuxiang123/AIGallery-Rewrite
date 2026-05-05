package com.aigallery.rewrite.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Custom skill entity
 */
@Entity(tableName = "custom_skills")
data class CustomSkillEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String,
    val type: String,
    val icon: String?,
    val systemPrompt: String,
    val tools: String, // JSON array
    val isBuiltIn: Boolean,
    val isEnabled: Boolean,
    val usageCount: Int,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Task template entity
 */
@Entity(tableName = "task_templates")
data class TaskTemplateEntity(
    @PrimaryKey
    val id: String,
    val type: String,
    val name: String,
    val description: String,
    val promptTemplate: String,
    val parameters: String, // JSON array
    val isBuiltIn: Boolean,
    val isFavorite: Boolean,
    val usageCount: Int
)

/**
 * Task result history entity
 */
@Entity(tableName = "task_results")
data class TaskResultEntity(
    @PrimaryKey
    val id: String,
    val taskType: String,
    val input: String,
    val output: String,
    val parameters: String, // JSON map
    val durationMs: Long,
    val timestamp: Long
)
