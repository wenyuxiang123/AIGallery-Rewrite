package com.aigallery.rewrite.skill

import android.content.Context
import com.aigallery.rewrite.util.FileLogger
import java.io.InputStreamReader

/**
 * Skill registry - discovers, loads, and matches skills
 * 
 * Skills are defined as SKILL.md files in assets/skills/ directory.
 * Each skill specifies: name, description, tools, prompt template, examples.
 * 
 * Matching strategy:
 * 1. Keyword matching (current) - matches user input against skill keywords
 * 2. Future: Semantic matching using embeddings
 */
class SkillRegistry(
    private val context: Context
) {
    companion object {
        private const val TAG = "SkillRegistry"
        private const val SKILLS_DIR = "skills"
    }

    private val skills = mutableMapOf<String, SkillDefinition>()
    private var isLoaded = false

    /**
     * Load all skills from assets/skills/ directory
     */
    fun loadSkills() {
        if (isLoaded) return
        
        try {
            val assetFiles = context.assets.list(SKILLS_DIR) ?: emptyArray()
            FileLogger.d(TAG, "loadSkills: found ${assetFiles.size} skill files")
            
            for (fileName in assetFiles) {
                if (fileName.endsWith(".md")) {
                    try {
                        val skill = parseSkillFile(fileName)
                        if (skill != null) {
                            skills[skill.id] = skill
                            FileLogger.d(TAG, "loadSkills: loaded skill '${skill.name}' (id=${skill.id})")
                        }
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "loadSkills: failed to parse $fileName", e)
                    }
                }
            }
            
            isLoaded = true
            FileLogger.i(TAG, "loadSkills: ${skills.size} skills loaded")
        } catch (e: Exception) {
            FileLogger.e(TAG, "loadSkills: failed to list assets", e)
        }
    }

    /**
     * Match user input against registered skills
     * Returns skills sorted by confidence (highest first)
     */
    fun matchSkill(userMessage: String): List<SkillMatch> {
        loadSkills()
        
        val matches = mutableListOf<SkillMatch>()
        val msgLower = userMessage.lowercase()
        
        for (skill in skills.values) {
            if (!skill.isEnabled) continue
            
            val matchedKeywords = mutableListOf<String>()
            var score = 0f
            
            // Check name match
            if (msgLower.contains(skill.name.lowercase())) {
                score += 0.5f
                matchedKeywords.add(skill.name)
            }
            
            // Check description keywords
            val descWords = skill.description.lowercase().split("\\s+")
            for (word in descWords) {
                if (word.length > 1 && msgLower.contains(word)) {
                    score += 0.1f
                    matchedKeywords.add(word)
                }
            }
            
            // Check tool names
            for (toolName in skill.tools) {
                val toolKeywords = toolName.lowercase().split("_")
                for (kw in toolKeywords) {
                    if (kw.length > 1 && msgLower.contains(kw)) {
                        score += 0.2f
                        matchedKeywords.add(kw)
                    }
                }
            }
            
            // Check category keywords
            val categoryKeywords = getCategoryKeywords(skill.category)
            for (kw in categoryKeywords) {
                if (msgLower.contains(kw)) {
                    score += 0.15f
                    matchedKeywords.add(kw)
                }
            }
            
            if (score > 0.1f) {
                matches.add(SkillMatch(
                    skill = skill,
                    confidence = score.coerceAtMost(1.0f),
                    matchedKeywords = matchedKeywords.distinct()
                ))
            }
        }
        
        return matches.sortedByDescending { it.confidence }
    }

    /**
     * Get a skill by ID
     */
    fun getSkill(id: String): SkillDefinition? {
        loadSkills()
        return skills[id]
    }

    /**
     * Get all registered skills
     */
    fun getAllSkills(): List<SkillDefinition> {
        loadSkills()
        return skills.values.toList()
    }

    /**
     * Parse a SKILL.md file into SkillDefinition
     */
    private fun parseSkillFile(fileName: String): SkillDefinition? {
        try {
            val reader = InputStreamReader(context.assets.open("$SKILLS_DIR/$fileName"))
            val content = reader.readText()
            reader.close()
            
            val id = fileName.removeSuffix(".md")
            
            // Simple markdown parsing
            val name = extractSection(content, "## \u540d\u79f0", "##") 
                ?: extractSection(content, "## Name", "##")
                ?: id.replace("_", " ")
            
            val description = extractSection(content, "## \u63cf\u8ff0", "##")
                ?: extractSection(content, "## Description", "##")
                ?: ""
            
            val toolsStr = extractSection(content, "## \u5de5\u5177", "##")
                ?: extractSection(content, "## Tools", "##")
                ?: ""
            val tools = toolsStr.lines()
                .map { it.trim().removePrefix("- ").removePrefix("* ").trim() }
                .filter { it.isNotBlank() && it.contains("(") }
            
            val promptTemplate = extractSection(content, "## \u63d0\u793a\u8bcd", "##")
                ?: extractSection(content, "## Prompt", "##")
                ?: ""
            
            val examples = parseExamples(content)
            
            return SkillDefinition(
                id = id,
                name = name.trim(),
                description = description.trim(),
                tools = tools,
                promptTemplate = promptTemplate.trim(),
                examples = examples,
                category = inferCategory(name, description, toolsStr)
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "parseSkillFile: failed for $fileName", e)
            return null
        }
    }

    /**
     * Extract a section from markdown content
     */
    private fun extractSection(content: String, sectionHeader: String, nextSectionPrefix: String): String? {
        val regex = Regex(sectionHeader, RegexOption.IGNORE_CASE)
        val match = regex.find(content) ?: return null
        
        val startIdx = match.range.last + 1
        val nextSection = Regex(nextSectionPrefix, RegexOption.IGNORE_CASE).find(content, startIdx)
        val endIdx = nextSection?.range?.first ?: content.length
        
        return content.substring(startIdx, endIdx).trim()
    }

    /**
     * Parse examples section
     */
    private fun parseExamples(content: String): List<SkillExample> {
        val examplesSection = extractSection(content, "## \u793a\u4f8b", "##")
            ?: extractSection(content, "## Example", "##")
            ?: return emptyList()
        
        return examplesSection.split("\n\n").mapNotNull { block ->
            val lines = block.lines().filter { it.isNotBlank() }
            if (lines.size >= 2) {
                SkillExample(
                    userInput = lines[0].removePrefix("\u7528\u6237\uff1a").removePrefix("User: ").trim(),
                    expectedToolCalls = emptyList(),
                    expectedResponse = lines[1].removePrefix("\u52a9\u624b\uff1a").removePrefix("Assistant: ").trim()
                )
            } else null
        }
    }

    /**
     * Infer skill category from name/description/tools
     */
    private fun inferCategory(name: String, description: String, toolsStr: String): SkillCategory {
        val combined = "$name $description $toolsStr".lowercase()
        return when {
            combined.contains("\u8ba1\u7b97") || combined.contains("calcul") -> SkillCategory.CALCULATION
            combined.contains("\u641c\u7d22") || combined.contains("search") -> SkillCategory.SEARCH
            combined.contains("\u7ffb\u8bd1") || combined.contains("translat") -> SkillCategory.TRANSLATION
            combined.contains("\u4ee3\u7801") || combined.contains("cod") -> SkillCategory.CODING
            combined.contains("\u5929\u6c14") || combined.contains("weather") -> SkillCategory.WEATHER
            combined.contains("\u65e5\u7a0b") || combined.contains("schedul") -> SkillCategory.SCHEDULING
            combined.contains("\u521b\u4f5c") || combined.contains("writ") || combined.contains("creative") -> SkillCategory.CREATIVE
            else -> SkillCategory.GENERAL
        }
    }

    /**
     * Get category-specific keywords for matching
     */
    private fun getCategoryKeywords(category: SkillCategory): List<String> {
        return when (category) {
            SkillCategory.CALCULATION -> listOf("\u8ba1\u7b97", "\u7b97", "\u591a\u5c11", "calcul", "math")
            SkillCategory.SEARCH -> listOf("\u641c\u7d22", "\u67e5\u627e", "\u627e", "search", "find")
            SkillCategory.TRANSLATION -> listOf("\u7ffb\u8bd1", "translat", "translate")
            SkillCategory.CODING -> listOf("\u4ee3\u7801", "\u7f16\u7a0b", "code", "program")
            SkillCategory.WEATHER -> listOf("\u5929\u6c14", "weather", "\u6e29\u5ea6")
            SkillCategory.SCHEDULING -> listOf("\u65e5\u7a0b", "\u63d0\u9192", "schedule", "calendar")
            SkillCategory.CREATIVE -> listOf("\u5199", "\u521b\u4f5c", "\u6587\u7ae0", "write", "creative")
            SkillCategory.GENERAL -> emptyList()
        }
    }
}
