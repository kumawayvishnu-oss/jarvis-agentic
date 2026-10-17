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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * ManagerAgent: The supreme orchestrator of the JARVIS Multi-Agent Swarm.
 * Features:
 * 1. Probabilistic Pre-Execution Planner (`buildProbabilisticPlan`) — evaluates primary paths,
 *    failure probabilities (RAM, Network, App UI, API rate limits), and multi-tier fallback chains.
 * 2. Hindi/Hinglish + English Multi-Task Decomposition (`decomposeGoal`) — splits compound requests
 *    (`"aur"`, `"saath mein"`, `"phir"`, `"uske baad"`, `"and"`, `";"`) and executes independent
 *    tasks simultaneously in parallel via `supervisorScope`.
 * 3. Non-blocking Background Mission Queue (`launchBackgroundMission`) — runs coding, terminal downloads,
 *    and anonymous web research 100% in the background without interrupting foreground voice/chat.
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

    data class StageProbabilityPlan(
        val stageIndex: Int,
        val subGoal: String,
        val primarySpecialist: String,
        val estimatedSuccessProbPercent: Int,
        val identifiedRisks: List<String>,
        val fallbackChain: String
    )

    data class ProbabilisticExecutionPlan(
        val originalGoal: String,
        val isParallelExecution: Boolean,
        val stages: List<StageProbabilityPlan>,
        val hardwareSummary: String
    ) {
        fun toCompactSummary(): String = buildString {
            appendLine("🧠 [PROBABILISTIC PRE-EXECUTION PLAN • Mode=${if (isParallelExecution) "PARALLEL SWARM" else "SEQUENTIAL PIPELINE"} | $hardwareSummary]")
            stages.forEach { s ->
                appendLine("  • Stage ${s.stageIndex}: '${s.subGoal}' -> ${s.primarySpecialist} (Success Prob: ${s.estimatedSuccessProbPercent}% | Risks: ${s.identifiedRisks.joinToString(", ")} | Fallback: ${s.fallbackChain})")
            }
        }.trim()
    }

    override val id: String = "agent_manager"
    override val name: String = "ManagerAgent"
    override val role: String = "Supreme Swarm Orchestrator & Probabilistic Planner"
    override val description: String = "Builds probabilistic execution plans, runs parallel multi-agent tasks, and manages background missions"

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeBackgroundMissions = ConcurrentHashMap<String, String>()

    @Volatile
    var currentState: AgentState = AgentState.IDLE

    @Volatile
    var currentGoalText: String? = null

    @Volatile
    var lastActionSummary: String = "Standby"

    @Volatile
    var lastActiveTime: Long = System.currentTimeMillis()

    override fun getLiveStatus(): AgentLiveStatus {
        val bgCount = activeBackgroundMissions.size
        val summary = if (bgCount > 0) "$lastActionSummary ($bgCount Background Missions Active)" else lastActionSummary
        return AgentLiveStatus(
            id = id,
            name = name,
            role = role,
            description = description,
            state = if (bgCount > 0 && currentState == AgentState.IDLE) AgentState.WORKING else currentState,
            currentGoal = currentGoalText ?: activeBackgroundMissions.values.firstOrNull(),
            lastAction = summary,
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
     * Launches a mission 100% asynchronously in the background (`backgroundScope`)
     * so foreground voice/chat is never blocked while terminal downloads, coding, or
     * deep web research run in the background.
     */
    fun launchBackgroundMission(
        userGoal: String,
        onComplete: ((String) -> Unit)? = null
    ): String {
        val missionId = "bg_${System.currentTimeMillis() % 100000}"
        activeBackgroundMissions[missionId] = userGoal
        lastActionSummary = "Background Mission Launched: ${userGoal.take(32)}"
        lastActiveTime = System.currentTimeMillis()

        backgroundScope.launch {
            try {
                val result = executeGoal(userGoal)
                onComplete?.invoke(result)
            } catch (e: Exception) {
                onComplete?.invoke("Background mission error: ${e.message}")
            } finally {
                activeBackgroundMissions.remove(missionId)
            }
        }
        return "⚡ Background Mission [$missionId] started in parallel for: '$userGoal'. Main aapko background me execute karke update dunga."
    }

    fun getActiveBackgroundMissions(): Map<String, String> = activeBackgroundMissions.toMap()

    /**
     * Builds a complete Probabilistic Pre-Execution Plan before running any task.
     * Evaluates all probabilities (RAM, Network, App availability, API rate limits)
     * and defines fallback paths for every sub-goal.
     */
    fun buildProbabilisticPlan(userGoal: String): ProbabilisticExecutionPlan {
        val resourceEngine = com.jarvis.assistant.agentic.healing.HardwareResourceAwareEngine.getInstance(context)
        val hw = resourceEngine.inspectHardware()
        val subGoals = decomposeGoal(userGoal)

        val sequentialMarkers = listOf(" then ", " ke baad ", " uske baad ", " phir ", " fir ", " after that ")
        val hasSequentialDependency = sequentialMarkers.any { userGoal.contains(it, ignoreCase = true) }
        val isParallel = subGoals.size > 1 && !hasSequentialDependency

        val stages = subGoals.mapIndexed { idx, sg ->
            val specialist = resolveSpecialist(sg)
            val risks = mutableListOf<String>()
            var prob = 94

            if (!hw.hasInternet && (specialist.id == "agent_search" || specialist.id == "agent_cognitive")) {
                risks.add("Offline/Network Drop (35%)")
                prob -= 25
            } else {
                risks.add("API 429 Rate-Limit (8% -> Auto-Cascades 15 Providers)")
            }

            if (hw.isLowMemory) {
                risks.add("4GB RAM Pressure (${hw.availableRamMb}MB free -> Compact Mode)")
                prob -= 6
            }

            if (specialist.id == "agent_navigator" || specialist.id == "agent_device") {
                risks.add("Target App/UI State Variance (10%)")
                prob -= 4
            }

            if (specialist.id == "agent_shell_coding") {
                risks.add("Missing Native Binary (5% -> Embedded JVM Polyfill Active)")
            }

            val fallbackChain = when (specialist.id) {
                "agent_search" -> "DuckDuckGo API -> DDG Lite -> Wikipedia -> BuiltInChromeEngine (100% Anonymous Background)"
                "agent_shell_coding" -> "Embedded Linux + Native Polyfills (curl/git/agy) -> Termux Bridge -> Autonomous HTML5 Builder"
                "agent_navigator", "agent_device" -> "Tier 1 Deep Intent -> Tier 2 Accessibility Tree -> Tier 3 Shizuku -> Background Web Research"
                else -> "15-Provider Cascade (Gemini -> Groq -> GLM Free -> SiliconFlow -> NVIDIA -> Pollinations Zero-Key)"
            }

            StageProbabilityPlan(
                stageIndex = idx + 1,
                subGoal = sg,
                primarySpecialist = specialist.name,
                estimatedSuccessProbPercent = prob.coerceIn(55, 98),
                identifiedRisks = risks,
                fallbackChain = fallbackChain
            )
        }

        return ProbabilisticExecutionPlan(
            originalGoal = userGoal,
            isParallelExecution = isParallel,
            stages = stages,
            hardwareSummary = "RAM=${hw.availableRamMb}MB/${hw.totalRamMb}MB free, Storage=${hw.freeStorageMb}MB, Net=${if (hw.hasInternet) "ONLINE" else "OFFLINE"}"
        )
    }

    /**
     * Executes an autonomous workflow for a user request with Probabilistic Pre-Planning
     * and Parallel Swarm Execution.
     */
    suspend fun executeGoal(userGoal: String): String = withContext(Dispatchers.Default) {
        // If user explicitly asked to run in background without waiting, dispatch to background queue
        val lowerGoal = userGoal.lowercase()
        if ((lowerGoal.startsWith("background me ") || lowerGoal.startsWith("run in background ")) &&
            !userGoal.contains("[BG_RUNNING]")
        ) {
            val cleanBgGoal = userGoal
                .replace(Regex("(?i)^(background me|run in background)\\s+"), "")
                .trim() + " [BG_RUNNING]"
            return@withContext launchBackgroundMission(cleanBgGoal)
        }

        val cleanGoal = userGoal.replace("[BG_RUNNING]", "").trim()
        currentState = AgentState.WORKING
        currentGoalText = cleanGoal
        lastActionSummary = "Planning probabilities: '${cleanGoal.take(30)}'"
        lastActiveTime = System.currentTimeMillis()

        val taskId = agentSoulManager.createTask(
            title = cleanGoal.take(50),
            description = cleanGoal,
            initiator = "User",
            assignedTo = "ManagerAgent",
            priority = TaskPriority.HIGH
        )

        agentSoulManager.updateTaskStatus(taskId, TaskStatus.IN_PROGRESS)
        agentSoulManager.postStepLog(
            agentName = "ManagerAgent",
            action = "GoalReceived",
            detail = "Starting autonomous execution for: '$cleanGoal'",
            level = StepLogLevel.INFO,
            taskId = taskId
        )

        // 0. Strict Safety Confirmation Gate
        val resourceEngine = com.jarvis.assistant.agentic.healing.HardwareResourceAwareEngine.getInstance(context)
        val safetyVerdict = resourceEngine.evaluateSafetyGate(cleanGoal)
        if (!safetyVerdict.allowedImmediately && safetyVerdict.requiresConfirmation && !cleanGoal.contains("[CONFIRMED]", ignoreCase = true)) {
            val confirmMsg = "⚠️ ${safetyVerdict.reason}\nLakshya: '$cleanGoal'\nKripya confirm karein ('Haan confirm' bolein ya likhein)."
            agentSoulManager.updateTaskStatus(taskId, TaskStatus.PENDING, confirmMsg)
            currentState = AgentState.IDLE
            return@withContext confirmMsg
        }

        // 1. Build 4-tier Shared AgentContext (User SOUL + Agent SOUL + Episodic + Self-Awareness Manifest)
        val agentCtx = com.jarvis.assistant.agentic.soul.AgentContext.getInstance(context)
        val snapshot = agentCtx.buildSnapshot(cleanGoal)
        val userContext = snapshot.toCompactPromptBlock()
        val healingEngine = com.jarvis.assistant.agentic.healing.SelfHealingEngine.getInstance(context)

        // 2. Build Probabilistic Pre-Execution Plan ("pahle planning karega, har probability samjhega")
        val probPlan = buildProbabilisticPlan(cleanGoal)
        val planSummary = probPlan.toCompactSummary()
        agentSoulManager.postToBlackboard(taskId, "probabilistic_plan", planSummary)

        agentSoulManager.postStepLog(
            agentName = "ManagerAgent",
            action = "ProbabilisticPlanBuilt",
            detail = planSummary,
            level = StepLogLevel.INFO,
            taskId = taskId
        )

        val subGoals = probPlan.stages.map { it.subGoal }
        val stepOutputs = Collections.synchronizedList(mutableListOf<String>())
        var overallSuccess = true

        suspend fun runSingleSubGoal(stagePlan: StageProbabilityPlan): Boolean {
            val stageNum = stagePlan.stageIndex
            val subGoal = stagePlan.subGoal
            val specialist = resolveSpecialist(subGoal)
            lastActionSummary = "Stage $stageNum/${subGoals.size}: ${specialist.name} (${stagePlan.estimatedSuccessProbPercent}%)"
            lastActiveTime = System.currentTimeMillis()

            interCom.sendMessage(
                sender = "ManagerAgent",
                target = specialist.name,
                topic = "TaskDelegation",
                payload = "$subGoal | PlanFallback=${stagePlan.fallbackChain}",
                taskId = taskId
            )

            val inputData = mutableMapOf<String, Any>(
                "dualSoulContext" to userContext,
                "probabilisticPlan" to planSummary
            )
            if (stepOutputs.isNotEmpty()) {
                inputData["previousOutputs"] = stepOutputs.toList()
            }

            // Tier 1 + Tier 2 Self-healing execution (Max 3 tries)
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
                // Tier 3 Fallback: If device/navigator/search failed, try Autonomous Terminal / Dynamic Agent
                agentSoulManager.postStepLog(
                    agentName = "ManagerAgent",
                    action = "DynamicAgentFallback",
                    detail = "${specialist.name} failed on '$subGoal'. Triggering fallback chain (${stagePlan.fallbackChain})...",
                    level = StepLogLevel.WARNING,
                    taskId = taskId
                )
                val dynamicRecoveryAgent = dynamicFactory.synthesizeAgentForGoal(subGoal)
                dynamicRecoveryAgent.executeTask(subGoal, taskId, inputData)
            }

            stepOutputs.add("[Stage $stageNum • ${finalStepResult.agentName}]: ${finalStepResult.output}")
            return finalStepResult.isSuccess
        }

        // 3. Execute Stages (Parallel Swarm via supervisorScope OR Sequential Pipeline)
        if (probPlan.isParallelExecution) {
            val results: List<Boolean> = supervisorScope {
                probPlan.stages.map { stagePlan ->
                    async(Dispatchers.IO) {
                        runCatching { runSingleSubGoal(stagePlan) }.getOrDefault(false)
                    }
                }.awaitAll()
            }
            overallSuccess = results.any { it }
        } else {
            for (stagePlan in probPlan.stages) {
                val ok = runCatching { runSingleSubGoal(stagePlan) }.getOrDefault(false)
                if (!ok) {
                    overallSuccess = false
                    if (probPlan.stages.size == 1) break
                }
            }
        }

        // 4. Synthesize unified response adapted to user's query tone & User SOUL
        lastActionSummary = "Synthesizing multi-agent outputs"
        lastActiveTime = System.currentTimeMillis()
        val accumulatedOutput = stepOutputs.joinToString("\n\n")
        val finalOutput = if (overallSuccess || stepOutputs.isNotEmpty()) {
            val synthesisPrompt = buildString {
                append("User requested: $cleanGoal\n\n")
                append("Probabilistic Plan Executed:\n$planSummary\n\n")
                append("Execution Results from Swarm:\n$accumulatedOutput\n\n")
                append("$userContext\n\n")
                append("Response Style Instruction: ${snapshot.detectedQueryTone.instruction}\n")
                append("Synthesize a clear, helpful response in Hindi/Hinglish (or user's language) confirming all completed tasks and key findings.")
            }

            val request = UnifiedPromptRequest(
                systemInstruction = "You are JARVIS, created and customized by your owner Lakki. You are a self-aware, multi-agent autonomous Android super-assistant. Respond naturally in Hinglish/Hindi.",
                messages = listOf(UnifiedMessage(role = MessageRole.USER, content = synthesisPrompt)),
                temperature = 0.35f,
                maxTokens = 450
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
            "I encountered a difficulty executing that request after self-healing attempts:\n$accumulatedOutput"
        }

        currentState = if (overallSuccess) AgentState.IDLE else AgentState.ERROR
        currentGoalText = null
        lastActionSummary = if (overallSuccess) "Mission completed (${probPlan.stages.size} stages)" else "Mission failed"
        lastActiveTime = System.currentTimeMillis()

        agentSoulManager.updateTaskStatus(
            taskId,
            if (overallSuccess) TaskStatus.COMPLETED else TaskStatus.FAILED,
            finalOutput
        )

        agentSoulManager.postStepLog(
            agentName = "ManagerAgent",
            action = "WorkflowComplete",
            detail = "Autonomous task completed (${probPlan.stages.size} stage(s)).",
            level = if (overallSuccess) StepLogLevel.SUCCESS else StepLogLevel.ERROR,
            taskId = taskId
        )

        return@withContext finalOutput
    }

    private fun resolveSpecialist(goal: String): BaseSpecialistAgent {
        val lower = goal.lowercase().trim()
        val defaultAgent = specialistPool.findAgentForGoal(goal)

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
     * Decomposes compound multi-action sentences in Hindi/Hinglish and English into sub-goals.
     * Supports: "aur", "saath mein", "sath me", "saath hi", "uske baad", "ke baad", "phir",
     * "and then", "then", "after that", "and also", "also", ";".
     */
    fun decomposeGoal(goal: String): List<String> {
        val delimiters = listOf(
            " and then ",
            " then ",
            " after that ",
            " and also ",
            " uske baad ",
            " ke baad ",
            " saath mein ",
            " sath me ",
            " saath hi ",
            " aur phir ",
            " aur fir ",
            " phir ",
            " aur ",
            " and ",
            ";"
        )
        var subGoals = listOf(goal.trim())

        for (delimiter in delimiters) {
            val nextList = mutableListOf<String>()
            for (item in subGoals) {
                if (item.contains(delimiter, ignoreCase = true)) {
                    val parts = item.split(Regex(Regex.escape(delimiter), RegexOption.IGNORE_CASE))
                    // Only split on " aur " or " and " if each side has at least 2 words (avoids splitting "bread and butter" or "ram aur shyam")
                    val validSplit = if (delimiter.trim() == "aur" || delimiter.trim() == "and") {
                        parts.size > 1 && parts.all { p -> p.trim().split(Regex("\\s+")).size >= 2 }
                    } else {
                        parts.size > 1
                    }
                    if (validSplit) {
                        parts.filter { it.isNotBlank() }.forEach { nextList.add(it.trim()) }
                    } else {
                        nextList.add(item)
                    }
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
