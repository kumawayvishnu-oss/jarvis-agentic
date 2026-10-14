package com.jarvis.assistant.agentic.router

import com.google.gson.annotations.SerializedName

/**
 * Unified message role across all providers.
 */
enum class MessageRole {
    @SerializedName("system")
    SYSTEM,
    @SerializedName("user")
    USER,
    @SerializedName("assistant")
    ASSISTANT,
    @SerializedName("tool")
    TOOL
}

/**
 * Standardized message representation.
 */
data class UnifiedMessage(
    val role: MessageRole,
    val content: String,
    val toolCallId: String? = null,
    val name: String? = null
)

/**
 * Standardized tool parameter schema (JSON Schema compatible).
 */
data class ToolParameterProperty(
    val type: String,
    val description: String,
    val enum: List<String>? = null
)

/**
 * Standardized tool definition that all LLM providers can understand.
 */
data class UnifiedTool(
    val name: String,
    val description: String,
    val parameters: Map<String, ToolParameterProperty> = emptyMap(),
    val requiredParameters: List<String> = emptyList()
)

/**
 * Tool call requested by any model provider.
 */
data class UnifiedToolCall(
    val id: String,
    val functionName: String,
    val argumentsJson: String
)

/**
 * Request payload routed to providers.
 */
data class UnifiedPromptRequest(
    val messages: List<UnifiedMessage>,
    val systemInstruction: String? = null,
    val tools: List<UnifiedTool> = emptyList(),
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val timeoutSeconds: Long = 20L
)

/**
 * Response payload returned by the active provider.
 */
data class UnifiedPromptResponse(
    val content: String,
    val toolCalls: List<UnifiedToolCall> = emptyList(),
    val provider: ModelProvider,
    val modelName: String,
    val latencyMs: Long,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val isSuccess: Boolean = true,
    val errorMessage: String? = null
)

/**
 * Live health tracking of a model provider (RPM, circuit breaking, errors).
 */
data class ProviderHealth(
    val provider: ModelProvider,
    @Volatile var isHealthy: Boolean = true,
    @Volatile var consecutiveErrors: Int = 0,
    @Volatile var lastErrorTimestamp: Long = 0L,
    @Volatile var lastErrorMessage: String? = null,
    @Volatile var requestsInCurrentMinute: Int = 0,
    @Volatile var minuteWindowStart: Long = System.currentTimeMillis()
)
