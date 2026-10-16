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

        // Incorporate both User SOUL and Agent SOUL blackboard state + live tool capabilities
        val dualSoulContext = getDualSoulPromptContext(taskGoal)
        val toolRegistry = com.jarvis.assistant.agentic.router.UnifiedToolRegistry.getInstance(context)
        val relevantTools = toolRegistry.selectRelevantTools(taskGoal)
        val enrichedPrompt = buildString {
            append(systemPrompt)
            append("\n\n")
            append(dualSoulContext)
            append("\n")
            append(toolRegistry.getLiveSystemCapabilitiesManifest())
        }

        val request = UnifiedPromptRequest(
            systemInstruction = enrichedPrompt,
            messages = listOf(
                UnifiedMessage(role = MessageRole.USER, content = taskGoal)
            ),
            tools = relevantTools,
            temperature = 0.4f,
            maxTokens = 1500
        )

        val response = router.executeWithFailover(request, taskId = taskId, agentName = name)

        if (response.isSuccess) {
            reportStep("DynamicAgentSuccess", "Completed task execution.", StepLogLevel.SUCCESS, taskId)
            writeBlackboard(taskId, "result_${id}", response.content)
            sendPeerMessage("ManagerAgent", "TaskCompletion", response.content, taskId)
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

    /**
     * Reuses an existing agent if registered for the given role, or creates a new one.
     */
    fun getOrCreateAgent(
        roleName: String,
        goalDescription: String,
        customInstructions: String? = null
    ): DynamicSpecialistAgent {
        val cleanName = roleName.replace(Regex("[^a-zA-Z0-9]"), "") + "Agent"
        val existing = agentPool.getAllAgents().filterIsInstance<DynamicSpecialistAgent>().firstOrNull {
            it.name.equals(cleanName, ignoreCase = true) || it.role.equals(roleName, ignoreCase = true)
        }
        return existing ?: createAgent(roleName, goalDescription, customInstructions)
    }

    /**
     * Infers role and synthesizes a specialist agent on-demand for an arbitrary task goal.
     */
    fun synthesizeAgentForGoal(goalDescription: String): DynamicSpecialistAgent {
        val lower = goalDescription.lowercase()
        val (role, instructions) = when {
            lower.contains("code") || lower.contains("debug") || lower.contains("script") || lower.contains("kotlin") ->
                "CodeEngineer" to "Focus on clean, optimal, error-free code design and debugging."
            lower.contains("calc") || lower.contains("math") || lower.contains("percentage") || lower.contains("formula") ->
                "MathCalculator" to "Perform rigorous numerical and logical calculations with step-by-step verification."
            lower.contains("translate") || lower.contains("meaning in") ->
                "LanguageTranslator" to "Translate with cultural precision, natural tone, and contextual idioms."
            lower.contains("email") || lower.contains("draft") || lower.contains("letter") ->
                "CorrespondenceWriter" to "Draft polished, articulate, and appropriately toned communications."
            else ->
                "AutonomousProblemSolver" to "Deconstruct the goal, verify constraints, and execute the solution methodically."
        }
        return getOrCreateAgent(role, goalDescription, instructions)
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
