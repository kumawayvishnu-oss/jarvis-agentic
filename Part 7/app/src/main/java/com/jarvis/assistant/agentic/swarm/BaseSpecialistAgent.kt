package com.jarvis.assistant.agentic.swarm

import android.content.Context
import com.jarvis.assistant.agentic.soul.AgentSoulManager
import com.jarvis.assistant.agentic.soul.StepLogLevel

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
 * Provides unified access to Agent SOUL blackboard, Inter-Agent Communication (InterCom),
 * and structured step reporting.
 */
abstract class BaseSpecialistAgent(
    val id: String,
    val name: String,
    val role: String,
    val description: String,
    val systemPrompt: String,
    protected val context: Context,
    protected val agentSoulManager: AgentSoulManager = AgentSoulManager.getInstance(context)
) {
    protected val interCom: AgentInterCom by lazy { AgentInterCom.getInstance(context) }

    /**
     * Executes an assigned task asynchronously.
     */
    abstract suspend fun executeTask(
        taskGoal: String,
        taskId: String,
        inputData: Map<String, Any> = emptyMap()
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
