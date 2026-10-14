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
}
