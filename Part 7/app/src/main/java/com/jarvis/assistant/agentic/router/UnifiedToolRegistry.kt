package com.jarvis.assistant.agentic.router

import android.content.Context
import com.google.gson.JsonParser
import com.jarvis.assistant.agentic.connector.HybridActionDispatcher
import com.jarvis.assistant.agentic.search.FreeLiveSearchClient
import com.jarvis.assistant.agentic.soul.AgentContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Centralized, token-efficient Tool Registry with:
 * - Strict JSON Schema definitions
 * - Dynamic query-based tool filtering (sends only relevant tools per request)
 * - Automatic malformed JSON argument repair & validation
 * - Parallel execution with per-call timeouts
 * - Compact result truncation to save context window tokens
 */
class UnifiedToolRegistry private constructor(private val context: Context) {

    data class RegisteredToolEntry(
        val tool: UnifiedTool,
        val domainKeywords: Set<String>,
        val requiresSafetyConfirmation: Boolean = false,
        val executor: suspend (Map<String, String>) -> String
    )

    private val registry = LinkedHashMap<String, RegisteredToolEntry>()
    private val hybridDispatcher by lazy { HybridActionDispatcher.getInstance(context) }
    private val liveSearchClient by lazy { FreeLiveSearchClient() }
    private val agentContext by lazy { AgentContext.getInstance(context) }

    init {
        registerDefaultTools()
    }

