package com.jarvis.assistant.agentic.swarm

import android.content.Context
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.agentic.connector.HybridActionDispatcher
import com.jarvis.assistant.agentic.router.MessageRole
import com.jarvis.assistant.agentic.router.SmartProviderRouter
import com.jarvis.assistant.agentic.router.UnifiedMessage
import com.jarvis.assistant.agentic.router.UnifiedPromptRequest
import com.jarvis.assistant.agentic.search.FreeLiveSearchClient
import com.jarvis.assistant.agentic.soul.StepLogLevel
import com.jarvis.assistant.automation.command.CommandParser
import com.jarvis.assistant.automation.command.JarvisCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * DeviceSpecialistAgent: Handles 21 built-in device controls & hardware automation
 * (flashlight, volume, brightness, media, WhatsApp, phone calls, memory recall).
 */
class DeviceSpecialistAgent(context: Context) : BaseSpecialistAgent(
    id = "agent_device",
    name = "DeviceSpecialist",
    role = "Hardware & Phone Controller",
    description = "Controls volume, camera, flashlight, connectivity, WhatsApp, YouTube, and phone tools",
    systemPrompt = "You are the DeviceSpecialist in JARVIS. You manage phone controls and hardware actions safely.",
    context = context
) {
    override suspend fun executeTask(
        taskGoal: String,
        taskId: String,
        inputData: Map<String, Any>
    ): AgentExecutionResult = withContext(Dispatchers.Default) {
        reportStep("ExecuteDeviceAction", "Processing command: $taskGoal", StepLogLevel.INFO, taskId)

        val app = JarvisApp.instance
        return@withContext try {
            val directCmd = inputData["command"] as? JarvisCommand
            val parsedResult = if (directCmd == null) CommandParser.parse(taskGoal) else null
            val commandToExecute = directCmd ?: parsedResult?.command

            if (commandToExecute != null) {
                val output = app.commandExecutor.execute(commandToExecute)
                reportStep("DeviceActionSuccess", "Executed $commandToExecute: $output", StepLogLevel.SUCCESS, taskId)
                writeBlackboard(taskId, "last_device_action", output)
                AgentExecutionResult(
                    agentId = id,
                    agentName = name,
                    isSuccess = true,
                    output = output
                )
            } else {
                // If direct command was not recognized, check if it's an app open request
                val hybridDispatcher = HybridActionDispatcher.getInstance(context)
                val hybridResult = hybridDispatcher.openAppOrAction(taskGoal, taskId = taskId, agentName = name)
                if (hybridResult.isSuccess) {
                    reportStep("DeviceAppLaunchSuccess", hybridResult.message, StepLogLevel.SUCCESS, taskId)
                    writeBlackboard(taskId, "last_device_action", hybridResult.message)
                    AgentExecutionResult(
                        agentId = id,
                        agentName = name,
                        isSuccess = true,
                        output = hybridResult.message
                    )
                } else {
                    val fallbackMsg = "Device command not recognized for: '$taskGoal'. Supported commands include flashlight, volume, brightness, media playback, WhatsApp, and phone calls."
                    reportStep("DeviceActionUnrecognized", fallbackMsg, StepLogLevel.WARNING, taskId)
                    AgentExecutionResult(
                        agentId = id,
                        agentName = name,
                        isSuccess = false,
                        output = fallbackMsg,
                        error = "Unrecognized command"
                    )
                }
            }
        } catch (e: Exception) {
            reportStep("DeviceActionError", "Failed: ${e.message}", StepLogLevel.ERROR, taskId)
            AgentExecutionResult(
                agentId = id,
                agentName = name,
                isSuccess = false,
                output = "Device control error: ${e.message}",
                error = e.message
            )
        }
    }
}

/**
 * SearchSpecialistAgent: Conducts live web research without paid APIs using DuckDuckGo.
 */
