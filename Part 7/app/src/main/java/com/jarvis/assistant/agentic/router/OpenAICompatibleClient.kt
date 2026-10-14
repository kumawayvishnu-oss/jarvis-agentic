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
    private val client: OkHttpClient = defaultClient
) {
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun execute(request: UnifiedPromptRequest): UnifiedPromptResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val endpointUrl = "${provider.baseUrl}/chat/completions"

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
                    msg.toolCallId?.let { addProperty("tool_call_id", it) }
                }
                messagesArray.add(msgObj)
            }
            add("messages", messagesArray)

            // Add tools if present
            if (request.tools.isNotEmpty()) {
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
                if (provider != ModelProvider.OLLAMA_LOCAL && apiKey.isNotBlank()) {
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
            client.newCall(httpRequest).execute().use { response ->
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

                val parsed = gson.fromJson(responseBody, JsonObject::class.java)
                val choices = parsed.getAsJsonArray("choices")
                if (choices == null || choices.size() == 0) {
                    return@withContext UnifiedPromptResponse(
                        content = "",
                        provider = provider,
                        modelName = provider.defaultModel,
                        latencyMs = duration,
                        isSuccess = false,
                        errorMessage = "Empty choices array in response"
                    )
                }

                val firstChoice = choices[0].asJsonObject
                val messageObj = firstChoice.getAsJsonObject("message")
                val textContent = messageObj?.get("content")?.let {
                    if (it.isJsonNull) "" else it.asString
                }.orEmpty()

                val toolCallsList = mutableListOf<UnifiedToolCall>()
                val toolCallsArr = messageObj?.getAsJsonArray("tool_calls")
                toolCallsArr?.forEach { tcElem ->
                    val tcObj = tcElem.asJsonObject
                    val callId = tcObj.get("id")?.asString.orEmpty()
                    val funcObj = tcObj.getAsJsonObject("function")
                    val fnName = funcObj?.get("name")?.asString.orEmpty()
                    val argsStr = funcObj?.get("arguments")?.asString.orEmpty()
                    if (fnName.isNotBlank()) {
                        toolCallsList.add(UnifiedToolCall(callId, fnName, argsStr))
                    }
                }

                val usageObj = parsed.getAsJsonObject("usage")
                val pTokens = usageObj?.get("prompt_tokens")?.asInt ?: 0
                val cTokens = usageObj?.get("completion_tokens")?.asInt ?: 0

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
