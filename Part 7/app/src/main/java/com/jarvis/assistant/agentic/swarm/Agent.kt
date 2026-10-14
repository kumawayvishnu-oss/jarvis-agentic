package com.jarvis.assistant.agentic.swarm

/**
 * Real-time operational lifecycle state of an agent in the JARVIS Swarm.
 */
enum class AgentState {
    IDLE,
    WORKING,
    SUCCESS,
    ERROR
}

/**
 * Snapshot of an agent's live identity, current assignment, and recent activity.
 */
data class AgentLiveStatus(
    val id: String,
    val name: String,
    val role: String,
    val description: String,
    val state: AgentState = AgentState.IDLE,
    val currentGoal: String? = null,
    val lastAction: String = "Standby",
    val lastActiveTime: Long = System.currentTimeMillis()
)

/**
 * Universal interface for all autonomous agents in JARVIS.
 * Implemented by ManagerAgent, Built-in Specialists, and Dynamic Agents.
 */
interface Agent {
    val id: String
    val name: String
    val role: String
    val description: String

    suspend fun executeTask(
        taskGoal: String,
        taskId: String,
        inputData: Map<String, Any> = emptyMap()
    ): AgentExecutionResult

    fun getLiveStatus(): AgentLiveStatus
}
