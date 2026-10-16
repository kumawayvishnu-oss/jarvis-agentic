package com.jarvis.assistant.agentic.soul

import android.content.Context
import com.jarvis.assistant.agentic.router.ProviderVault
import com.jarvis.assistant.data.memory.MemoryEntity
import com.jarvis.assistant.data.memory.MemoryRepository
import com.jarvis.assistant.data.model.ChatTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp

/**
 * Shared 4-Layer Cognitive Memory Context (Hermes-style) read and written by all agents:
 * 1. Short-Term Memory: Current conversation turns
 * 2. Episodic Memory: Recent task execution events & step logs
 * 3. Permanent Profile & Facts: User SOUL traits, preferences, habits, and encrypted sensitive facts
 * 4. Procedural Skills: Auto-synthesized skills & external SKILL.md definitions
 */
data class ScoredMemoryItem(
    val key: String,
    val category: String,
    val content: String,
    val importanceScore: Float,
    val recencyScore: Float,
    val relevanceScore: Float,
    val isEncrypted: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class SharedAgentSnapshot(
    val query: String,
    val shortTermTurns: List<ChatTurn>,
    val episodicSummary: List<SoulStepLog>,
    val relevantSoulTraits: List<ScoredMemoryItem>,
    val relevantSkills: List<SynthesizedSkillEntity>,
    val activeMissionState: String,
    val detectedQueryTone: QueryTone
) {
    fun toCompactPromptBlock(): String = buildString {
        append("[SHARED AGENT CONTEXT (4-TIER MEMORY)]:\n")
        append("• Detected Tone: ${detectedQueryTone.name} (${detectedQueryTone.instruction})\n")
        if (relevantSoulTraits.isNotEmpty()) {
            append("• User SOUL & Facts (Top Relevant):\n")
            relevantSoulTraits.take(6).forEach { item ->
                val scorePct = (item.relevanceScore * 100).toInt()
                append("  - [${item.category} | rel:$scorePct%] ${item.key}: ${item.content}\n")
            }
        }
        if (relevantSkills.isNotEmpty()) {
            append("• Active Skills:\n")
            relevantSkills.take(4).forEach { skill ->
                append("  - ${skill.name}: ${skill.description} (trigger: ${skill.triggerPattern})\n")
            }
        }
        if (shortTermTurns.isNotEmpty()) {
            append("• Recent Conversation (Short-Term):\n")
            shortTermTurns.takeLast(3).forEach { turn ->
                append("  User: ${turn.userTranscript.take(100)} -> Jarvis: ${turn.jarvisResponse.take(100)}\n")
            }
        }
        if (activeMissionState.isNotBlank()) {
            append("• Live Agent SOUL State:\n$activeMissionState\n")
        }
    }
}

enum class QueryTone(val instruction: String) {
    URGENT_CONCISE("User wants immediate action or ultra-short direct answer. Zero fluff."),
    TECHNICAL_DEEP("User is asking a technical/coding/architecture question. Provide precise, structured details."),
    CASUAL_CONVERSATIONAL("User is chatting naturally in Hindi/Hinglish/English. Match their warm, natural tone."),
    COMMAND_EXECUTION("User issued a direct device or app command. Execute first, confirm briefly.")
}

class AgentContext private constructor(private val context: Context) {

    private val userSoulManager by lazy { UserSoulManager.getInstance(context) }
    private val agentSoulManager by lazy { AgentSoulManager.getInstance(context) }
    private val providerVault by lazy { ProviderVault.getInstance(context) }
    private val skillLoader by lazy { ExternalSkillLoader.getInstance(context) }

    companion object {
        private const val HALF_LIFE_MS = 7 * 24 * 60 * 60 * 1000.0 // 7 days decay half-life
        private val SENSITIVE_KEYWORDS = listOf("password", "token", "secret", "pin", "bank", "upi", "cvv", "key", "aadhaar", "pan")

        @Volatile
        private var INSTANCE: AgentContext? = null

        fun getInstance(context: Context): AgentContext {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AgentContext(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Detects the user's question style/tone to adapt the response accordingly.
     */
    fun detectTone(query: String): QueryTone {
        val lower = query.lowercase().trim()
        return when {
            lower.contains("jaldi") || lower.contains("fast") || lower.contains("short") ||
                lower.contains("turant") || lower.length < 18 -> QueryTone.URGENT_CONCISE
            lower.contains("code") || lower.contains("architecture") || lower.contains("bug") ||
                lower.contains("error") || lower.contains("explain") || lower.contains("kaise kam") -> QueryTone.TECHNICAL_DEEP
            lower.startsWith("open ") || lower.startsWith("play ") || lower.startsWith("call ") ||
                lower.contains("kholo") || lower.contains("bajao") || lower.contains("bhejo") ||
                lower.contains("set alarm") || lower.contains("turn on") || lower.contains("turn off") -> QueryTone.COMMAND_EXECUTION
            else -> QueryTone.CASUAL_CONVERSATIONAL
        }
    }

    /**
     * Computes combined relevance + importance + recency score for a memory item against a query.
     */
    fun scoreMemory(
        key: String,
        value: String,
        category: String,
        confidence: Float,
        updatedAt: Long,
        query: String
    ): ScoredMemoryItem {
        val now = System.currentTimeMillis()
        val ageMs = (now - updatedAt).coerceAtLeast(0L).toDouble()
        val recencyScore = exp(-ageMs / HALF_LIFE_MS).toFloat().coerceIn(0.1f, 1.0f)
        val importanceScore = confidence.coerceIn(0.1f, 1.0f)

        val queryTokens = query.lowercase()
            .split(Regex("[^a-zA-Z0-9\\u0900-\\u097F]+"))
            .filter { it.length > 2 }

        val targetText = "$key $value $category".lowercase()
        val matchCount = if (queryTokens.isEmpty()) 0 else queryTokens.count { targetText.contains(it) }
        val keywordScore = if (queryTokens.isEmpty()) 0.5f else (matchCount.toFloat() / queryTokens.size).coerceIn(0f, 1f)

        // Combined weighted score: Relevance (45%) + Importance (35%) + Recency (20%)
        val totalScore = if (query.isBlank()) {
            (importanceScore * 0.65f) + (recencyScore * 0.35f)
        } else {
            (keywordScore * 0.45f) + (importanceScore * 0.35f) + (recencyScore * 0.20f)
        }

        val isSens = SENSITIVE_KEYWORDS.any { key.lowercase().contains(it) || category.lowercase().contains("secret") }
        val resolvedValue = if (isSens && value.startsWith("ENC:")) {
            providerVault.decryptCustomSecret(key) ?: "[ENCRYPTED]"
        } else {
            value
        }

        return ScoredMemoryItem(
            key = key,
            category = category,
            content = resolvedValue,
            importanceScore = importanceScore,
            recencyScore = recencyScore,
            relevanceScore = totalScore,
            isEncrypted = isSens,
            timestamp = updatedAt
        )
    }

    /**
     * Stores a sensitive fact encrypted with AES-256 GCM in ProviderVault and references it in UserSoul.
     */
    suspend fun storeFact(
        key: String,
        value: String,
        category: String = UserSoulEntity.CAT_PREFERENCES,
        isSensitive: Boolean = false
    ) {
        val autoSensitive = isSensitive || SENSITIVE_KEYWORDS.any { key.lowercase().contains(it) }
        if (autoSensitive) {
            providerVault.encryptCustomSecret(key, value)
            userSoulManager.setExplicitTrait(category, key, "ENC:AES256_PROTECTED", 0.95f)
        } else {
            userSoulManager.setExplicitTrait(category, key, value, 0.90f)
        }
    }

    /**
     * Builds the unified snapshot for any agent (Manager, Specialist, Dynamic, or Live Voice).
     */
    suspend fun buildSnapshot(
        query: String,
        shortTermTurns: List<ChatTurn> = emptyList(),
        legacyMemories: List<MemoryEntity> = emptyList()
    ): SharedAgentSnapshot = withContext(Dispatchers.IO) {
        userSoulManager.observeTurn(query)

        val soulTraits = userSoulManager.cachedUserSoul.value
        val scoredList = mutableListOf<ScoredMemoryItem>()
        val seenKeys = HashSet<String>()

        // 1. Score User SOUL traits (deduplicated by normalized key)
        for (trait in soulTraits) {
            val normKey = trait.traitKey.trim().lowercase()
            if (seenKeys.add(normKey)) {
                scoredList.add(
                    scoreMemory(
                        key = trait.traitKey,
                        value = trait.traitValue,
                        category = trait.category,
                        confidence = trait.confidence,
                        updatedAt = trait.updatedAt,
                        query = query
                    )
                )
            }
        }

        // 2. Score legacy Room memories (deduplicated)
        for (mem in legacyMemories) {
            val normKey = mem.key.ifBlank { mem.rawText.take(24) }.trim().lowercase()
            if (seenKeys.add(normKey)) {
                scoredList.add(
                    scoreMemory(
                        key = mem.key.ifBlank { mem.category },
                        value = mem.value.ifBlank { mem.rawText },
                        category = mem.category,
                        confidence = 0.85f,
                        updatedAt = mem.timestamp,
                        query = query
                    )
                )
            }
        }

        val sortedRelevant = scoredList.sortedByDescending { it.relevanceScore }.take(8)
        val allSkills = skillLoader.getAllAvailableSkills()

        SharedAgentSnapshot(
            query = query,
            shortTermTurns = shortTermTurns.takeLast(5),
            episodicSummary = agentSoulManager.getRecentStepLogsSnapshot(6),
            relevantSoulTraits = sortedRelevant,
            relevantSkills = allSkills.take(5),
            activeMissionState = agentSoulManager.buildAgentSoulPromptContext(),
            detectedQueryTone = detectTone(query)
        )
    }
}
