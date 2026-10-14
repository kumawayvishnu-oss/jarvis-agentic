package com.jarvis.assistant.agentic.swarm

import android.content.Context
import com.jarvis.assistant.agentic.router.MessageRole
import com.jarvis.assistant.agentic.router.SmartProviderRouter
import com.jarvis.assistant.agentic.router.UnifiedMessage
import com.jarvis.assistant.agentic.router.UnifiedPromptRequest
import com.jarvis.assistant.agentic.soul.AgentSoulManager
import com.jarvis.assistant.agentic.soul.StepLogLevel
import com.jarvis.assistant.agentic.soul.TaskPriority
import com.jarvis.assistant.agentic.soul.TaskStatus
import com.jarvis.assistant.agentic.soul.UserSoulManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ManagerAgent: The supreme orchestrator of the JARVIS Multi-Agent Swarm.
 * Decomposes complex goals, coordinates specialists, and synthesizes unified outputs.
 */
class ManagerAgent private constructor(
    private val context: Context,
    private val agentSoulManager: AgentSoulManager = AgentSoulManager.getInstance(context),
    private val userSoulManager: UserSoulManager = UserSoulManager.getInstance(context),
    private val specialistPool: SpecialistAgentPool = SpecialistAgentPool.getInstance(context),
    private val dynamicFactory: DynamicAgentFactory = DynamicAgentFactory.getInstance(context),
    private val router: SmartProviderRouter = SmartProviderRouter.getInstance(context)
) {

    /**
     * Executes an autonomous workflow for a user request.
     */
    suspend fun executeGoal(userGoal: String): String = withContext(Dispatchers.Default) {
        val taskId = agentSoulManager.createTask(
            title = userGoal.take(50),
            description = userGoal,
            initiator = "User",
            assignedTo = "ManagerAgent",
            priority = TaskPriority.HIGH
        )

        agentSoulManager.updateTaskStatus(taskId, TaskStatus.IN_PROGRESS)
        agentSoulManager.postStepLog(
            agentName = "ManagerAgent",
            action = "GoalReceived",
            detail = "Starting autonomous execution for: '$userGoal'",
            level = StepLogLevel.INFO,
            taskId = taskId
        )

        // 1. Enrich with User SOUL context (habits, preferences)
        val userContext = userSoulManager.buildPromptContext()

        // 2. Select or synthesize specialist
        val specialist = specialistPool.findAgentForGoal(userGoal)
        agentSoulManager.postStepLog(
            agentName = "ManagerAgent",
            action = "AssignSpecialist",
            detail = "Delegating goal to specialist: ${specialist.name} (${specialist.role})",
            level = StepLogLevel.INFO,
            taskId = taskId
        )

        // 3. Execute specialist task
        val specialistResult = specialist.executeTask(userGoal, taskId)

        // 4. Synthesize final answer if needed
        val finalOutput = if (specialistResult.isSuccess) {
            val synthesisPrompt = buildString {
                append("User requested: $userGoal\n")
                append("Specialist (${specialist.name}) executed action with output:\n${specialistResult.output}\n\n")
                if (userContext.isNotBlank()) {
                    append("User Profile Context:\n$userContext\n\n")
                }
                append("Synthesize a concise, polite, JARVIS-style response confirming the action.")
            }

            val request = UnifiedPromptRequest(
                systemInstruction = "You are JARVIS, Tony Stark's sophisticated AI assistant. Be direct, ultra-efficient, and courteous.",
                messages = listOf(UnifiedMessage(role = MessageRole.USER, content = synthesisPrompt)),
                temperature = 0.4f,
                maxTokens = 300
            )

            val synthResponse = router.executeWithFailover(request, taskId = taskId, agentName = "ManagerAgent")
            if (synthResponse.isSuccess && synthResponse.content.isNotBlank()) {
                synthResponse.content
            } else {
                specialistResult.output
            }
        } else {
            "I encountered a difficulty executing that request: ${specialistResult.output}"
        }

        agentSoulManager.updateTaskStatus(
            taskId,
            if (specialistResult.isSuccess) TaskStatus.COMPLETED else TaskStatus.FAILED,
            finalOutput
        )

        agentSoulManager.postStepLog(
            agentName = "ManagerAgent",
            action = "WorkflowComplete",
            detail = "Autonomous task completed.",
            level = if (specialistResult.isSuccess) StepLogLevel.SUCCESS else StepLogLevel.ERROR,
            taskId = taskId
        )

        return@withContext finalOutput
    }

    companion object {
        @Volatile
        private var INSTANCE: ManagerAgent? = null

        fun getInstance(context: Context): ManagerAgent {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ManagerAgent(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
