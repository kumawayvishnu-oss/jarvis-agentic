package com.jarvis.assistant.agentic.soul

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Represents the live shared mission state in "Agent SOUL".
 * All sub-agents and specialist agents observe and update this shared blackboard,
 * ensuring complete synchronization without work duplication or race conditions.
 */
@Entity(tableName = "agent_soul_missions")
data class AgentSoulEntity(
    @PrimaryKey
    val missionId: String = UUID.randomUUID().toString(),
    val goal: String,
    val normalizedGoal: String,
    val status: String = STATUS_PLANNING, // PLANNING, RUNNING, PAUSED, COMPLETED, FAILED, RECOVERING
    val assignedAgentsJson: String = "[\"manager\"]", // JSON array of active agents involved
    val blackboardStateJson: String = "{}", // Shared JSON key-value store accessible to all agents
    val currentStepSummary: String = "Initializing mission...",
    val completedStepsJson: String = "[]",
    val pendingStepsJson: String = "[]",
    val failedStepsJson: String = "[]",
    val totalStepsCount: Int = 0,
    val completedStepsCount: Int = 0,
    val recoveryCount: Int = 0,
    val failureReason: String? = null,
    val finalOutcomeSummary: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
) {
    companion object {
        const val STATUS_PLANNING = "PLANNING"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_PAUSED = "PAUSED"
        const val STATUS_RECOVERING = "RECOVERING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"
    }

    val progressPercent: Int
        get() = if (totalStepsCount > 0) ((completedStepsCount.toFloat() / totalStepsCount.toFloat()) * 100).toInt().coerceIn(0, 100) else 0

    val formattedStartTime: String
        get() {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(startedAt))
        }
}

enum class TaskPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT
}

enum class TaskStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class SoulTask(
    val taskId: String,
    val title: String,
    val description: String,
    val initiator: String = "User",
    val assignedTo: String = "ManagerAgent",
    val priority: TaskPriority = TaskPriority.NORMAL,
    val status: TaskStatus = TaskStatus.IN_PROGRESS,
    val createdAt: Long = System.currentTimeMillis()
)

