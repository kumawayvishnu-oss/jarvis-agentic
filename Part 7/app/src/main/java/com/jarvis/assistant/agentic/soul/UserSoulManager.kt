package com.jarvis.assistant.agentic.soul

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages the "User SOUL" — continuous, self-learning user memory (Hermes-style).
 * Automatically extracts user traits, preferences, habits, and communication expectations,
 * and dynamically synthesizes recurring skills.
 */
class UserSoulManager(
    private val context: Context,
    private val soulDao: SoulDao,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    companion object {
        private const val TAG = "UserSoulManager"

        @Volatile
        private var instance: UserSoulManager? = null

        fun getInstance(context: Context): UserSoulManager {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val db = SoulDatabase.getInstance(context)
                    UserSoulManager(context.applicationContext, db.soulDao())
                        .also { instance = it }
                }
            }
        }
    }

    private val gson = Gson()
    private val traitMutex = Mutex()
    private val _cachedUserSoul = MutableStateFlow<List<UserSoulEntity>>(emptyList())
    val cachedUserSoul: StateFlow<List<UserSoulEntity>> = _cachedUserSoul.asStateFlow()

    init {
        refreshCache()
        scope.launch {
            try {
                soulDao.getAllUserSoulAttributesFlow().collect { list ->
                    _cachedUserSoul.value = list
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting user soul flow", e)
            }
        }
    }

    fun refreshCache() {
        scope.launch {
            try {
                val list = soulDao.getAllUserSoulAttributes()
                _cachedUserSoul.value = list
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh User SOUL cache", e)
            }
        }
    }

    val userSoulFlow: Flow<List<UserSoulEntity>> = soulDao.getAllUserSoulAttributesFlow()
    val activeSkillsFlow: Flow<List<SynthesizedSkillEntity>> = soulDao.getActiveSkillsFlow()

    /**
     * Ingests legacy memories from MemoryDatabase into the Hermes User SOUL matrix.
     * Checks for existing keys to prevent duplicate auto-increment rows on app startup.
     */
    fun importLegacyMemories(legacyMemories: List<com.jarvis.assistant.data.memory.MemoryEntity>) {
        scope.launch {
            traitMutex.withLock {
                try {
                    val now = System.currentTimeMillis()
                    legacyMemories.forEach { mem ->
                        val key = mem.key.ifBlank { "legacy_mem_${mem.id}" }
                        val value = mem.value.ifBlank { mem.rawText }
                        val cat = when (mem.category.lowercase()) {
                            "user", "profile", "identity" -> UserSoulEntity.CAT_PERSONALITY
                            "preference", "habit" -> UserSoulEntity.CAT_PREFERENCES
                            "routine", "schedule" -> UserSoulEntity.CAT_ROUTINES
                            else -> UserSoulEntity.CAT_HABITS
                        }
                        val existing = soulDao.getUserSoulByKey(key)
                        val entity = existing?.copy(
                            category = cat,
                            traitValue = value,
                            confidence = 0.95f,
                            evidenceSummary = "LegacyMemoryDB import",
                            updatedAt = now
                        ) ?: UserSoulEntity(
                            category = cat,
                            traitKey = key,
                            traitValue = value,
                            confidence = 0.95f,
                            evidenceSummary = "LegacyMemoryDB import",
                            createdAt = now,
                            updatedAt = now
                        )
                        soulDao.insertOrUpdateUserSoul(entity)
                    }
                    refreshCache()
                } catch (e: Exception) {
                    Log.e(TAG, "Error importing legacy memories: ${e.message}")
                }
            }
        }
    }

    /**
     * Analyzes and learns from ongoing user conversation turn.
     */
    fun analyzeAndLearn(userText: String) {
        observeTurn(userText, "")
    }

    /**
     * Analyzes an ongoing conversational turn (Hermes-style heuristic reflection).
     * Extracts traits, habits, tone, and checks if a new dynamic skill should be synthesized.
     */
    fun observeTurn(userText: String, assistantReply: String = "") {
        val query = userText.trim()
        if (query.isBlank()) return

        scope.launch {
            try {
                analyzeCommunicationStyle(query)
                analyzeHabitsAndPreferences(query)
                checkAndSynthesizeSkill(query)
                refreshCache()
            } catch (e: Exception) {
                Log.e(TAG, "Error in User SOUL reflection loop", e)
            }
        }
    }

    private suspend fun learnOrReinforceTrait(
        category: String,
        traitKey: String,
        traitValue: String,
        evidence: String
    ) = traitMutex.withLock {
        val existing = soulDao.getUserSoulByKey(traitKey)
        val now = System.currentTimeMillis()

        if (existing != null) {
            val newConfidence = (existing.confidence + 0.15f).coerceAtMost(0.98f)
            val updated = existing.copy(
                traitValue = traitValue,
                confidence = newConfidence,
                observationCount = existing.observationCount + 1,
                evidenceSummary = if (existing.evidenceSummary.contains(evidence)) existing.evidenceSummary else "${existing.evidenceSummary} | $evidence",
                lastObservedAt = now,
                updatedAt = now
            )
            soulDao.insertOrUpdateUserSoul(updated)
            Log.d(TAG, "Reinforced User SOUL trait: $traitKey (confidence=$newConfidence)")
        } else {
            val newAttribute = UserSoulEntity(
                category = category,
                traitKey = traitKey,
                traitValue = traitValue,
                confidence = 0.55f,
                evidenceSummary = evidence,
                observationCount = 1,
                lastObservedAt = now,
                createdAt = now,
                updatedAt = now
            )
            soulDao.insertOrUpdateUserSoul(newAttribute)
            Log.d(TAG, "Learned NEW User SOUL trait: $traitKey -> $traitValue")
        }
    }

    private suspend fun analyzeCommunicationStyle(text: String) {
        val lower = text.lowercase()

        // Conciseness preference
        if (lower.contains("jaldi") || lower.contains("short") || lower.contains("seedha") || lower.contains("point pe") || lower.contains("fast") || lower.contains("time waste mat")) {
            learnOrReinforceTrait(
                category = UserSoulEntity.CAT_COMMUNICATION,
                traitKey = "prefers_brevity",
                traitValue = "Prefers ultra-concise, direct answers with zero preamble or robotic fluff",
                evidence = "User emphasized: '$text'"
            )
        }

        // Language nuance
        if (lower.contains("kya") || lower.contains("kaise") || lower.contains("batao") || lower.contains("karo") || lower.contains("yaar")) {
            learnOrReinforceTrait(
                category = UserSoulEntity.CAT_COMMUNICATION,
                traitKey = "native_vernacular",
                traitValue = "Naturally speaks and thinks in fluent, casual conversational Hinglish/Hindi",
                evidence = "Observed natural vernacular phrasing in: '$text'"
            )
        }
    }

    private suspend fun analyzeHabitsAndPreferences(text: String) {
        val lower = text.lowercase()

        // Music preference
        val musicKeywords = listOf("play", "bajao", "chalao", "song", "gana", "music", "spotify", "youtube")
        if (musicKeywords.any { lower.contains(it) }) {
            val songClean = text.replace(Regex("(?i)play|bajao|chalao|song|gana|on youtube|on spotify"), "").trim()
            if (songClean.isNotBlank() && songClean.length > 2) {
                learnOrReinforceTrait(
                    category = UserSoulEntity.CAT_PREFERENCES,
                    traitKey = "frequent_music_request",
                    traitValue = "Enjoys songs like: '$songClean'",
                    evidence = "Requested song playback: '$text'"
                )
            }
        }

        // Technical / Coding Persona
        if (lower.contains("code") || lower.contains("github") || lower.contains("git") || lower.contains("android") || lower.contains("apk") || lower.contains("api") || lower.contains("terminal")) {
            learnOrReinforceTrait(
                category = UserSoulEntity.CAT_PERSONALITY,
                traitKey = "technical_builder",
                traitValue = "High technical intellect: developer, hacker, and system builder",
                evidence = "Worked on codebase/terminal tools: '$text'"
            )
        }

        // Night owl / Late night routine
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (hour in 23..24 || hour in 0..4) {
            learnOrReinforceTrait(
                category = UserSoulEntity.CAT_ROUTINES,
                traitKey = "night_owl_schedule",
                traitValue = "Frequently active and working during late night / early morning hours",
                evidence = "Active at $hour:00 local time"
            )
        }
    }

    /**
     * Synthesizes dynamic skills automatically when recurring patterns are spotted.
     */
    private suspend fun checkAndSynthesizeSkill(query: String) {
        val lower = query.lowercase()

        // Example auto-synthesis: Late night music trigger
        if ((lower.contains("gana sunao") || lower.contains("play my song") || lower.contains("chill")) &&
            !hasSkillNamed("Evening Ambient Chill Session")
        ) {
            val skill = SynthesizedSkillEntity(
                name = "Auto Chill Music Routine",
                description = "Instantly launches user's favorite relaxing music on YouTube without redundant questioning",
                triggerPattern = "When user asks for music or chill session casually",
                actionChainJson = gson.toJson(listOf("search_and_play_youtube", "set_volume")),
                autoGenerated = true
            )
            soulDao.insertSkill(skill)
            Log.d(TAG, "Auto-synthesized dynamic skill: ${skill.name}")
        }
    }

    private suspend fun hasSkillNamed(name: String): Boolean {
        val list = soulDao.getActiveSkills()
        return list.any { it.name.equals(name, ignoreCase = true) }
    }

    /**
     * Generates a dense, rich context block that is injected directly into system prompts.
     * Every sub-agent and the Gemini Live session reads this so JARVIS deeply understands the user.
     */
    fun buildUserSoulPromptContext(): String {
        val attributes = _cachedUserSoul.value
        if (attributes.isEmpty()) {
            return "[USER SOUL MATRIX: Learning initial user baseline...]"
        }

        val sb = StringBuilder()
        sb.append("[USER SOUL MATRIX — DEEP COGNITIVE PROFILE (HERMES-STYLE)]:\n")

        val grouped = attributes.groupBy { it.category }
        grouped.forEach { (cat, list) ->
            sb.append("• $cat:\n")
            list.sortedByDescending { it.confidence }.take(4).forEach { item ->
                val confPct = (item.confidence * 100).toInt()
                sb.append("   - [${confPct}% Confidence] ${item.traitValue}\n")
            }
        }
        sb.append("Treat this User SOUL as authoritative. Align tone, pacing, and actions accordingly.")
        return sb.toString()
    }

    fun buildPromptContext(): String = buildUserSoulPromptContext()
}