class SearchSpecialistAgent(
    context: Context,
    private val searchClient: FreeLiveSearchClient = FreeLiveSearchClient()
) : BaseSpecialistAgent(
    id = "agent_search",
    name = "SearchSpecialist",
    role = "Live Web Researcher",
    description = "Performs real-time zero-cost internet search and extracts answers",
    systemPrompt = "You are SearchSpecialist in JARVIS. You search the live web and synthesize accurate summaries.",
    context = context
) {
    override suspend fun executeTask(
        taskGoal: String,
        taskId: String,
        inputData: Map<String, Any>
    ): AgentExecutionResult = withContext(Dispatchers.IO) {
        val rawQuery = (inputData["query"] as? String)?.takeIf { it.isNotBlank() } ?: taskGoal
        val cleanQuery = rawQuery
            .replace(Regex("(?i)^(search for|search|google for|google|look up|who is|what is|find out)\\s+"), "")
            .trim()

        reportStep("InitiateWebSearch", "Querying web for: $cleanQuery", StepLogLevel.INFO, taskId)

        try {
            val results = searchClient.search(cleanQuery, maxResults = 5)
            if (results.isEmpty()) {
                reportStep("SearchEmpty", "No live web results found for query.", StepLogLevel.WARNING, taskId)
                return@withContext AgentExecutionResult(
                    agentId = id,
                    agentName = name,
                    isSuccess = true,
                    output = "No specific online results found for '$cleanQuery'."
                )
            }

            val summary = results.joinToString("\n\n") { item ->
                "• ${item.title}: ${item.snippet}\n  Source: ${item.url}"
            }

            writeBlackboard(taskId, "last_search_summary", summary)
            writeBlackboard(taskId, "last_search_query", cleanQuery)

            reportStep("SearchSuccess", "Retrieved ${results.size} web results.", StepLogLevel.SUCCESS, taskId)
            AgentExecutionResult(
                agentId = id,
                agentName = name,
                isSuccess = true,
                output = summary,
                artifacts = results.associate { it.title to it.url }
            )
        } catch (e: Exception) {
            reportStep("SearchError", "Web search failed: ${e.message}", StepLogLevel.ERROR, taskId)
            AgentExecutionResult(
                agentId = id,
                agentName = name,
                isSuccess = false,
                output = "Web search failed: ${e.message}",
                error = e.message
            )
        }
    }
}

/**
 * NavigatorSpecialistAgent: Handles app navigation, deep intents, and accessibility actions.
 */
class NavigatorSpecialistAgent(
    context: Context,
    private val actionDispatcher: HybridActionDispatcher = HybridActionDispatcher.getInstance(context)
) : BaseSpecialistAgent(
    id = "agent_navigator",
    name = "NavigatorSpecialist",
    role = "UI & App Navigator",
    description = "Navigates 1500+ apps, clicks elements, and executes deep links",
    systemPrompt = "You are NavigatorSpecialist in JARVIS. You execute app intents and screen interactions.",
    context = context
) {
    override suspend fun executeTask(
        taskGoal: String,
        taskId: String,
        inputData: Map<String, Any>
    ): AgentExecutionResult = withContext(Dispatchers.Default) {
        reportStep("NavigateApp", "Launching or interacting: $taskGoal", StepLogLevel.INFO, taskId)

        val targetApp = (inputData["app"] as? String) ?: taskGoal
        val query = inputData["query"] as? String

        val result = actionDispatcher.openAppOrAction(targetApp, query, taskId, name)
        writeBlackboard(taskId, "last_navigation_result", result.message)

        if (result.isSuccess) {
            reportStep("NavigatorSuccess", result.message, StepLogLevel.SUCCESS, taskId)
        } else {
            reportStep("NavigatorFailure", result.message, StepLogLevel.WARNING, taskId)
        }

        AgentExecutionResult(
            agentId = id,
            agentName = name,
            isSuccess = result.isSuccess,
            output = result.message
        )
    }
}

/**
 * CognitiveSpecialistAgent: Handles reasoning, math, code analysis, translation,
 * and text transformations using SmartProviderRouter.
 */
