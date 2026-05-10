package com.aigallery.rewrite.skill

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Skill definition data model
 * Parsed from SKILL.md files in assets/skills/ directory
 */
data class SkillDefinition(
    val id: String,
    val name: String,
    val description: String,
    val tools: List<String>,           // Tool names this skill uses
    val promptTemplate: String,        // Prompt template with {variables}
    val examples: List<SkillExample>,
    val category: SkillCategory = SkillCategory.GENERAL,
    val priority: Int = 0,             // Higher = more likely to match
    val isEnabled: Boolean = true
)

/**
 * Skill example (few-shot)
 */
data class SkillExample(
    val userInput: String,
    val expectedToolCalls: List<String>,
    val expectedResponse: String
)

/**
 * Skill category
 */
enum class SkillCategory {
    GENERAL,        // General Q&A
    CALCULATION,    // Math & calculation
    SEARCH,         // Information retrieval
    TRANSLATION,    // Translation
    CODING,         // Code assistance
    WEATHER,        // Weather queries
    SCHEDULING,     // Calendar & scheduling
    CREATIVE        // Creative writing
}

/**
 * Skill match result with confidence score
 */
data class SkillMatch(
    val skill: SkillDefinition,
    val confidence: Float,    // 0.0 - 1.0
    val matchedKeywords: List<String>
)
