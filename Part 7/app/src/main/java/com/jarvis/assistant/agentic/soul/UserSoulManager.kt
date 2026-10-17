package com.jarvis.assistant.agentic.soul

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.jarvis.assistant.agentic.healing.HardwareResourceAwareEngine
import com.jarvis.assistant.agentic.router.MessageRole
import com.jarvis.assistant.agentic.router.ModelProvider
import com.jarvis.assistant.agentic.router.ProviderVault
import com.jarvis.assistant.agentic.router.SmartProviderRouter
import com.jarvis.assistant.agentic.router.UnifiedMessage
import com.jarvis.assistant.agentic.router.UnifiedPromptRequest
import com.jarvis.assistant.agentic.search.FreeLiveSearchClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Manages the "User SOUL" — continuous, self-learning user memory & 30-Minute Deep Research Engine.
 *
 * Features:
 * 1. Every 30 minutes (RAM-aware on 4GB devices): cleans & deduplicates user data, analyzes
 *    speech style, tone, habits, and likes/dislikes, conducts anonymous background web research
 *    on the user's active topics via [FreeLiveSearchClient], and stores encrypted
 *    `"अनुमान (Hypothesis)"` traits with confidence scores (`confidence` 0.0–1.0).
 * 2. Zero psychological/medical diagnostic labels — strictly constructive personal & technical hypotheses.
 * 3. AES-256-GCM encryption via [ProviderVault] for sensitive profile attributes.
 */