class CognitiveSpecialistAgent(
    context: Context,
    private val router: SmartProviderRouter = SmartProviderRouter.getInstance(context)
) : BaseSpecialistAgent(
    id = "agent_cognitive",
    name = "CognitiveSpecialist",
    role = "Reasoning & Knowledge Specialist",
    description = "Solves complex reasoning, math, translations, code review, and factual synthesis",
    systemPrompt = "You are CognitiveSpecialist in JARVIS. You provide accurate, concise, and structured answers to complex reasoning, calculation, and knowledge queries.",
    context = context
) {
    override suspend fun executeTask(
        taskGoal: String,
        taskId: String,
        inputData: Map<String, Any>
    ): AgentExecutionResult = withContext(Dispatchers.IO) {
        reportStep("CognitiveStart", "Analyzing query: $taskGoal", StepLogLevel.INFO, taskId)

        val request = UnifiedPromptRequest(
            systemInstruction = systemPrompt,
            messages = listOf(UnifiedMessage(role = MessageRole.USER, content = taskGoal)),
            temperature = 0.3f,
            maxTokens = 1200
        )

        val response = router.executeWithFailover(request, taskId = taskId, agentName = name)
        if (response.isSuccess) {
            reportStep("CognitiveSuccess", "Analysis complete", StepLogLevel.SUCCESS, taskId)
            writeBlackboard(taskId, "last_cognitive_output", response.content)
            AgentExecutionResult(
                agentId = id,
                agentName = name,
                isSuccess = true,
                output = response.content
            )
        } else {
            reportStep("CognitiveFailure", "Reasoning error: ${response.errorMessage}", StepLogLevel.ERROR, taskId)
            AgentExecutionResult(
                agentId = id,
                agentName = name,
                isSuccess = false,
                output = "Analysis could not be completed: ${response.errorMessage}",
                error = response.errorMessage
            )
        }
    }
}

/**
 * SpecialistAgentPool: Manages the registry of active and built-in specialist agents.
 */
class SpecialistAgentPool private constructor(context: Context) {
    private val pool = ConcurrentHashMap<String, BaseSpecialistAgent>()

    init {
        registerAgent(DeviceSpecialistAgent(context))
        registerAgent(SearchSpecialistAgent(context))
        registerAgent(NavigatorSpecialistAgent(context))
        registerAgent(CognitiveSpecialistAgent(context))
    }

    fun registerAgent(agent: BaseSpecialistAgent) {
        pool[agent.id] = agent
    }

    fun unregisterAgent(agentId: String): BaseSpecialistAgent? {
        return pool.remove(agentId)
    }

    fun getAgent(agentId: String): BaseSpecialistAgent? {
        return pool[agentId]
    }

    fun getAllAgents(): List<BaseSpecialistAgent> {
        return pool.values.toList()
    }

    /**
     * Retrieves the live status of the manager agent and all registered specialists.
     */
    fun getAllAgentStatuses(): List<AgentLiveStatus> {
        val list = mutableListOf<AgentLiveStatus>()
        list.add(ManagerAgent.getInstance(context).getLiveStatus())
        for (agent in pool.values) {
            list.add(agent.getLiveStatus())
        }
        return list
    }

    /**
     * Resolves the best specialist agent for a given goal.
     */
    fun findAgentForGoal(goal: String): BaseSpecialistAgent {
        val lower = goal.lowercase()

        // 1. Check if any dynamically registered agent explicitly matches
        for (agent in pool.values) {
            if (agent.id.startsWith("agent_dyn_")) {
                if (lower.contains(agent.role.lowercase()) || lower.contains(agent.name.lowercase())) {
                    return agent
                }
            }
        }

        // 2. Web search / live research
        if (lower.contains("search") || lower.contains("google") || lower.contains("weather") ||
            lower.contains("who is") || lower.contains("what is") || lower.contains("news") ||
            lower.contains("price of") || lower.contains("score") || lower.contains("look up")
        ) {
            return pool["agent_search"] ?: pool.values.first()
        }

        // 3. UI Navigation / App opening
        if (lower.contains("open") || lower.contains("launch") || lower.contains("instagram") ||
            lower.contains("uber") || lower.contains("click") || lower.contains("scroll") ||
            lower.contains("tap") || lower.contains("whatsapp") && (lower.contains("chat") || lower.contains("status"))
        ) {
            return pool["agent_navigator"] ?: pool.values.first()
        }

        // 4. Hardware / phone controls / media / memory
        if (lower.contains("flash") || lower.contains("torch") || lower.contains("volume") ||
            lower.contains("brightness") || lower.contains("play") || lower.contains("song") ||
            lower.contains("youtube") || lower.contains("call") || lower.contains("battery") ||
            lower.contains("remember") || lower.contains("forget") || lower.contains("bluetooth") ||
            lower.contains("wifi") || lower.contains("pause") || lower.contains("resume")
        ) {
            return pool["agent_device"] ?: pool.values.first()
        }

        // 5. Reasoning / math / translation / coding / advice -> Cognitive Specialist
        return pool["agent_cognitive"] ?: pool["agent_device"] ?: pool.values.first()
    }

    companion object {
        @Volatile
        private var INSTANCE: SpecialistAgentPool? = null

        fun getInstance(context: Context): SpecialistAgentPool {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SpecialistAgentPool(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
