package com.jarvis.assistant.agentic.healing

import android.content.Context
import com.jarvis.assistant.agentic.soul.AgentSoulManager
import com.jarvis.assistant.agentic.soul.StepLogLevel
import kotlinx.coroutines.delay

/**
 * Categorization of runtime agent errors.
 */
enum class FailureCategory {
    UI_ELEMENT_NOT_FOUND,
    APP_NOT_INSTALLED,
    RATE_LIMIT_EXCEEDED,
    SECURITY_PERMISSION_DENIED,
    NETWORK_UNAVAILABLE,
    TOOL_EXECUTION_ERROR,
    UNKNOWN_ANOMALY
}

/**
 * Diagnosis and recovery recommendation.
 */
data class HealingRecommendation(
    val category: FailureCategory,
    val suggestedAction: String,
    val shouldRetry: Boolean,
    val backoffDelayMs: Long = 0L,
    val fallbackStrategy: String? = null
)

/**
 * SelfHealingEngine: Autonomous error detection, diagnostics, and recovery
 * for JARVIS multi-agent workflows.
 */
class SelfHealingEngine private constructor(
    private val context: Context,
    private val agentSoulManager: AgentSoulManager = AgentSoulManager.getInstance(context)
) {
    /**
     * Diagnoses an error and decides on an autonomous recovery strategy.
     */
    fun diagnose(error: Throwable, actionContext: String): HealingRecommendation {
        val msg = error.message.orEmpty()
        val errorClassName = error.javaClass.simpleName

        return when {
            msg.contains("429") || msg.contains("RESOURCE_EXHAUSTED") || msg.contains("rate limit", ignoreCase = true) -> {
                HealingRecommendation(
                    category = FailureCategory.RATE_LIMIT_EXCEEDED,
                    suggestedAction = "Switch to next provider in SmartRouter cascade",
                    shouldRetry = true,
                    backoffDelayMs = 500L,
                    fallbackStrategy = "FAILOVER_PROVIDER"
                )
            }
            msg.contains("ActivityNotFoundException", ignoreCase = true) || msg.contains("No Activity found to handle Intent", ignoreCase = true) -> {
                HealingRecommendation(
                    category = FailureCategory.APP_NOT_INSTALLED,
                    suggestedAction = "Target app not installed. Fallback to web browser or Play Store intent.",
                    shouldRetry = false,
                    fallbackStrategy = "OPEN_WEB_ALTERNATIVE"
                )
            }
            msg.contains("NodeNotFound", ignoreCase = true) || msg.contains("Element not clickable", ignoreCase = true) || msg.contains("Node not found", ignoreCase = true) -> {
                HealingRecommendation(
                    category = FailureCategory.UI_ELEMENT_NOT_FOUND,
                    suggestedAction = "Scroll active screen view and re-query Accessibility node hierarchy.",
                    shouldRetry = true,
                    backoffDelayMs = 1200L,
                    fallbackStrategy = "SCROLL_AND_RETRY"
                )
            }
            msg.contains("SecurityException", ignoreCase = true) || msg.contains("Permission Denial", ignoreCase = true) -> {
                HealingRecommendation(
                    category = FailureCategory.SECURITY_PERMISSION_DENIED,
                    suggestedAction = "Action requires elevated permission. Request permission or use unprivileged Intent.",
                    shouldRetry = false,
                    fallbackStrategy = "REQUEST_USER_PERMISSION"
                )
            }
            msg.contains("SSLException", ignoreCase = true) || msg.contains("SSLHandshakeException", ignoreCase = true) || msg.contains("CertPathValidatorException", ignoreCase = true) -> {
                HealingRecommendation(
                    category = FailureCategory.NETWORK_UNAVAILABLE,
                    suggestedAction = "SSL Handshake failure. Retry or bypass proxy.",
                    shouldRetry = true,
                    backoffDelayMs = 1500L,
                    fallbackStrategy = "RETRY_NETWORK"
                )
            }
            msg.contains("EOFException", ignoreCase = true) || msg.contains("ConnectionReset", ignoreCase = true) || msg.contains("stream was reset", ignoreCase = true) -> {
                HealingRecommendation(
                    category = FailureCategory.NETWORK_UNAVAILABLE,
                    suggestedAction = "Connection reset by peer. Retry connection with clean socket.",
                    shouldRetry = true,
                    backoffDelayMs = 1000L,
                    fallbackStrategy = "RECONNECT_SOCKET"
                )
            }
            msg.contains("UnknownHostException", ignoreCase = true) || msg.contains("SocketTimeoutException", ignoreCase = true) -> {
                HealingRecommendation(
                    category = FailureCategory.NETWORK_UNAVAILABLE,
                    suggestedAction = "Network timeout detected. Retry with exponential backoff or use cached memory.",
                    shouldRetry = true,
                    backoffDelayMs = 2000L,
                    fallbackStrategy = "OFFLINE_CACHE_FALLBACK"
                )
            }
            msg.contains("JsonSyntaxException", ignoreCase = true) || msg.contains("MalformedJsonException", ignoreCase = true) -> {
                HealingRecommendation(
                    category = FailureCategory.TOOL_EXECUTION_ERROR,
                    suggestedAction = "Malformed JSON payload. Re-query with strict JSON output requirement.",
                    shouldRetry = true,
                    backoffDelayMs = 500L,
                    fallbackStrategy = "RETRY_WITH_CLEAN_FORMAT"
                )
            }
            else -> {
                HealingRecommendation(
                    category = FailureCategory.UNKNOWN_ANOMALY,
                    suggestedAction = "Unknown failure in $actionContext ($errorClassName: $msg).",
                    shouldRetry = false,
                    fallbackStrategy = "LOG_AND_ABORT"
                )
            }
        }
    }

    /**
     * Executes an operation with automated self-healing retry logic.
     */
    suspend fun <T> executeWithHealing(
        actionName: String,
        agentName: String = "SelfHealingEngine",
        taskId: String? = null,
        maxRetries: Int = 2,
        block: suspend () -> T
    ): Result<T> {
        var attempt = 0
        var currentDelay = 500L

        while (true) {
            attempt++
            try {
                val result = block()
                if (attempt > 1) {
                    agentSoulManager.postStepLog(
                        agentName = agentName,
                        action = "HealingSuccessful",
                        detail = "Action '$actionName' recovered successfully on attempt #$attempt.",
                        level = StepLogLevel.SUCCESS,
                        taskId = taskId
                    )
                }
                return Result.success(result)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val diagnosis = diagnose(e, actionName)
                agentSoulManager.postStepLog(
                    agentName = agentName,
                    action = "SelfHealingDiagnosis",
                    detail = "Attempt #$attempt failed for '$actionName'. Category: ${diagnosis.category}. Suggested: ${diagnosis.suggestedAction}",
                    level = StepLogLevel.WARNING,
                    taskId = taskId
                )

                if (diagnosis.shouldRetry && attempt <= maxRetries) {
                    val waitTime = if (diagnosis.backoffDelayMs > 0) diagnosis.backoffDelayMs else currentDelay
                    delay(waitTime)
                    currentDelay *= 2
                } else {
                    agentSoulManager.postStepLog(
                        agentName = agentName,
                        action = "SelfHealingExhausted",
                        detail = "All retry and healing strategies exhausted for '$actionName'. Error: ${e.message}",
                        level = StepLogLevel.ERROR,
                        taskId = taskId
                    )
                    return Result.failure(e)
                }
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: SelfHealingEngine? = null

        fun getInstance(context: Context): SelfHealingEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SelfHealingEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