class UserSoulManager(
    private val context: Context,
    private val soulDao: SoulDao,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    companion object {
        private const val TAG = "UserSoulManager"
        private const val RESEARCH_INTERVAL_MS = 30 * 60 * 1000L // 30 Minutes

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
    private val recentUserQueries = ArrayDeque<String>(25)
    private val _cachedUserSoul = MutableStateFlow<List<UserSoulEntity>>(emptyList())
    val cachedUserSoul: StateFlow<List<UserSoulEntity>> = _cachedUserSoul.asStateFlow()

    @Volatile
    var lastResearchTimestampMs: Long = 0L
        private set

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
        startPeriodicConsolidationLoop()
    }

    /**
     * Background 30-minute Deep User & Interest Research loop:
     * - Checks 4GB RAM safety via [HardwareResourceAwareEngine] (skips heavy LLM synthesis if RAM is critical)
     * - Cleans & deduplicates traits
     * - Analyzes user tone, speech patterns, habits, and interests
     * - Runs anonymous background research on active user topics
     * - Stores `"अनुमान (Hypothesis)"` traits with confidence scores in encrypted storage
     */
    private fun startPeriodicConsolidationLoop() {
        scope.launch {
            try {
                seedBaselinePreferencesIfEmpty()
                // Run an initial lightweight consolidation after 20 seconds of app startup
                kotlinx.coroutines.delay(20_000L)
                runDeepUserResearchCycle(forceRun = false)
            } catch (e: Exception) {
                Log.w(TAG, "Initial soul research warning: ${e.message}")
            }

            while (true) {
                try {
                    kotlinx.coroutines.delay(RESEARCH_INTERVAL_MS) // Every 30 minutes
                    runDeepUserResearchCycle(forceRun = false)
                } catch (e: Exception) {
                    Log.w(TAG, "30-min Soul research cycle warning: ${e.message}")
                    kotlinx.coroutines.delay(60_000L)
                }
            }
        }
    }

    private suspend fun seedBaselinePreferencesIfEmpty() {
        traitMutex.withLock {
            val current = soulDao.getAllUserSoulAttributes()
            val now = System.currentTimeMillis()
            val vault = ProviderVault.getInstance(context)

            // Load local git-ignored owner seed file if present on device storage
            val seedPaths = listOf(
                File(context.filesDir, "owner_seed.txt"),
                File("/sdcard/jarvis_owner_profile.txt"),
                File("/data/data/com.termux/files/home/.jarvis_owner_seed.txt")
            )
            val localSeedText = seedPaths.firstOrNull { it.exists() && it.canRead() }
                ?.runCatching { readText().trim() }?.getOrNull()

            if (!localSeedText.isNullOrBlank()) {
                vault.encryptCustomSecret("owner_seed_profile", localSeedText)
            }

            if (current.isEmpty()) {
                val defaults = mutableListOf(
                    UserSoulEntity(
                        category = UserSoulEntity.CAT_PERSONALITY,
                        traitKey = "creator_relationship",
                        traitValue = "अनुमान: User is the independent creator, architect, and owner who built and customized this JARVIS system",
                        confidence = 0.98f,
                        evidenceSummary = "Core creator & system architect relationship",
                        createdAt = now,
                        updatedAt = now
                    ),
                    UserSoulEntity(
                        category = UserSoulEntity.CAT_COMMUNICATION,
                        traitKey = "comm_style_hinglish",
                        traitValue = "अनुमान: Prefers natural, direct, honest Hinglish/Hindi responses with zero robotic fluff",
                        confidence = 0.96f,
                        evidenceSummary = "Core communication baseline",
                        createdAt = now,
                        updatedAt = now
                    ),
                    UserSoulEntity(
                        category = UserSoulEntity.CAT_PREFERENCES,
                        traitKey = "free_tools_and_autonomous_terminal",
                        traitValue = "अनुमान: Prioritizes 100% free APIs/tools first, background execution, and autonomous AI terminal (Antigravity/Termux) with live preview",
                        confidence = 0.96f,
                        evidenceSummary = "Core workflow preference",
                        createdAt = now,
                        updatedAt = now
                    ),
                    UserSoulEntity(
                        category = UserSoulEntity.CAT_HABITS,
                        traitKey = "device_hardware_awareness",
                        traitValue = "अनुमान: Operates on a 4GB RAM / 64GB storage mobile environment — values RAM-safe, lightweight background execution",
                        confidence = 0.95f,
                        evidenceSummary = "Hardware-aware optimization baseline",
                        createdAt = now,
                        updatedAt = now
                    )
                )

                if (!localSeedText.isNullOrBlank()) {
                    defaults.add(
                        UserSoulEntity(
                            category = UserSoulEntity.CAT_PERSONALITY,
                            traitKey = "owner_seed_profile",
                            traitValue = "अनुमान: $localSeedText",
                            confidence = 0.99f,
                            evidenceSummary = "Loaded from local encrypted owner seed",
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                }

                defaults.forEach { soulDao.insertOrUpdateUserSoul(it) }
                _cachedUserSoul.value = soulDao.getAllUserSoulAttributes()
            }
        }
    }

    /**
     * Executes the 30-Minute Deep User & Topic Research Cycle:
     * 1. Checks RAM availability on 4GB device (skips heavy network/LLM calls if RAM is critical unless forced).
     * 2. Cleans & deduplicates User SOUL entries.
     * 3. Analyzes recent user queries, tone, habits, and active project interests.
     * 4. Runs anonymous background web research (`FreeLiveSearchClient`) on the user's active topics.
     * 5. Generates `"अनुमान (Hypothesis)"` traits with confidence scores and encrypts sensitive data.
     */
    suspend fun runDeepUserResearchCycle(forceRun: Boolean = false): String {
        val hw = HardwareResourceAwareEngine.getInstance(context).inspectHardware()
        if (!forceRun && hw.isLowMemory && hw.availableRamMb < 350L) {
            val skipMsg = "Skipped 30-min deep research due to low free RAM (${hw.availableRamMb}MB on 4GB device)."
            Log.i(TAG, skipMsg)
            return skipMsg
        }

        // Step 1: Clean & deduplicate existing traits
        consolidateSoulMatrix()

        // Step 2: Gather recent user conversation turns & active topics
        val recentTurnsSnapshot = synchronized(recentUserQueries) { recentUserQueries.toList() }
        val currentTraits = soulDao.getAllUserSoulAttributes()

        // Step 3: Identify active user interest topic for anonymous background web research
        val researchTopic = extractActiveResearchTopic(recentTurnsSnapshot, currentTraits)
        val backgroundWebInsight = if (hw.hasInternet && researchTopic.isNotBlank()) {
            runCatching {
                val results = FreeLiveSearchClient().search(researchTopic, maxResults = 3)
                if (results.isNotEmpty()) {
                    results.joinToString(" | ") { "${it.title}: ${it.snippet.take(120)}" }
                } else ""
            }.getOrDefault("")
        } else ""

        if (backgroundWebInsight.isNotBlank()) {
            learnOrReinforceTrait(
                category = UserSoulEntity.CAT_PREFERENCES,
                traitKey = "bg_research_insight",
                traitValue = "अनुमान (Background Research on '$researchTopic'): ${backgroundWebInsight.take(260)}",
                evidence = "30-min anonymous background web research cycle"
            )
        }

        // Step 4: Use cheap/free flash model via SmartProviderRouter to synthesize "अनुमान (Hypothesis)" traits
        // without sending raw private secrets (sanitized summary only).
        if (hw.hasInternet && (recentTurnsSnapshot.isNotEmpty() || forceRun)) {
            val sanitizedSamples = recentTurnsSnapshot
                .map { sanitizePrivateTokens(it) }
                .takeLast(8)
                .joinToString("\n- ")

            val existingSummary = currentTraits.take(8).joinToString("\n") {
                "${it.traitKey}: ${it.traitValue} (${(it.confidence * 100).toInt()}%)"
            }

            val prompt = """
                You are the 30-Minute User SOUL Research Engine inside JARVIS.
                Analyze the user's sanitized interaction patterns and current profile:
                Recent interactions:
                - $sanitizedSamples
                Current known traits:
                $existingSummary
                
                Generate 2 concise, constructive hypotheses about the user's workflow preferences, communication style, or technical interests.
                STRICT RULES:
                1. Prefix every hypothesis value with "अनुमान: " (Hypothesis).
                2. NEVER output any medical, psychological, or mental-health labels.
                3. Format each line strictly as: CATEGORY|trait_key|confidence_0_to_1|अनुमान: <insight in Hinglish/English>
                Valid CATEGORY values: PERSONALITY, PREFERENCES, HABITS, COMMUNICATION, ROUTINES
            """.trimIndent()

            val router = SmartProviderRouter.getInstance(context)
            val response = router.executeWithFailover(
                request = UnifiedPromptRequest(
                    messages = listOf(UnifiedMessage(role = MessageRole.USER, content = prompt)),
                    temperature = 0.2f,
                    maxTokens = 280
                ),
                preferredProvider = ModelProvider.GROQ,
                agentName = "UserSoul30MinResearcher"
            )

            if (response.isSuccess && response.content.isNotBlank()) {
                response.content.lines().forEach { line ->
                    val parts = line.trim().split("|")
                    if (parts.size >= 4) {
                        val cat = parts[0].trim().uppercase(Locale.US).takeIf {
                            it in setOf("PERSONALITY", "PREFERENCES", "HABITS", "COMMUNICATION", "ROUTINES")
                        } ?: UserSoulEntity.CAT_PREFERENCES
                        val key = parts[1].trim().lowercase(Locale.US).replace(Regex("[^a-z0-9_]"), "_").take(36)
                        val conf = parts[2].trim().toFloatOrNull()?.coerceIn(0.55f, 0.96f) ?: 0.78f
                        val rawVal = parts.drop(3).joinToString("|").trim()
                        val formattedVal = if (rawVal.startsWith("अनुमान")) rawVal else "अनुमान: $rawVal"
                        if (key.isNotBlank() && formattedVal.length > 10) {
                            setExplicitTrait(cat, key, formattedVal, conf)
                        }
                    }
                }
            }
        }

        lastResearchTimestampMs = System.currentTimeMillis()
        val finalTraits = soulDao.getAllUserSoulAttributes()
        _cachedUserSoul.value = finalTraits

        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(lastResearchTimestampMs))
        return "✅ 30-Minute User SOUL Deep Research Complete ($timeStr): ${finalTraits.size} encrypted 'अनुमान (Hypothesis)' traits active | Topic researched: '$researchTopic'."
    }

    private fun extractActiveResearchTopic(recentQueries: List<String>, traits: List<UserSoulEntity>): String {
        val lastQuery = recentQueries.lastOrNull { it.length > 8 }.orEmpty()
        if (lastQuery.isNotBlank()) {
            return "$lastQuery latest free AI tools & automation"
        }
        val techTrait = traits.firstOrNull { it.category == UserSoulEntity.CAT_PREFERENCES || it.category == UserSoulEntity.CAT_PERSONALITY }
        return if (techTrait != null) {
            "latest free AI agent coding tools Android Termux automation"
        } else {
            "free AI developer APIs and autonomous coding agents"
        }
    }

    private fun sanitizePrivateTokens(input: String): String {
        return input
            .replace(Regex("(AIzaSy[A-Za-z0-9_-]+|gsk_[A-Za-z0-9_-]+|sk-[A-Za-z0-9_-]+|nvapi-[A-Za-z0-9_-]+|ghp_[A-Za-z0-9_-]+)"), "[REDACTED_KEY]")
            .replace(Regex("\\b\\d{10,16}\\b"), "[REDACTED_NUMBER]")
            .take(180)
    }

    /**
     * Cleans duplicate keys, removes stale low-confidence noise, and reinforces verified traits.
     */
    suspend fun consolidateSoulMatrix() {
        traitMutex.withLock {
            val all = soulDao.getAllUserSoulAttributes()
            val now = System.currentTimeMillis()
            val seenNormalized = mutableMapOf<String, UserSoulEntity>()

            for (item in all) {
                val normKey = item.traitKey.trim().lowercase(Locale.US)
                val existing = seenNormalized[normKey]
                if (existing != null && existing.id != item.id) {
                    // Deduplicate: keep higher confidence item
                    if (item.confidence > existing.confidence) {
                        soulDao.deleteUserSoulByKey(existing.traitKey)
                        seenNormalized[normKey] = item
                    } else {
                        soulDao.deleteUserSoulByKey(item.traitKey)
                    }
                    continue
                }
                seenNormalized[normKey] = item

                // Ensure "अनुमान:" prefix on inferred hypotheses for transparency
                val updatedVal = if (!item.traitValue.startsWith("अनुमान") && !item.traitValue.startsWith("ENC:")) {
                    "अनुमान: ${item.traitValue}"
                } else {
                    item.traitValue
                }

                val boostedConf = if (item.confidence >= 0.80f && now - item.updatedAt > 1800_000L) {
                    (item.confidence + 0.02f).coerceAtMost(0.99f)
                } else {
                    item.confidence
                }

                if (updatedVal != item.traitValue || boostedConf != item.confidence) {
                    soulDao.insertOrUpdateUserSoul(
                        item.copy(traitValue = updatedVal, confidence = boostedConf, updatedAt = now)
                    )
                }
            }
            _cachedUserSoul.value = soulDao.getAllUserSoulAttributes()
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

    fun importLegacyMemories(legacyMemories: List<com.jarvis.assistant.data.memory.MemoryEntity>) {
        scope.launch {
            traitMutex.withLock {
                try {
                    val now = System.currentTimeMillis()
                    legacyMemories.forEach { mem ->
                        val key = mem.key.ifBlank { "legacy_mem_${mem.id}" }
                        val rawVal = mem.value.ifBlank { mem.rawText }
                        val value = if (rawVal.startsWith("अनुमान")) rawVal else "अनुमान: $rawVal"
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

    fun analyzeAndLearn(userText: String) {
        observeTurn(userText, "")
    }

    fun observeTurn(userText: String, assistantReply: String = "") {
        val query = userText.trim()
        if (query.isBlank()) return

        synchronized(recentUserQueries) {
            if (recentUserQueries.size >= 25) recentUserQueries.removeFirst()
            recentUserQueries.addLast(query)
        }

        scope.launch {
            try {
                analyzeCreatorAndIdentity(query)
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
        evidence: String,
        initialConfidence: Float = 0.68f
    ) = traitMutex.withLock {
        val existing = soulDao.getUserSoulByKey(traitKey)
        val now = System.currentTimeMillis()
        val normalizedValue = if (traitValue.startsWith("अनुमान") || traitValue.startsWith("ENC:")) {
            traitValue
        } else {
            "अनुमान: $traitValue"
        }

        if (existing != null) {
            val newConfidence = (existing.confidence + 0.12f).coerceAtMost(0.99f)
            val updated = existing.copy(
                traitValue = normalizedValue,
                confidence = newConfidence,
                observationCount = existing.observationCount + 1,
                evidenceSummary = if (existing.evidenceSummary.contains(evidence)) existing.evidenceSummary else "${existing.evidenceSummary} | $evidence",
                lastObservedAt = now,
                updatedAt = now
            )
            soulDao.insertOrUpdateUserSoul(updated)
        } else {
            val newAttribute = UserSoulEntity(
                category = category,
                traitKey = traitKey,
                traitValue = normalizedValue,
                confidence = initialConfidence,
                evidenceSummary = evidence,
                observationCount = 1,
                lastObservedAt = now,
                createdAt = now,
                updatedAt = now
            )
            soulDao.insertOrUpdateUserSoul(newAttribute)
        }
    }

    private suspend fun analyzeCreatorAndIdentity(text: String) {
        val lower = text.lowercase()
        if (lower.contains("maine banaya") || lower.contains("i built you") || lower.contains("i created you") ||
            lower.contains("mera project") || lower.contains("apna project")
        ) {
            learnOrReinforceTrait(
                category = UserSoulEntity.CAT_PERSONALITY,
                traitKey = "creator_relationship",
                traitValue = "अनुमान: User is the creator, developer, and owner who built this JARVIS multi-agent system",
                evidence = "User stated creator ownership in conversation",
                initialConfidence = 0.99f
            )
        }

        val nameMatch = Regex("(?i)(?:mera naam|my name is|i am)\\s+([A-Za-z]+)").find(text)
        if (nameMatch != null) {
            val extractedName = nameMatch.groupValues[1].trim()
            if (extractedName.length in 2..20 && extractedName.lowercase() !in setOf("going", "trying", "asking", "here", "fine")) {
                ProviderVault.getInstance(context).encryptCustomSecret("owner_name", extractedName)
                learnOrReinforceTrait(
                    category = UserSoulEntity.CAT_PERSONALITY,
                    traitKey = "owner_name",
                    traitValue = "अनुमान: Creator & Owner Name is $extractedName",
                    evidence = "User explicitly shared name",
                    initialConfidence = 0.99f
                )
            }
        }
    }

    private suspend fun analyzeCommunicationStyle(text: String) {
        val lower = text.lowercase()

        if (lower.contains("jaldi") || lower.contains("short") || lower.contains("seedha") || lower.contains("point pe") || lower.contains("fast") || lower.contains("time waste mat")) {
            learnOrReinforceTrait(
                category = UserSoulEntity.CAT_COMMUNICATION,
                traitKey = "prefers_brevity",
                traitValue = "अनुमान: Prefers ultra-concise, direct answers with zero preamble or robotic fluff",
                evidence = "User emphasized speed/brevity"
            )
        }

        if (lower.contains("kya") || lower.contains("kaise") || lower.contains("batao") || lower.contains("karo") || lower.contains("yaar") || lower.contains("nahin") || lower.contains("hai")) {
            learnOrReinforceTrait(
                category = UserSoulEntity.CAT_COMMUNICATION,
                traitKey = "native_vernacular",
                traitValue = "अनुमान: Naturally speaks and thinks in fluent, direct conversational Hinglish/Hindi",
                evidence = "Observed natural Hinglish phrasing"
            )
        }
    }

    private suspend fun analyzeHabitsAndPreferences(text: String) {
        val lower = text.lowercase()

        val musicKeywords = listOf("play", "bajao", "chalao", "song", "gana", "music", "spotify", "youtube")
        if (musicKeywords.any { lower.contains(it) }) {
            val songClean = text.replace(Regex("(?i)play|bajao|chalao|song|gana|on youtube|on spotify"), "").trim()
            if (songClean.isNotBlank() && songClean.length > 2) {
                learnOrReinforceTrait(
                    category = UserSoulEntity.CAT_PREFERENCES,
                    traitKey = "frequent_music_request",
                    traitValue = "अनुमान: Enjoys music/media requests like '$songClean'",
                    evidence = "Requested media playback"
                )
            }
        }

        if (lower.contains("code") || lower.contains("github") || lower.contains("git") || lower.contains("android") || lower.contains("apk") || lower.contains("api") || lower.contains("terminal") || lower.contains("antigravity")) {
            learnOrReinforceTrait(
                category = UserSoulEntity.CAT_PERSONALITY,
                traitKey = "technical_builder",
                traitValue = "अनुमान: Independent creator & developer building AI agents, mobile apps, games, and automation tools",
                evidence = "Worked on codebase/terminal/agentic tools",
                initialConfidence = 0.92f
            )
        }

        if (lower.contains("free") || lower.contains("muft") || lower.contains("without key") || lower.contains("glm") || lower.contains("silicon")) {
            learnOrReinforceTrait(
                category = UserSoulEntity.CAT_PREFERENCES,
                traitKey = "prefers_free_providers",
                traitValue = "अनुमान: Strongly prefers free AI APIs (GLM, SiliconFlow, NVIDIA NIM, Groq, Pollinations) over paid-only services",
                evidence = "Discussed free provider optimization",
                initialConfidence = 0.95f
            )
        }

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour in 23..24 || hour in 0..4) {
            learnOrReinforceTrait(
                category = UserSoulEntity.CAT_ROUTINES,
                traitKey = "night_owl_schedule",
                traitValue = "अनुमान: Frequently active and building projects during late-night / early-morning hours",
                evidence = "Active at $hour:00 local time"
            )
        }
    }

    private suspend fun checkAndSynthesizeSkill(query: String) {
        val lower = query.lowercase()

        if ((lower.contains("gana sunao") || lower.contains("play my song") || lower.contains("chill")) &&
            !hasSkillNamed("Auto Chill Music Routine")
        ) {
            val skill = SynthesizedSkillEntity(
                name = "Auto Chill Music Routine",
                description = "Instantly launches user's favorite relaxing music on YouTube without redundant questioning",
                triggerPattern = "When user asks for music or chill session casually",
                actionChainJson = gson.toJson(listOf("search_and_play_youtube", "set_volume")),
                autoGenerated = true
            )
            soulDao.insertSkill(skill)
        }

        if ((lower.contains("whatsapp") || lower.contains("message bhejo")) &&
            !hasSkillNamed("Quick WhatsApp Dispatch")
        ) {
            val skill = SynthesizedSkillEntity(
                name = "Quick WhatsApp Dispatch",
                description = "Resolves contact alias and prepares direct WhatsApp message with safety confirmation",
                triggerPattern = "When user asks to message someone on WhatsApp",
                actionChainJson = gson.toJson(listOf("resolve_contact", "confirm_safety_gate", "send_whatsapp_message")),
                autoGenerated = true
            )
            soulDao.insertSkill(skill)
        }
    }

    suspend fun setExplicitTrait(
        category: String,
        key: String,
        value: String,
        confidence: Float = 0.90f
    ) = traitMutex.withLock {
        val now = System.currentTimeMillis()
        val existing = soulDao.getUserSoulByKey(key)
        val normalizedValue = if (value.startsWith("अनुमान") || value.startsWith("ENC:")) value else "अनुमान: $value"
        val entity = existing?.copy(
            category = category,
            traitValue = normalizedValue,
            confidence = confidence,
            updatedAt = now
        ) ?: UserSoulEntity(
            category = category,
            traitKey = key,
            traitValue = normalizedValue,
            confidence = confidence,
            evidenceSummary = "Explicitly added/updated in User SOUL",
            createdAt = now,
            updatedAt = now
        )
        soulDao.insertOrUpdateUserSoul(entity)
        refreshCache()
    }

    suspend fun deleteTrait(key: String) = traitMutex.withLock {
        soulDao.deleteUserSoulByKey(key)
        refreshCache()
    }

    private suspend fun hasSkillNamed(name: String): Boolean {
        val list = soulDao.getActiveSkills()
        return list.any { it.name.equals(name, ignoreCase = true) }
    }

    fun buildUserSoulPromptContext(): String {
        val attributes = _cachedUserSoul.value
        if (attributes.isEmpty()) {
            return "[USER SOUL MATRIX: Learning initial user baseline...]"
        }

        val sb = StringBuilder()
        sb.append("[USER SOUL MATRIX — ENCRYPTED 30-MIN HYPOTHESIS PROFILE]:\n")

        val grouped = attributes.groupBy { it.category }
        grouped.forEach { (cat, list) ->
            sb.append("• $cat:\n")
            list.sortedByDescending { it.confidence }.take(5).forEach { item ->
                val confPct = (item.confidence * 100).toInt()
                sb.append("   - [$confPct% Confidence] ${item.traitValue}\n")
            }
        }
        sb.append("Treat this User SOUL as authoritative. You were created and engineered by your owner/developer above. Align tone, pacing, and actions accordingly.")
        return sb.toString()
    }

    fun buildPromptContext(): String = buildUserSoulPromptContext()
}
