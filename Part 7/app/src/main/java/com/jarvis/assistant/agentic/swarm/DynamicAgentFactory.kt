package com.jarvis.assistant.agentic.swarm

import android.content.Context
import com.jarvis.assistant.agentic.router.MessageRole
import com.jarvis.assistant.agentic.router.SmartProviderRouter
import com.jarvis.assistant.agentic.router.UnifiedMessage
import com.jarvis.assistant.agentic.router.UnifiedPromptRequest
import com.jarvis.assistant.agentic.soul.StepLogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * DynamicSpecialistAgent: An on-demand synthesized specialist agent.
 * Executes purely via prompt/config orchestration and Router calls.
 * Never executes arbitrary binary or shell code.
 */
class DynamicSpecialistAgent(
    id: String,
    name: String,
    role: String,
    description: String,
    systemPrompt: String,
    val requiresConfirmationForSensitiveActions: Boolean = true,
    context: Context,
    private val router: SmartProviderRouter = SmartProviderRouter.getInstance(context)
) : BaseSpecialistAgent(id, name, role, description, systemPrompt, context) {

    override suspend fun executeTask(
        taskGoal: String,
        taskId: String,
        inputData: Map<String, Any>
    ): AgentExecutionResult = withContext(Dispatchers.IO) {
        reportStep("DynamicAgentStart", "Executing task: $taskGoal", StepLogLevel.INFO, taskId)

        // Safety verification: check for sensitive operations requiring confirmation
        val isSensitive = isSensitiveOperation(taskGoal)
        if (isSensitive && requiresConfirmationForSensitiveActions) {
            reportStep(
                action = "AwaitingUserConfirmation",
                detail = "Sensitive action detected ('$taskGoal'). Halting execution until user confirms.",
                level = StepLogLevel.WARNING,
                taskId = taskId
            )
            return@withContext AgentExecutionResult(
                agentId = id,
                agentName = name,
                isSuccess = false,
                output = "Action requires explicit user confirmation before proceeding: '$taskGoal'."
            )
        }

        val request = UnifiedPromptRequest(
            systemInstruction = systemPrompt,
            messages = listOf(
                UnifiedMessage(role = MessageRole.USER, content = taskGoal)
            ),
            temperature = 0.5f,
            maxTokens = 1500
        )

        val response = router.executeWithFailover(request, taskId = taskId, agentName = name)

        if (response.isSuccess) {
            reportStep("DynamicAgentSuccess", "Completed task execution.", StepLogLevel.SUCCESS, taskId)
            AgentExecutionResult(
                agentId = id,
                agentName = name,
                isSuccess = true,
                output = response.content
            )
        } else {
            reportStep("DynamicAgentFailure", "Execution error: ${response.errorMessage}", StepLogLevel.ERROR, taskId)
            AgentExecutionResult(
                agentId = id,
                agentName = name,
                isSuccess = false,
                output = "Error: ${response.errorMessage}",
                error = response.errorMessage
            )
        }
    }

    private fun isSensitiveOperation(goal: String): Boolean {
        val lower = goal.lowercase()
        return lower.contains("pay") ||
                lower.contains("send money") ||
                lower.contains("transfer") ||
                lower.contains("delete") ||
                lower.contains("remove all") ||
                lower.contains("send message to") ||
                lower.contains("post on")
    }
}

/**
 * DynamicAgentFactory: Synthesizes specialized agents on-the-fly.
 * Strictly adheres to prompt/config architecture without arbitrary code execution.
 */
class DynamicAgentFactory private constructor(
    private val context: Context,
    private val agentPool: SpecialistAgentPool = SpecialistAgentPool.getInstance(context)
) {
    /**
     * Synthesizes and registers a new dynamic agent for a specialized goal.
     */
    fun createAgent(
        roleName: String,
        goalDescription: String,
        customInstructions: String? = null
    ): DynamicSpecialistAgent {
        val agentId = "agent_dyn_${UUID.randomUUID().toString().take(8)}"
        val cleanName = roleName.replace(Regex("[^a-zA-Z0-9]"), "") + "Agent"

        val systemPrompt = buildString {
            append("You are $cleanName, a specialized sub-agent in the JARVIS Autonomous Super-System.\n")
            append("Your specific role: $roleName.\n")
            append("Goal: $goalDescription.\n")
            if (!customInstructions.isNullOrBlank()) {
                append("Special instructions: $customInstructions\n")
            }
            append("Guidelines: Provide precise, direct, and actionable solutions. Never make assumptions without verification.")
        }

        val dynamicAgent = DynamicSpecialistAgent(
            id = agentId,
            name = cleanName,
            role = roleName,
            description = goalDescription,
            systemPrompt = systemPrompt,
            requiresConfirmationForSensitiveActions = true,
            context = context
        )

        agentPool.registerAgent(dynamicAgent)
        return dynamicAgent
    }

    companion object {
        @Volatile
        private var INSTANCE: DynamicAgentFactory? = null

        fun getInstance(context: Context): DynamicAgentFactory {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DynamicAgentFactory(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