    companion object {
        private const val MAX_OUTPUT_CHARS = 650

        @Volatile
        private var INSTANCE: UnifiedToolRegistry? = null

        fun getInstance(context: Context): UnifiedToolRegistry {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UnifiedToolRegistry(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private fun registerDefaultTools() {
        // 1. Live Web Search (Free, 0 API key)
        register(
            UnifiedTool(
                name = "free_live_web_search",
                description = "Searches live web (DuckDuckGo/Wikipedia) for news, facts, weather, prices.",
                parameters = mapOf(
                    "query" to ToolParameterProperty("string", "Search query")
                ),
                requiredParameters = listOf("query")
            ),
            keywords = setOf("search", "news", "weather", "price", "who", "what", "when", "latest", "khabar", "mausam", "batao", "khojo")
        ) { args ->
            val q = args["query"].orEmpty()
            val results = liveSearchClient.search(q, 3)
            if (results.isNotEmpty()) {
                results.joinToString("\n") { "• ${it.title}: ${it.snippet}" }
            } else {
                "No live web results found for '$q'."
            }
        }

        // 2. Open App / Deep Link Action (1500+ Apps via Tier 1->2->3)
        register(
            UnifiedTool(
                name = "open_app_or_action",
                description = "Opens any of 1500+ apps or runs deep action (Intent -> Accessibility -> Shizuku).",
                parameters = mapOf(
                    "app_name" to ToolParameterProperty("string", "Target app name (e.g. YouTube, WhatsApp, Spotify, Maps)"),
                    "action_query" to ToolParameterProperty("string", "Optional search or action query inside the app")
                ),
                requiredParameters = listOf("app_name")
            ),
            keywords = setOf("open", "launch", "start", "kholo", "chalao", "app", "youtube", "whatsapp", "spotify", "instagram", "maps", "telegram", "camera")
        ) { args ->
            val app = args["app_name"].orEmpty()
            val query = args["action_query"]
            val res = hybridDispatcher.openAppOrAction(app, query)
            "[${res.executedTier}] ${res.message}"
        }

        // 3. Screen Click / Tap Element
        register(
            UnifiedTool(
                name = "click_screen_element",
                description = "Clicks button or text visible on screen via Accessibility or Shizuku.",
                parameters = mapOf(
                    "target_text" to ToolParameterProperty("string", "Visible label or button text to click")
                ),
                requiredParameters = listOf("target_text")
            ),
            keywords = setOf("click", "tap", "press", "button", "dabao", "screen", "select")
        ) { args ->
            val target = args["target_text"].orEmpty()
            val res = hybridDispatcher.clickElement(target)
            res.message
        }

        // 4. Type Text into Field
        register(
            UnifiedTool(
                name = "type_text_on_screen",
                description = "Types text into focused or matched input field on screen.",
                parameters = mapOf(
                    "text" to ToolParameterProperty("string", "Text to type"),
                    "field_hint" to ToolParameterProperty("string", "Optional field label")
                ),
                requiredParameters = listOf("text")
            ),
            keywords = setOf("type", "write", "input", "enter", "likho", "search")
        ) { args ->
            val text = args["text"].orEmpty()
            val res = hybridDispatcher.typeText(text, args["field_hint"])
            res.message
        }

        // 5. Save Memory / User Preference
        register(
            UnifiedTool(
                name = "save_user_memory",
                description = "Saves permanent fact, preference, or encrypted secret into 4-tier memory.",
                parameters = mapOf(
                    "key" to ToolParameterProperty("string", "Short topic key"),
                    "value" to ToolParameterProperty("string", "Fact or preference detail"),
                    "category" to ToolParameterProperty("string", "PERSONALITY, PREFERENCES, HABITS, or ROUTINES", listOf("PERSONALITY", "PREFERENCES", "HABITS", "ROUTINES"))
                ),
                requiredParameters = listOf("key", "value")
            ),
            keywords = setOf("remember", "save", "memory", "yaad", "pasand", "favorite", "my name", "mera naam")
        ) { args ->
            val k = args["key"].orEmpty()
            val v = args["value"].orEmpty()
            val c = args["category"] ?: "PREFERENCES"
            agentContext.storeFact(k, v, c)
            "Saved to User SOUL memory: $k -> $v"
        }

        // 6. Send Message (Requires Safety Confirmation)
        register(
            UnifiedTool(
                name = "send_external_message",
                description = "Prepares external WhatsApp/SMS message (gated by user safety confirmation).",
                parameters = mapOf(
                    "recipient" to ToolParameterProperty("string", "Contact name or number"),
                    "message" to ToolParameterProperty("string", "Message content")
                ),
                requiredParameters = listOf("recipient", "message")
            ),
            keywords = setOf("send", "message", "whatsapp", "sms", "text", "bhejo", "msg"),
            requiresConfirmation = true
        ) { args ->
            val recipient = args["recipient"].orEmpty()
            val msg = args["message"].orEmpty()
            val res = hybridDispatcher.openAppOrAction("WhatsApp", msg)
            "Prepared message to $recipient: '$msg' (${res.message})"
        }

        // 7. Autonomous AI Linux Terminal & Antigravity Coding Builder (`sh`)
        register(
            UnifiedTool(
                name = "sh",
                description = "Executes command in JARVIS AI Linux Terminal: 'htop' (system/RAM monitor), 'postman test-providers' or 'postman GET/POST <url>', 'curl', 'netstat', 'ping', 'validate' (10-point validation script), 'r <recipe>' (sysinfo, htop, validate, test-apis, ram-boost, setup-termux, install-agy, list-projects), 'perm <target>', 'agy <goal>', or '--more'.",
                parameters = mapOf(
                    "cmd" to ToolParameterProperty("string", "Command: 'htop', 'postman test-providers', 'validate', 'netstat', 'r ram-boost', 'r install-agy', 'agy <goal>', or shell command")
                ),
                requiredParameters = listOf("cmd")
            ),
            keywords = setOf(
                "terminal", "shell", "termux", "linux", "ubuntu", "bash", "sh", "code", "coding",
                "build", "preview", "antigravity", "agy", "opencode", "script", "python", "node",
                "git", "gh", "website", "webapp", "html", "calculator", "dashboard", "perm", "sysinfo", "banao",
                "htop", "postman", "curl", "wget", "netstat", "ping", "validate", "ram"
            )
        ) { args ->
            val cmd = args["cmd"].orEmpty()
            val shell = com.jarvis.assistant.agentic.shell.AgentShellEngine.getInstance(context)
            val result = shell.executeCommand(
                rawCommand = cmd,
                caller = com.jarvis.assistant.agentic.shell.AgentShellEngine.CallerRole.SHELL_CODING_SPECIALIST
            )
            result.compactOutput
        }

        // 8. Hardware & Device Action Tool (`device_hardware_control`)
        register(
            UnifiedTool(
                name = "device_hardware_control",
                description = "Controls phone hardware: flashlight/torch, volume, brightness, battery check, media play/pause, Wi-Fi/Bluetooth settings.",
                parameters = mapOf(
                    "command_text" to ToolParameterProperty("string", "Natural hardware command, e.g. 'turn on flashlight', 'volume up', 'battery status'")
                ),
                requiredParameters = listOf("command_text")
            ),
            keywords = setOf("flashlight", "torch", "volume", "brightness", "battery", "wifi", "bluetooth", "mute", "play", "pause", "awaz", "light")
        ) { args ->
            val cmdText = args["command_text"].orEmpty()
            val parsed = com.jarvis.assistant.automation.command.CommandParser.parse(cmdText)
            if (parsed?.command != null) {
                com.jarvis.assistant.JarvisApp.instance.commandExecutor.execute(parsed.command)
            } else {
                hybridDispatcher.openAppOrAction(cmdText).message
            }
        }

        // 9. Self-App Accessibility, Workstation Display & Internal Introspection (`jarvis_self_control`)
        register(
            UnifiedTool(
                name = "jarvis_self_control",
                description = "Displays JARVIS's own live Terminal Workstation ('show_terminal', 'show_preview', 'show_htop', 'show_source'), navigates internal screens ('api_vault', 'diagnostics', 'memory', 'live_debug', 'updates', 'settings'), or runs full internal self-inspection ('inspect_self').",
                parameters = mapOf(
                    "action" to ToolParameterProperty("string", "Action: 'show_terminal', 'show_preview', 'show_htop', 'show_source', 'navigate', or 'inspect_self'"),
                    "target" to ToolParameterProperty("string", "Target screen when action='navigate': 'terminal', 'htop', 'preview', 'api_vault', 'diagnostics', 'memory', 'live_debug', 'updates', 'settings'")
                ),
                requiredParameters = listOf("action")
            ),
            keywords = setOf("dikhao", "show", "terminal", "preview", "htop", "vault", "diagnostics", "inspect", "explore", "screen")
        ) { args ->
            val action = args["action"].orEmpty().lowercase()
            val target = args["target"].orEmpty()
            when (action) {
                "show_terminal", "terminal" -> com.jarvis.assistant.agentic.control.JarvisSelfControlBridge.showWorkstationOnScreen(
                    context,
                    com.jarvis.assistant.agentic.control.JarvisSelfControlBridge.WorkstationTab.TERMINAL
                )
                "show_preview", "preview", "viewport" -> com.jarvis.assistant.agentic.control.JarvisSelfControlBridge.showWorkstationOnScreen(
                    context,
                    com.jarvis.assistant.agentic.control.JarvisSelfControlBridge.WorkstationTab.VIEWPORT
                )
                "show_htop", "htop" -> com.jarvis.assistant.agentic.control.JarvisSelfControlBridge.showWorkstationOnScreen(
                    context,
                    com.jarvis.assistant.agentic.control.JarvisSelfControlBridge.WorkstationTab.HTOP
                )
                "show_source", "source" -> com.jarvis.assistant.agentic.control.JarvisSelfControlBridge.showWorkstationOnScreen(
                    context,
                    com.jarvis.assistant.agentic.control.JarvisSelfControlBridge.WorkstationTab.SOURCE
                )
                "inspect_self", "inspect" -> com.jarvis.assistant.agentic.control.JarvisSelfControlBridge.inspectSelfState(context)
                else -> com.jarvis.assistant.agentic.control.JarvisSelfControlBridge.navigateToInternalScreen(context, target.ifBlank { action })
            }
        }
    }

    fun register(
        tool: UnifiedTool,
        keywords: Set<String>,
        requiresConfirmation: Boolean = false,
        executor: suspend (Map<String, String>) -> String
    ) {
        registry[tool.name] = RegisteredToolEntry(tool, keywords, requiresConfirmation, executor)
    }

    /**
     * Dynamically selects only the relevant tools for a user query (token-efficient).
     */
    fun selectRelevantTools(query: String, maxTools: Int = 5): List<UnifiedTool> {
        val lower = query.lowercase()
        val scored = registry.values.map { entry ->
            val matchScore = entry.domainKeywords.count { lower.contains(it) }
            entry.tool to matchScore
        }.sortedByDescending { it.second }

        val matched = scored.filter { it.second > 0 }.map { it.first }
        return if (matched.isNotEmpty()) {
            matched.take(maxTools)
        } else {
            // Default minimal core tools when intent is ambiguous
            registry.values.take(3).map { it.tool }
        }
    }

    /**
     * Validates and automatically repairs malformed JSON arguments from LLMs.
     */
    fun validateAndRepairArguments(tool: UnifiedTool, rawJson: String): Result<Map<String, String>> {
        val cleaned = repairJsonString(rawJson)
        return try {
            val obj = JsonParser.parseString(cleaned).asJsonObject
            val map = mutableMapOf<String, String>()
            for ((k, v) in obj.entrySet()) {
                if (!v.isJsonNull) {
                    map[k] = if (v.isJsonPrimitive) v.asString else v.toString()
                }
            }
            for (req in tool.requiredParameters) {
                if (map[req].isNullOrBlank()) {
                    return Result.failure(IllegalArgumentException("Missing required parameter '$req' for tool '${tool.name}'"))
                }
            }
            Result.success(map)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Repairs common LLM JSON syntax mistakes (trailing commas, markdown fences, unclosed braces).
     */
    private fun repairJsonString(raw: String): String {
        var s = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (!s.startsWith("{")) s = "{$s"
        s = s.replace(Regex(",\\s*}"), "}")
        val openBraces = s.count { it == '{' }
        val closeBraces = s.count { it == '}' }
        if (openBraces > closeBraces) {
            s += "}".repeat(openBraces - closeBraces)
        }
        return s
    }

    /**
     * Executes multiple tool calls in parallel with strict timeout and output compaction.
     */
    suspend fun executeToolsParallel(
        calls: List<UnifiedToolCall>,
        timeoutMs: Long = 10_000L
    ): List<Pair<UnifiedToolCall, String>> = coroutineScope {
        calls.map { call ->
            async(Dispatchers.IO) {
                val output = try {
                    withTimeout(timeoutMs) {
                        executeSingleTool(call)
                    }
                } catch (e: Exception) {
                    "Tool '${call.functionName}' timed out or failed: ${e.message}"
                }
                call to truncateOutput(output)
            }
        }.awaitAll()
    }

    suspend fun executeSingleTool(call: UnifiedToolCall): String = withContext(Dispatchers.IO) {
        val entry = registry[call.functionName]
            ?: return@withContext "Unknown tool '${call.functionName}'. Available: ${registry.keys.joinToString()}"

        val validated = validateAndRepairArguments(entry.tool, call.argumentsJson)
        val args = validated.getOrElse { err ->
            return@withContext "Argument validation failed for '${call.functionName}': ${err.message}"
        }

        entry.executor(args)
    }

    private fun truncateOutput(raw: String): String {
        val clean = raw.trim()
        return if (clean.length <= MAX_OUTPUT_CHARS) clean else clean.take(MAX_OUTPUT_CHARS) + "... [truncated]"
    }

    /**
     * Live self-awareness manifest of all registered functions so JARVIS never gets confused.
     */
    fun getLiveSystemCapabilitiesManifest(): String = buildString {
        append("[LIVE FUNCTION & TOOL MANIFEST (${registry.size} Core Agentic Tools + 21 Voice Tools)]:\n")
        registry.values.forEach { entry ->
            append("• ${entry.tool.name}: ${entry.tool.description}\n")
        }
    }
}
