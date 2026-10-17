package com.jarvis.assistant.agentic.router

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * High-performance client for OpenAI-compatible providers:
 * - DeepSeek (V3/R1)
 * - Groq LPU (Ultra-Fast)
 * - OpenRouter
 * - Ollama (Local)
 */
class OpenAICompatibleClient(
    private val provider: ModelProvider,
    private val apiKey: String,
    private val baseUrl: String = provider.baseUrl,
    private val client: OkHttpClient = defaultClient
) {
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun execute(request: UnifiedPromptRequest): UnifiedPromptResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val endpointUrl = "${baseUrl.trimEnd('/')}/chat/completions"

        val effectiveClient = if (request.timeoutSeconds > 0 && request.timeoutSeconds != 30L) {
            client.newBuilder()
                .callTimeout(request.timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(request.timeoutSeconds, TimeUnit.SECONDS)
                .build()
        } else {
            client
        }

        val bodyJson = JsonObject().apply {
            addProperty("model", provider.defaultModel)
            addProperty("temperature", request.temperature)
            addProperty("max_tokens", request.maxTokens)

            val messagesArray = JsonArray()
            // If system instruction is provided, prepend it as system message
            request.systemInstruction?.let { sys ->
                val sysObj = JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", sys)
                }
                messagesArray.add(sysObj)
            }

            request.messages.forEach { msg ->
                val msgObj = JsonObject().apply {
                    addProperty("role", msg.role.name.lowercase())
                    addProperty("content", msg.content)
                    msg.name?.let { addProperty("name", it) }
                    if (msg.role == MessageRole.TOOL) {
                        addProperty("tool_call_id", msg.toolCallId ?: "call_default")
                    } else {
                        msg.toolCallId?.let { addProperty("tool_call_id", it) }
                    }
                }
                messagesArray.add(msgObj)
            }
            add("messages", messagesArray)

            // Add tools if present (skip for zero-key fallback if it prefers plain text/JSON prompt)
            if (request.tools.isNotEmpty() && provider != ModelProvider.POLLINATIONS_FREE) {
                val toolsArray = JsonArray()
                request.tools.forEach { tool ->
                    val toolObj = JsonObject().apply {
                        addProperty("type", "function")
                        val funcObj = JsonObject().apply {
                            addProperty("name", tool.name)
                            addProperty("description", tool.description)

                            val parametersObj = JsonObject().apply {
                                addProperty("type", "object")
                                val propsObj = JsonObject()
                                tool.parameters.forEach { (propName, propDef) ->
                                    val p = JsonObject().apply {
                                        addProperty("type", propDef.type)
                                        addProperty("description", propDef.description)
                                        propDef.enum?.let { enumList ->
                                            val enumArr = JsonArray()
                                            enumList.forEach { enumArr.add(it) }
                                            add("enum", enumArr)
                                        }
                                    }
                                    propsObj.add(propName, p)
                                }
                                add("properties", propsObj)

                                if (tool.requiredParameters.isNotEmpty()) {
                                    val reqArr = JsonArray()
                                    tool.requiredParameters.forEach { reqArr.add(it) }
                                    add("required", reqArr)
                                }
                            }
                            add("parameters", parametersObj)
                        }
                        add("function", funcObj)
                    }
                    toolsArray.add(toolObj)
                }
                add("tools", toolsArray)
                addProperty("tool_choice", "auto")
            }
        }

        val httpRequest = Request.Builder()
            .url(endpointUrl)
            .addHeader("Content-Type", "application/json")
            .apply {
                if (provider.requiresApiKey && apiKey.isNotBlank() && apiKey != "zero-key-provider-ready") {
                    addHeader("Authorization", "Bearer $apiKey")
                }
                if (provider == ModelProvider.OPENROUTER) {
                    addHeader("HTTP-Referer", "https://github.com/jarvis-assistant")
                    addHeader("X-Title", "JARVIS-Android")
                }
            }
            .post(bodyJson.toString().toRequestBody(jsonMediaType))
            .build()

        try {
            effectiveClient.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                val duration = System.currentTimeMillis() - startTime

                if (!response.isSuccessful) {
                    val code = response.code
                    val errorDetail = "HTTP $code: $responseBody"
                    return@withContext UnifiedPromptResponse(
                        content = "",
                        provider = provider,
                        modelName = provider.defaultModel,
                        latencyMs = duration,
                        isSuccess = false,
                        errorMessage = errorDetail
                    )
                }

                val parsed = try {
                    gson.fromJson(responseBody, JsonObject::class.java)
                } catch (e: Exception) {
                    null
                }

                if (parsed == null) {
                    return@withContext UnifiedPromptResponse(
                        content = "",
                        provider = provider,
                        modelName = provider.defaultModel,
                        latencyMs = duration,
                        isSuccess = false,
                        errorMessage = "Invalid JSON response from ${provider.displayName}"
                    )
                }

                val choices = parsed.getAsJsonArray("choices")
                if (choices == null || choices.size() == 0) {
                    return@withContext UnifiedPromptResponse(
                        content = "",
                        provider = provider,
                        modelName = provider.defaultModel,
                        latencyMs = duration,
                        isSuccess = false,
                        errorMessage = "Empty choices array in response from ${provider.displayName}"
                    )
                }

                val firstChoice = choices[0].asJsonObject
                val messageObj = firstChoice.getAsJsonObject("message")
                var textContent = messageObj?.get("content")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()

                // Check for DeepSeek R1 reasoning_content or Pollinations reasoning if content is empty
                if (textContent.isBlank()) {
                    val reasoning = messageObj?.get("reasoning_content")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
                        ?: messageObj?.get("reasoning")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
                    if (!reasoning.isNullOrBlank()) {
                        textContent = reasoning
                    }
                }

                val toolCallsList = mutableListOf<UnifiedToolCall>()
                val toolCallsArr = messageObj?.getAsJsonArray("tool_calls")
                toolCallsArr?.forEach { tcElem ->
                    if (tcElem.isJsonObject) {
                        val tcObj = tcElem.asJsonObject
                        val callId = tcObj.get("id")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()
                        val funcObj = tcObj.getAsJsonObject("function")
                        val fnName = funcObj?.get("name")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()
                        val argsStr = funcObj?.get("arguments")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()
                        if (fnName.isNotBlank()) {
                            toolCallsList.add(UnifiedToolCall(callId, fnName, argsStr))
                        }
                    }
                }

                val usageObj = parsed.getAsJsonObject("usage")
                val pTokens = usageObj?.get("prompt_tokens")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asInt ?: 0
                val cTokens = usageObj?.get("completion_tokens")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asInt ?: 0

                UnifiedPromptResponse(
                    content = textContent,
                    toolCalls = toolCallsList,
                    provider = provider,
                    modelName = provider.defaultModel,
                    latencyMs = duration,
                    promptTokens = pTokens,
                    completionTokens = cTokens,
                    isSuccess = true
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val duration = System.currentTimeMillis() - startTime
            UnifiedPromptResponse(
                content = "",
                provider = provider,
                modelName = provider.defaultModel,
                latencyMs = duration,
                isSuccess = false,
                errorMessage = e.message ?: "Network error calling ${provider.displayName}"
            )
        }
    }

    companion object {
        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
