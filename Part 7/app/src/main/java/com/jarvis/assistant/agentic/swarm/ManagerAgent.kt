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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

        // 0. Strict Safety Confirmation Gate (if/else)
        val resourceEngine = com.jarvis.assistant.agentic.healing.HardwareResourceAwareEngine.getInstance(context)
        val safetyVerdict = resourceEngine.evaluateSafetyGate(userGoal)
        if (!safetyVerdict.allowedImmediately && safetyVerdict.requiresConfirmation && !userGoal.contains("[CONFIRMED]", ignoreCase = true)) {
            val confirmMsg = "⚠️ ${safetyVerdict.reason}\nLakshya: '$userGoal'\nKripya confirm karein ('Haan confirm' bolein ya likhein)."
            agentSoulManager.updateTaskStatus(taskId, TaskStatus.PENDING, confirmMsg)
            currentState = AgentState.IDLE
            return@withContext confirmMsg
        }

        // 1. Build 4-tier Shared AgentContext (User SOUL + Agent SOUL + Episodic + Skills)
        val agentCtx = com.jarvis.assistant.agentic.soul.AgentContext.getInstance(context)
        val snapshot = agentCtx.buildSnapshot(userGoal)
        val userContext = snapshot.toCompactPromptBlock()
        val healingEngine = com.jarvis.assistant.agentic.healing.SelfHealingEngine.getInstance(context)

        // 2. Pre-task Planning & Decomposition
        val subGoals = decomposeGoal(userGoal)
        val isParallelEligible = subGoals.size > 1 && !userGoal.contains(" then ", ignoreCase = true) && !userGoal.contains(" ke baad ", ignoreCase = true)
        val stepOutputs = java.util.Collections.synchronizedList(mutableListOf<String>())
        var overallSuccess = true

        agentSoulManager.postStepLog(
            agentName = "ManagerAgent",
            action = "PreExecutionPlan",
            detail = "Plan (${if (isParallelEligible) "PARALLEL" else "SEQUENTIAL"}): ${subGoals.size} stage(s) | Tone=${snapshot.detectedQueryTone.name}",
            level = StepLogLevel.INFO,
            taskId = taskId
        )

        suspend fun runSingleSubGoal(stageNum: Int, subGoal: String): Boolean {
            val specialist = resolveSpecialist(subGoal)
            lastActionSummary = "Stage $stageNum/${subGoals.size}: ${specialist.name}"
            lastActiveTime = System.currentTimeMillis()

            interCom.sendMessage(
                sender = "ManagerAgent",
                target = specialist.name,
                topic = "TaskDelegation",
                payload = subGoal,
                taskId = taskId
            )

            val inputData = mutableMapOf<String, Any>("dualSoulContext" to userContext)
            if (stepOutputs.isNotEmpty()) {
                inputData["previousOutputs"] = stepOutputs.toList()
            }

            // Self-healing execution (Max 3 tries)
            val healedResult = healingEngine.executeWithHealing(
                actionName = "Stage_${stageNum}_${specialist.name}",
                agentName = specialist.name,
                taskId = taskId,
                maxRetries = 2
            ) {
                val res = specialist.executeTask(subGoal, taskId, inputData)
                if (!res.isSuccess) throw RuntimeException(res.error ?: res.output)
                res
            }

            val finalStepResult = healedResult.getOrElse {
                // Specialist failed after self-healing -> dynamically synthesize a new specialist agent!
                agentSoulManager.postStepLog(
                    agentName = "ManagerAgent",
                    action = "DynamicAgentFallback",
                    detail = "${specialist.name} failed on '$subGoal'. Synthesizing new dynamic recovery agent...",
                    level = StepLogLevel.WARNING,
                    taskId = taskId
                )
                val dynamicRecoveryAgent = dynamicFactory.synthesizeAgentForGoal(subGoal)
                dynamicRecoveryAgent.executeTask(subGoal, taskId, inputData)
            }

            stepOutputs.add("${finalStepResult.agentName}: ${finalStepResult.output}")
            return finalStepResult.isSuccess
        }

        if (isParallelEligible) {
            val results: List<Boolean> = coroutineScope {
                subGoals.mapIndexed { idx, sg ->
                    async { runSingleSubGoal(idx + 1, sg) }
                }.awaitAll()
            }
            overallSuccess = results.all { it }
        } else {
            for ((index, subGoal) in subGoals.withIndex()) {
                val ok = runSingleSubGoal(index + 1, subGoal)
                if (!ok) {
                    overallSuccess = false
                    if (subGoals.size == 1) break
                }
            }
        }

        // 3. Synthesize unified response adapted to user's query tone & User SOUL
        lastActionSummary = "Synthesizing multi-agent outputs"
        lastActiveTime = System.currentTimeMillis()
        val accumulatedOutput = stepOutputs.joinToString("\n")
        val finalOutput = if (overallSuccess || stepOutputs.isNotEmpty()) {
            val synthesisPrompt = buildString {
                append("User requested: $userGoal\n\n")
                append("Execution Results from Swarm:\n$accumulatedOutput\n\n")
                append("$userContext\n\n")
                append("Response Style Instruction: ${snapshot.detectedQueryTone.instruction}\n")
                append("Synthesize a response confirming what was done matching the user's language and tone.")
            }

            val request = UnifiedPromptRequest(
                systemInstruction = "You are JARVIS, Tony Stark's sophisticated AI assistant. Follow the detected tone strictly.",
                messages = listOf(UnifiedMessage(role = MessageRole.USER, content = synthesisPrompt)),
                temperature = 0.4f,
                maxTokens = 350
            )

            val preferred = resourceEngine.recommendProvider()
            val synthResponse = router.executeWithFailover(
                request = request,
                preferredProvider = preferred,
                taskId = taskId,
                agentName = "ManagerAgent"
            )
            if (synthResponse.isSuccess && synthResponse.content.isNotBlank()) {
                synthResponse.content
            } else {
                accumulatedOutput
            }
        } else {
            "I encountered a difficulty executing that request after 3 self-healing attempts: $accumulatedOutput"
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
