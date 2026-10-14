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
 * Decomposes complex goals, coordinates specialists, manages inter-agent communications,
 * and synthesizes unified outputs.
 */
class ManagerAgent private constructor(
    private val context: Context,
    private val agentSoulManager: AgentSoulManager = AgentSoulManager.getInstance(context),
    private val userSoulManager: UserSoulManager = UserSoulManager.getInstance(context),
    private val specialistPool: SpecialistAgentPool = SpecialistAgentPool.getInstance(context),
    private val dynamicFactory: DynamicAgentFactory = DynamicAgentFactory.getInstance(context),
    private val router: SmartProviderRouter = SmartProviderRouter.getInstance(context),
    private val interCom: AgentInterCom = AgentInterCom.getInstance(context)
) : Agent {

    override val id: String = "agent_manager"
    override val name: String = "ManagerAgent"
    override val role: String = "Supreme Swarm Orchestrator"
    override val description: String = "Decomposes goals, delegates tasks to specialists, and synthesizes unified outputs"

    @Volatile
    var currentState: AgentState = AgentState.IDLE

    @Volatile
    var currentGoalText: String? = null

    @Volatile
    var lastActionSummary: String = "Standby"

    @Volatile
    var lastActiveTime: Long = System.currentTimeMillis()

    override fun getLiveStatus(): AgentLiveStatus {
        return AgentLiveStatus(
            id = id,
            name = name,
            role = role,
            description = description,
            state = currentState,
            currentGoal = currentGoalText,
            lastAction = lastActionSummary,
            lastActiveTime = lastActiveTime
        )
    }

    override suspend fun executeTask(
        taskGoal: String,
        taskId: String,
        inputData: Map<String, Any>
    ): AgentExecutionResult {
        val output = executeGoal(taskGoal)
        return AgentExecutionResult(
            agentId = id,
            agentName = name,
            isSuccess = true,
            output = output
        )
    }

    /**
     * Executes an autonomous workflow for a user request.
     */
    suspend fun executeGoal(userGoal: String): String = withContext(Dispatchers.Default) {
        currentState = AgentState.WORKING
        currentGoalText = userGoal
        lastActionSummary = "Decomposing goal: '${userGoal.take(30)}'"
        lastActiveTime = System.currentTimeMillis()

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

        // 1. Enrich with User SOUL context (habits, preferences, tone)
        val userContext = userSoulManager.buildPromptContext()

        // 2. Goal decomposition: Check if goal is a compound multi-step instruction
        val subGoals = decomposeGoal(userGoal)
        val stepOutputs = mutableListOf<String>()
        var overallSuccess = true

        agentSoulManager.postStepLog(
            agentName = "ManagerAgent",
            action = "GoalDecomposition",
            detail = "Decomposed into ${subGoals.size} actionable stage(s).",
            level = StepLogLevel.INFO,
            taskId = taskId
        )

        for ((index, subGoal) in subGoals.withIndex()) {
            val stageNum = index + 1
            agentSoulManager.postStepLog(
                agentName = "ManagerAgent",
                action = "StageStart",
                detail = "Executing stage $stageNum/${subGoals.size}: '$subGoal'",
                level = StepLogLevel.INFO,
                taskId = taskId
            )

            // Select matching specialist or synthesize dynamic agent on-demand
            val specialist = resolveSpecialist(subGoal)
            lastActionSummary = "Stage $stageNum/${subGoals.size}: Delegating to ${specialist.name}"
            lastActiveTime = System.currentTimeMillis()

            // Announce task delegation on InterCom
            interCom.sendMessage(
                sender = "ManagerAgent",
                target = specialist.name,
                topic = "TaskDelegation",
                payload = subGoal,
                taskId = taskId
            )

            agentSoulManager.postStepLog(
                agentName = "ManagerAgent",
                action = "AssignSpecialist",
                detail = "Delegated to ${specialist.name} (${specialist.role})",
                level = StepLogLevel.INFO,
                taskId = taskId
            )

            // Pass previous step results to subsequent specialists if any
            val inputData = mutableMapOf<String, Any>()
            if (stepOutputs.isNotEmpty()) {
                inputData["previousOutputs"] = stepOutputs.toList()
            }

            val stepResult = specialist.executeTask(subGoal, taskId, inputData)
            stepOutputs.add("${specialist.name}: ${stepResult.output}")

            if (!stepResult.isSuccess) {
                overallSuccess = false
                agentSoulManager.postStepLog(
                    agentName = "ManagerAgent",
                    action = "StageFailed",
                    detail = "Stage $stageNum encountered an issue: ${stepResult.output}",
                    level = StepLogLevel.WARNING,
                    taskId = taskId
                )
                // If it's a critical single-step or first step failure, stop sequence
                if (subGoals.size == 1) break
            }
        }

        // 3. Synthesize unified response taking into account User SOUL context
        lastActionSummary = "Synthesizing multi-agent outputs"
        lastActiveTime = System.currentTimeMillis()
        val accumulatedOutput = stepOutputs.joinToString("\n")
        val finalOutput = if (overallSuccess || stepOutputs.isNotEmpty()) {
            val synthesisPrompt = buildString {
                append("User requested: $userGoal\n\n")
                append("Execution Results from Swarm:\n$accumulatedOutput\n\n")
                if (userContext.isNotBlank()) {
                    append("User Profile Context:\n$userContext\n\n")
                }
                append("Synthesize a concise, polite, JARVIS-style response confirming what was done.")
            }

            val request = UnifiedPromptRequest(
                systemInstruction = "You are JARVIS, Tony Stark's sophisticated AI assistant. Be direct, ultra-efficient, and courteous.",
                messages = listOf(UnifiedMessage(role = MessageRole.USER, content = synthesisPrompt)),
                temperature = 0.4f,
                maxTokens = 350
            )

            val synthResponse = router.executeWithFailover(request, taskId = taskId, agentName = "ManagerAgent")
            if (synthResponse.isSuccess && synthResponse.content.isNotBlank()) {
                synthResponse.content
            } else {
                accumulatedOutput
            }
        } else {
            "I encountered a difficulty executing that request: $accumulatedOutput"
        }

        currentState = if (overallSuccess) AgentState.IDLE else AgentState.ERROR
        currentGoalText = null
        lastActionSummary = if (overallSuccess) "Mission completed" else "Mission failed"
        lastActiveTime = System.currentTimeMillis()

        agentSoulManager.updateTaskStatus(
            taskId,
            if (overallSuccess) TaskStatus.COMPLETED else TaskStatus.FAILED,
            finalOutput
        )

        agentSoulManager.postStepLog(
            agentName = "ManagerAgent",
            action = "WorkflowComplete",
            detail = "Autonomous task completed.",
            level = if (overallSuccess) StepLogLevel.SUCCESS else StepLogLevel.ERROR,
            taskId = taskId
        )

        return@withContext finalOutput
    }

    /**
     * Resolves an existing specialist agent or synthesizes a dynamic one if needed.
     */
    private fun resolveSpecialist(goal: String): BaseSpecialistAgent {
        val lower = goal.lowercase().trim()
        val defaultAgent = specialistPool.findAgentForGoal(goal)

        // If goal involves a very specialized domain not matching built-ins, synthesize on-demand
        val requiresSynthesis = lower.startsWith("synthesize") ||
                lower.startsWith("act as") ||
                (lower.contains("agent") && lower.contains("create"))

        return if (requiresSynthesis) {
            dynamicFactory.synthesizeAgentForGoal(goal)
        } else {
            defaultAgent
        }
    }

    /**
     * Decomposes compound multi-action sentences into sequential sub-goals.
     */
    private fun decomposeGoal(goal: String): List<String> {
        val delimiters = listOf(" and then ", " then ", " after that ", " and also ")
        var subGoals = listOf(goal.trim())

        for (delimiter in delimiters) {
            val nextList = mutableListOf<String>()
            for (item in subGoals) {
                if (item.contains(delimiter, ignoreCase = true)) {
                    val parts = item.split(Regex(Regex.escape(delimiter), RegexOption.IGNORE_CASE))
                    parts.filter { it.isNotBlank() }.forEach { nextList.add(it.trim()) }
                } else {
                    nextList.add(item)
                }
            }
            subGoals = nextList
        }

        return subGoals.ifEmpty { listOf(goal.trim()) }
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
