package com.jarvis.assistant.agentic.swarm

import android.content.Context
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.agentic.connector.HybridActionDispatcher
import com.jarvis.assistant.agentic.healing.SelfHealingEngine
import com.jarvis.assistant.agentic.search.FreeLiveSearchClient
import com.jarvis.assistant.agentic.soul.StepLogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DeviceSpecialistAgent: Handles 21 built-in device controls & automation.
 */
class DeviceSpecialistAgent(context: Context) : BaseSpecialistAgent(
    id = "agent_device",
    name = "DeviceSpecialist",
    role = "Hardware & Phone Controller",
    description = "Controls volume, camera, connectivity, WhatsApp, YouTube, and phone tools",
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
            val commandResult = app.commandExecutor.execute(taskGoal)
            reportStep("DeviceActionSuccess", "Result: ${commandResult.message}", StepLogLevel.SUCCESS, taskId)
            AgentExecutionResult(
                agentId = id,
                agentName = name,
                isSuccess = commandResult.isSuccess,
                output = commandResult.message
            )
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
 * SearchSpecialistAgent: Conducts live web research without paid APIs.
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
        reportStep("InitiateWebSearch", "Querying web for: $taskGoal", StepLogLevel.INFO, taskId)

        try {
            val results = searchClient.search(taskGoal, maxResults = 5)
            if (results.isEmpty()) {
                reportStep("SearchEmpty", "No live web results found for query.", StepLogLevel.WARNING, taskId)
                return@withContext AgentExecutionResult(
                    agentId = id,
                    agentName = name,
                    isSuccess = true,
                    output = "No specific online results found for '$taskGoal'."
                )
            }

            val summary = results.joinToString("\n\n") { item ->
                "• ${item.title}: ${item.snippet}\n  Source: ${item.url}"
            }

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

        val targetApp = inputData["app"] as? String ?: taskGoal
        val query = inputData["query"] as? String

        val result = actionDispatcher.openAppOrAction(targetApp, query, taskId, name)
        AgentExecutionResult(
            agentId = id,
            agentName = name,
            isSuccess = result.isSuccess,
            output = result.message
        )
    }
}

/**
 * SpecialistAgentPool: Manages the registry of active and built-in specialist agents.
 */
class SpecialistAgentPool private constructor(context: Context) {
    private val pool = mutableMapOf<String, BaseSpecialistAgent>()

    init {
        registerAgent(DeviceSpecialistAgent(context))
        registerAgent(SearchSpecialistAgent(context))
        registerAgent(NavigatorSpecialistAgent(context))
    }

    fun registerAgent(agent: BaseSpecialistAgent) {
        pool[agent.id] = agent
    }

    fun getAgent(agentId: String): BaseSpecialistAgent? {
        return pool[agentId]
    }

    fun getAllAgents(): List<BaseSpecialistAgent> {
        return pool.values.toList()
    }

    fun findAgentForGoal(goal: String): BaseSpecialistAgent {
        val lower = goal.lowercase()
        return when {
            lower.contains("search") || lower.contains("google") || lower.contains("weather") || lower.contains("who is") || lower.contains("what is") -> {
                pool["agent_search"] ?: pool.values.first()
            }
            lower.contains("open") || lower.contains("launch") || lower.contains("instagram") || lower.contains("uber") || lower.contains("click") -> {
                pool["agent_navigator"] ?: pool.values.first()
            }
            else -> {
                pool["agent_device"] ?: pool.values.first()
            }
        }
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
