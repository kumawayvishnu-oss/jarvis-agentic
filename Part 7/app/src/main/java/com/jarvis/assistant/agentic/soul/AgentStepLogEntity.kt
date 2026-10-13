package com.jarvis.assistant.agentic.soul

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Audit log entry for an individual action dispatched or verified by an agent.
 * Enables self-healing, retrospective analysis, and user transparency.
 */
@Entity(tableName = "agent_soul_step_logs")
data class AgentStepLogEntity(
    @PrimaryKey(autoGenerate = true)
    val logId: Long = 0,
    val missionId: String,
    val stepIndex: Int,
    val agentName: String, // e.g. "ManagerAgent", "UiNavigator", "DeviceOps", "DynamicAgent_X"
    val actionName: String, // Tool called or sub-goal executed
    val target: String = "", // Target app, element label, or URL
    val inputPayload: String = "", // Arguments provided
    val outputPayload: String? = null, // Result returned from tool
    val verified: Boolean = false, // Whether screen signature or assertion confirmed it
    val status: String = "SUCCESS", // SUCCESS, FAILED, RETRIED, RECOVERED
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
}
