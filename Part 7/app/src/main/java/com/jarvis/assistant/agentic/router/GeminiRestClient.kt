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
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * GeminiRestClient: Native REST client for Google Gemini 1.5 Flash/Pro with
 * structured tool calling and system instructions.
 */
class GeminiRestClient(
    private val apiKey: String,
    private val baseUrl: String = ModelProvider.GEMINI.baseUrl,
    private val client: OkHttpClient = defaultClient
) {
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun execute(request: UnifiedPromptRequest): UnifiedPromptResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val provider = ModelProvider.GEMINI
        val model = provider.defaultModel

        val endpointUrl = "$baseUrl/models/$model:generateContent?key=$apiKey"

        val effectiveClient = if (request.timeoutSeconds > 0 && request.timeoutSeconds != 30L) {
            client.newBuilder()
                .callTimeout(request.timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(request.timeoutSeconds, TimeUnit.SECONDS)
                .build()
        } else {
            client
        }

        val bodyJson = JsonObject().apply {
            // System instruction
            request.systemInstruction?.let { sys ->
                val sysObj = JsonObject().apply {
                    val partsArr = JsonArray().apply {
                        val part = JsonObject().apply { addProperty("text", sys) }
                        add(part)
                    }
                    add("parts", partsArr)
                }
                add("systemInstruction", sysObj)
            }

            // Contents / messages
            val contentsArr = JsonArray()
            request.messages.forEach { msg ->
                val roleStr = when (msg.role) {
                    MessageRole.USER -> "user"
                    MessageRole.ASSISTANT -> "model"
                    MessageRole.SYSTEM -> "user"
                    MessageRole.TOOL -> "function"
                }

                val contentObj = JsonObject().apply {
                    addProperty("role", roleStr)
                    val partsArr = JsonArray()
                    if (msg.role == MessageRole.TOOL) {
                        val fnResponseObj = JsonObject().apply {
                            addProperty("name", msg.name ?: "function")
                            val respInner = JsonObject().apply {
                                addProperty("content", msg.content)
                            }
                            add("response", respInner)
                        }
                        val part = JsonObject().apply {
                            add("functionResponse", fnResponseObj)
                        }
                        partsArr.add(part)
                    } else {
                        val textPart = JsonObject().apply {
                            addProperty("text", msg.content)
                        }
                        partsArr.add(textPart)
                    }
                    add("parts", partsArr)
                }
                contentsArr.add(contentObj)
            }
            add("contents", contentsArr)

            // Generation config
            val genConfig = JsonObject().apply {
                addProperty("temperature", request.temperature)
                addProperty("maxOutputTokens", request.maxTokens)
            }
            add("generationConfig", genConfig)

            // Tools (Function Declarations)
            if (request.tools.isNotEmpty()) {
                val toolsArr = JsonArray()
                val toolContainer = JsonObject()
                val funcDeclsArr = JsonArray()

                request.tools.forEach { tool ->
                    val decl = JsonObject().apply {
                        addProperty("name", tool.name)
                        addProperty("description", tool.description)

                        val paramsObj = JsonObject().apply {
                            addProperty("type", "OBJECT")
                            val propsObj = JsonObject()
                            tool.parameters.forEach { (pName, pDef) ->
                                val p = JsonObject().apply {
                                    addProperty("type", pDef.type.uppercase())
                                    addProperty("description", pDef.description)
                                    pDef.enum?.let { enumList ->
                                        val enumArr = JsonArray()
                                        enumList.forEach { enumArr.add(it) }
                                        add("enum", enumArr)
                                    }
                                }
                                propsObj.add(pName, p)
                            }
                            add("properties", propsObj)
                            if (tool.requiredParameters.isNotEmpty()) {
                                val reqArr = JsonArray()
                                tool.requiredParameters.forEach { reqArr.add(it) }
                                add("required", reqArr)
                            }
                        }
                        add("parameters", paramsObj)
                    }
                    funcDeclsArr.add(decl)
                }
                toolContainer.add("functionDeclarations", funcDeclsArr)
                toolsArr.add(toolContainer)
                add("tools", toolsArr)
            }
        }

        val httpRequest = Request.Builder()
            .url(endpointUrl)
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody(jsonMediaType))
            .build()

        try {
            effectiveClient.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                val duration = System.currentTimeMillis() - startTime

                if (!response.isSuccessful) {
                    return@withContext UnifiedPromptResponse(
                        content = "",
                        provider = provider,
                        modelName = model,
                        latencyMs = duration,
                        isSuccess = false,
                        errorMessage = "HTTP ${response.code}: $responseBody"
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
                        modelName = model,
                        latencyMs = duration,
                        isSuccess = false,
                        errorMessage = "Invalid JSON response from Gemini API"
                    )
                }

                val candidates = parsed.getAsJsonArray("candidates")
                if (candidates == null || candidates.size() == 0) {
                    val promptFeedback = parsed.getAsJsonObject("promptFeedback")
                    val blockReason = promptFeedback?.get("blockReason")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
                    return@withContext UnifiedPromptResponse(
                        content = "",
                        provider = provider,
                        modelName = model,
                        latencyMs = duration,
                        isSuccess = false,
                        errorMessage = if (blockReason != null) "Blocked by Gemini safety: $blockReason" else "Gemini returned empty candidates"
                    )
                }

                val firstCandidate = candidates[0].asJsonObject
                val contentObj = firstCandidate.getAsJsonObject("content")
                val partsArr = contentObj?.getAsJsonArray("parts")
                val finishReason = firstCandidate.get("finishReason")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

                var textAccumulator = ""
                val toolCallsList = mutableListOf<UnifiedToolCall>()

                partsArr?.forEach { partElem ->
                    if (partElem.isJsonObject) {
                        val partObj = partElem.asJsonObject
                        partObj.get("text")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.let {
                            textAccumulator += it.asString
                        }

                        partObj.getAsJsonObject("functionCall")?.let { fnCallObj ->
                            val fnName = fnCallObj.get("name")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString.orEmpty()
                            val argsObj = fnCallObj.getAsJsonObject("args")
                            val argsJson = argsObj?.toString() ?: "{}"
                            val callId = "call_${UUID.randomUUID().toString().take(8)}"
                            toolCallsList.add(UnifiedToolCall(callId, fnName, argsJson))
                        }
                    }
                }

                if (textAccumulator.isEmpty() && toolCallsList.isEmpty() && finishReason != null && finishReason != "STOP") {
                    return@withContext UnifiedPromptResponse(
                        content = "",
                        provider = provider,
                        modelName = model,
                        latencyMs = duration,
                        isSuccess = false,
                        errorMessage = "Gemini finished with abnormal reason: $finishReason"
                    )
                }

                val usageMetadata = parsed.getAsJsonObject("usageMetadata")
                val promptTokens = usageMetadata?.get("promptTokenCount")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asInt ?: 0
                val compTokens = usageMetadata?.get("candidatesTokenCount")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asInt ?: 0

                UnifiedPromptResponse(
                    content = textAccumulator,
                    toolCalls = toolCallsList,
                    provider = provider,
                    modelName = model,
                    latencyMs = duration,
                    promptTokens = promptTokens,
                    completionTokens = compTokens,
                    isSuccess = true
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val duration = System.currentTimeMillis() - startTime
            UnifiedPromptResponse(
                content = "",
                provider = provider,
                modelName = model,
                latencyMs = duration,
                isSuccess = false,
                errorMessage = e.message ?: "Failed to connect to Gemini API"
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
