package com.jarvis.assistant.agentic.router

import android.content.Context
import com.jarvis.assistant.agentic.soul.AgentSoulManager
import com.jarvis.assistant.agentic.soul.StepLogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * SmartProviderRouter: Intelligent cascading router across AI providers.
 * Seamlessly fails over from Gemini -> DeepSeek -> Groq -> OpenRouter -> Ollama
 * on 429 RPM limits, timeouts, or 5xx server errors with zero user interruption.
 */
class SmartProviderRouter private constructor(
    private val context: Context,
    private val vault: ProviderVault = ProviderVault.getInstance(context),
    private val agentSoulManager: AgentSoulManager = AgentSoulManager.getInstance(context)
) {
    // Health tracking for each provider
    private val healthMap = ConcurrentHashMap<ModelProvider, ProviderHealth>()

    // Default provider priority order
    private val defaultCascade = listOf(
        ModelProvider.GEMINI,
        ModelProvider.GROQ,
        ModelProvider.CEREBRAS,
        ModelProvider.MISTRAL,
        ModelProvider.SAMBANOVA,
        ModelProvider.DEEPSEEK,
        ModelProvider.OPENROUTER,
        ModelProvider.OLLAMA_LOCAL
    )

    init {
        ModelProvider.entries.forEach { p ->
            healthMap[p] = ProviderHealth(provider = p)
        }
    }

    /**
     * Executes a prompt with automatic, cascading failover across providers.
     */
    suspend fun executeWithFailover(
        request: UnifiedPromptRequest,
        preferredProvider: ModelProvider? = null,
        taskId: String? = null,
        agentName: String = "SmartRouter"
    ): UnifiedPromptResponse = withContext(Dispatchers.IO) {
        val candidateOrder = mutableListOf<ModelProvider>()

        // Put preferred provider first if specified
        preferredProvider?.let { candidateOrder.add(it) }

        // Add remaining providers from cascade
        defaultCascade.forEach { p ->
            if (!candidateOrder.contains(p)) {
                candidateOrder.add(p)
            }
        }

        var lastError: String? = null

        for (provider in candidateOrder) {
            val health = getOrCreateHealth(provider)

            // Check if provider has an API key configured (or is local)
            if (!vault.hasApiKey(provider)) {
                continue
            }

            // Check circuit breaker cooldown (e.g., after 429 RPM limit)
            val now = System.currentTimeMillis()
            if (!health.isHealthy && (now - health.lastErrorTimestamp) < CIRCUIT_BREAKER_COOLDOWN_MS) {
                agentSoulManager.postStepLog(
                    agentName = agentName,
                    action = "SkipProviderCooldown",
                    detail = "Provider ${provider.displayName} is in cooldown after rate limit/error. Skipping to next.",
                    level = StepLogLevel.WARNING,
                    taskId = taskId
                )
                continue
            }

            // Check RPM limits
            if (now - health.minuteWindowStart >= 60_000L) {
                health.minuteWindowStart = now
                health.requestsInCurrentMinute = 0
            }
            if (health.requestsInCurrentMinute >= provider.defaultRpmLimit) {
                agentSoulManager.postStepLog(
                    agentName = agentName,
                    action = "SkipProviderRpmLimit",
                    detail = "Provider ${provider.displayName} reached local RPM limit (${health.requestsInCurrentMinute}/${provider.defaultRpmLimit}). Cascading to next provider.",
                    level = StepLogLevel.WARNING,
                    taskId = taskId
                )
                continue
            }

            // Attempt execution with current provider
            agentSoulManager.postStepLog(
                agentName = agentName,
                action = "AttemptProviderCall",
                detail = "Calling ${provider.displayName} (model: ${provider.defaultModel})...",
                level = StepLogLevel.INFO,
                taskId = taskId
            )

            health.requestsInCurrentMinute++
            val apiKey = vault.getApiKey(provider).orEmpty()
            val baseUrl = vault.getBaseUrl(provider)
            val response = when (provider) {
                ModelProvider.GEMINI -> {
                    GeminiRestClient(apiKey = apiKey, baseUrl = baseUrl).execute(request)
                }
                else -> {
                    OpenAICompatibleClient(provider = provider, apiKey = apiKey, baseUrl = baseUrl).execute(request)
                }
            }

            if (response.isSuccess) {
                // Record success and reset health
                health.isHealthy = true
                health.consecutiveErrors = 0
                health.lastErrorMessage = null

                // If provider returned tool calls, execute them in parallel and synthesize final output
                if (response.toolCalls.isNotEmpty()) {
                    val toolRegistry = UnifiedToolRegistry.getInstance(context)
                    val toolOutputs = toolRegistry.executeToolsParallel(response.toolCalls)
                    val updatedMessages = request.messages.toMutableList()
                    toolOutputs.forEach { (call, out) ->
                        updatedMessages.add(
                            UnifiedMessage(
                                role = MessageRole.TOOL,
                                content = out,
                                toolCallId = call.id,
                                name = call.functionName
                            )
                        )
                    }
                    val followUpReq = request.copy(messages = updatedMessages, tools = emptyList())
                    val followUpResp = when (provider) {
                        ModelProvider.GEMINI -> GeminiRestClient(apiKey = apiKey, baseUrl = baseUrl).execute(followUpReq)
                        else -> OpenAICompatibleClient(provider = provider, apiKey = apiKey, baseUrl = baseUrl).execute(followUpReq)
                    }
                    if (followUpResp.isSuccess && followUpResp.content.isNotBlank()) {
                        return@withContext followUpResp
                    }
                    val joinedTools = toolOutputs.joinToString("\n") { (c, o) -> "[${c.functionName}]: $o" }
                    return@withContext response.copy(content = if (response.content.isNotBlank()) "${response.content}\n$joinedTools" else joinedTools)
                }

                agentSoulManager.postStepLog(
                    agentName = agentName,
                    action = "ProviderSuccess",
                    detail = "Received response from ${provider.displayName} in ${response.latencyMs}ms. Tokens: ${response.promptTokens}+${response.completionTokens}",
                    level = StepLogLevel.SUCCESS,
                    taskId = taskId
                )
                return@withContext response
            } else {
                // Log failure and update circuit breaker
                val errMsg = response.errorMessage ?: "Unknown error"
                lastError = errMsg
                health.consecutiveErrors++
                health.lastErrorTimestamp = now
                health.lastErrorMessage = errMsg

                val isRpmOr429 = errMsg.contains("429") || errMsg.contains("RESOURCE_EXHAUSTED") || errMsg.contains("rate limit", ignoreCase = true)
                if (isRpmOr429 || health.consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    health.isHealthy = false
                }

                agentSoulManager.postStepLog(
                    agentName = agentName,
                    action = "ProviderFailover",
                    detail = "Failed call to ${provider.displayName}: $errMsg. Cascading to next fallback provider...",
                    level = StepLogLevel.WARNING,
                    taskId = taskId
                )
            }
        }

        // If all providers fail, return graceful fallback response
        val fallbackMsg = if (lastError != null) {
            "All configured AI providers are currently unavailable or reached rate limits. Last error: $lastError"
        } else {
            "No AI providers configured with valid API keys or all keys exhausted. Please configure API keys in Settings."
        }
        agentSoulManager.postStepLog(
            agentName = agentName,
            action = "AllProvidersExhausted",
            detail = fallbackMsg,
            level = StepLogLevel.ERROR,
            taskId = taskId
        )

        UnifiedPromptResponse(
            content = "JARVIS Notice: AI Providers are currently unreachable or rate-limited. Running in offline safety mode.",
            provider = ModelProvider.GEMINI,
            modelName = "offline-fallback",
            latencyMs = 0L,
            isSuccess = false,
            errorMessage = fallbackMsg
        )
    }

    /**
     * Returns a snapshot of health status for all providers.
     */
    fun getProviderHealthSnapshot(): Map<ModelProvider, ProviderHealth> {
        return healthMap.toMap()
    }

    /**
     * Tests a specific provider with a lightweight ping and returns latency or error.
     */
    suspend fun testProvider(provider: ModelProvider): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val apiKey = vault.getApiKey(provider).orEmpty()
        val baseUrl = vault.getBaseUrl(provider)
        if (!provider.isLocal && apiKey.isBlank()) {
            return@withContext Pair(false, "No API key configured")
        }

        val testReq = UnifiedPromptRequest(
            messages = listOf(UnifiedMessage(role = MessageRole.USER, content = "Say 'OK' in one word.")),
            maxTokens = 8,
            temperature = 0.1f,
            timeoutSeconds = 8L
        )

        val startTime = System.currentTimeMillis()
        try {
            val response = when (provider) {
                ModelProvider.GEMINI -> GeminiRestClient(apiKey = apiKey, baseUrl = baseUrl).execute(testReq)
                else -> OpenAICompatibleClient(provider = provider, apiKey = apiKey, baseUrl = baseUrl).execute(testReq)
            }
            val elapsed = System.currentTimeMillis() - startTime
            if (response.isSuccess) {
                Pair(true, "${elapsed}ms")
            } else {
                Pair(false, response.errorMessage ?: "Request failed")
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "Connection error")
        }
    }

    private fun getOrCreateHealth(provider: ModelProvider): ProviderHealth {
        return healthMap.computeIfAbsent(provider) {
            ProviderHealth(provider = provider)
        }
    }

    companion object {
        private const val CIRCUIT_BREAKER_COOLDOWN_MS = 60_000L // 1 minute cooldown
        private const val MAX_CONSECUTIVE_ERRORS = 3

        @Volatile
        private var INSTANCE: SmartProviderRouter? = null

        fun getInstance(context: Context): SmartProviderRouter {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SmartProviderRouter(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
