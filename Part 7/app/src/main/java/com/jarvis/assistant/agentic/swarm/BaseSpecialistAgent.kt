package com.jarvis.assistant.agentic.swarm

import android.content.Context
import com.jarvis.assistant.agentic.soul.AgentContext
import com.jarvis.assistant.agentic.soul.AgentSoulManager
import com.jarvis.assistant.agentic.soul.StepLogLevel
import com.jarvis.assistant.agentic.soul.UserSoulManager

/**
 * Result returned by an agent upon task execution.
 */
data class AgentExecutionResult(
    val agentId: String,
    val agentName: String,
    val isSuccess: Boolean,
    val output: String,
    val artifacts: Map<String, String> = emptyMap(),
    val error: String? = null
)

/**
 * BaseSpecialistAgent: Abstract contract for all specialized sub-agents in JARVIS.
 * Provides unified access to User SOUL, Agent SOUL blackboard, shared AgentContext,
 * Inter-Agent Communication (InterCom), and structured step reporting.
 */
abstract class BaseSpecialistAgent(
    override val id: String,
    override val name: String,
    override val role: String,
    override val description: String,
    val systemPrompt: String,
    protected val context: Context,
    protected val agentSoulManager: AgentSoulManager = AgentSoulManager.getInstance(context),
    protected val userSoulManager: UserSoulManager = UserSoulManager.getInstance(context),
    protected val agentContext: AgentContext = AgentContext.getInstance(context)
) : Agent {
    protected val interCom: AgentInterCom by lazy { AgentInterCom.getInstance(context) }

    suspend fun getDualSoulPromptContext(query: String): String {
        return agentContext.buildSnapshot(query).toCompactPromptBlock()
    }

    @Volatile
    var currentState: AgentState = AgentState.IDLE

    @Volatile
    var currentTaskGoal: String? = null

    @Volatile
    var lastActionName: String = "Standby"

    @Volatile
    var lastActiveTime: Long = System.currentTimeMillis()

    override fun getLiveStatus(): AgentLiveStatus {
        return AgentLiveStatus(
            id = id,
            name = name,
            role = role,
            description = description,
            state = currentState,
            currentGoal = currentTaskGoal,
            lastAction = lastActionName,
            lastActiveTime = lastActiveTime
        )
    }

    fun markWorking(goal: String) {
        currentState = AgentState.WORKING
        currentTaskGoal = goal
        lastActionName = "Executing: ${goal.take(40)}"
        lastActiveTime = System.currentTimeMillis()
    }

    fun markCompleted(summary: String) {
        currentState = AgentState.IDLE
        currentTaskGoal = null
        lastActionName = "Completed: ${summary.take(40)}"
        lastActiveTime = System.currentTimeMillis()
    }

    fun markError(error: String) {
        currentState = AgentState.ERROR
        currentTaskGoal = null
        lastActionName = "Error: ${error.take(40)}"
        lastActiveTime = System.currentTimeMillis()
    }

    /**
     * Executes an assigned task asynchronously.
     */
    abstract override suspend fun executeTask(
        taskGoal: String,
        taskId: String,
        inputData: Map<String, Any>
    ): AgentExecutionResult

    /**
     * Reports an execution step to the shared Agent SOUL blackboard.
     */
    protected suspend fun reportStep(
        action: String,
        detail: String,
        level: StepLogLevel = StepLogLevel.INFO,
        taskId: String? = null
    ) {
        lastActionName = "$action: $detail"
        lastActiveTime = System.currentTimeMillis()
        if (level == StepLogLevel.ERROR) {
            currentState = AgentState.ERROR
        } else if (level == StepLogLevel.SUCCESS) {
            currentState = AgentState.IDLE
        } else {
            currentState = AgentState.WORKING
        }

        agentSoulManager.postStepLog(
            agentName = name,
            action = action,
            detail = detail,
            level = level,
            taskId = taskId
        )
    }

    /**
     * Sends an inter-agent message to a peer or manager agent via InterCom.
     */
    protected suspend fun sendPeerMessage(
        targetAgent: String,
        topic: String,
        payload: String,
        taskId: String? = null
    ) {
        interCom.sendMessage(
            sender = name,
            target = targetAgent,
            topic = topic,
            payload = payload,
            taskId = taskId
        )
    }

    /**
     * Writes an artifact or variable to the mission blackboard.
     */
    protected fun writeBlackboard(taskId: String, key: String, value: Any) {
        agentSoulManager.postToBlackboard(taskId, key, value)
    }

    /**
     * Reads a variable or artifact from the mission blackboard.
     */
    protected fun <T> readBlackboard(taskId: String, key: String, clazz: Class<T>): T? {
        return agentSoulManager.getFromBlackboard(taskId, key, clazz)
    }
}
