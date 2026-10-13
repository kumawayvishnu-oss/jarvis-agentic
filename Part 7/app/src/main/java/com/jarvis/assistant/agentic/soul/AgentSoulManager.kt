package com.jarvis.assistant.agentic.soul

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the "Agent SOUL" — the shared live blackboard where all sub-agents
 * see the single, authoritative picture of ongoing and completed work.
 * Coordinates task handoffs, prevents duplicate effort, and tracks execution history.
 */
class AgentSoulManager(
    private val context: Context,
    private val soulDao: SoulDao,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    companion object {
        private const val TAG = "AgentSoulManager"

        @Volatile
        private var instance: AgentSoulManager? = null

        fun getInstance(context: Context): AgentSoulManager {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val db = SoulDatabase.getInstance(context)
                    AgentSoulManager(context.applicationContext, db.soulDao())
                        .also { instance = it }
                }
            }
        }
    }

    private val gson = Gson()

    // In-memory fast blackboard cache: missionId -> (key -> value)
    private val inMemoryBlackboards = ConcurrentHashMap<String, ConcurrentHashMap<String, Any>>()

    private val _activeMission = MutableStateFlow<AgentSoulEntity?>(null)
    val activeMission: StateFlow<AgentSoulEntity?> = _activeMission.asStateFlow()

    val recentMissionsFlow: Flow<List<AgentSoulEntity>> = soulDao.getRecentMissionsFlow()

    init {
        scope.launch {
            soulDao.getActiveMissionFlow().collect { mission ->
                _activeMission.value = mission
                mission?.let {
                    restoreBlackboard(it.missionId, it.blackboardStateJson)
                }
            }
        }
    }

    /**
     * Starts a new swarm mission and registers it in Agent SOUL.
     */
    suspend fun startMission(
        goal: String,
        assignedAgents: List<String> = listOf("manager", "ui_navigator", "device_ops"),
        plannedSteps: List<String> = emptyList()
    ): AgentSoulEntity {
        val mission = AgentSoulEntity(
            goal = goal,
            normalizedGoal = goal.lowercase().trim(),
            status = AgentSoulEntity.STATUS_RUNNING,
            assignedAgentsJson = gson.toJson(assignedAgents),
            blackboardStateJson = "{}",
            currentStepSummary = plannedSteps.firstOrNull() ?: "Analyzing goal and assigning agents...",
            pendingStepsJson = gson.toJson(plannedSteps),
            totalStepsCount = plannedSteps.size,
            completedStepsCount = 0
        )
        soulDao.insertOrUpdateMission(mission)
        _activeMission.value = mission
        inMemoryBlackboards[mission.missionId] = ConcurrentHashMap()
        Log.d(TAG, "Agent SOUL: Started mission [${mission.missionId}] - '$goal'")
        return mission
    }

    /**
     * Writes a value into the live shared blackboard accessible by all agents.
     */
    fun postToBlackboard(missionId: String, key: String, value: Any) {
        val board = inMemoryBlackboards.getOrPut(missionId) { ConcurrentHashMap() }
        board[key] = value

        scope.launch {
            try {
                val json = gson.toJson(board)
                soulDao.updateMissionBlackboard(missionId, json)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist blackboard state", e)
            }
        }
    }

    /**
     * Reads a value from the live shared blackboard.
     */
    fun <T> getFromBlackboard(missionId: String, key: String, clazz: Class<T>): T? {
        val board = inMemoryBlackboards[missionId] ?: return null
        val raw = board[key] ?: return null
        return try {
            if (clazz.isInstance(raw)) {
                clazz.cast(raw)
            } else {
                val json = gson.toJson(raw)
                gson.fromJson(json, clazz)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Records an executed step log from any specialist agent in the swarm.
     */
    suspend fun recordStepLog(
        missionId: String,
        stepIndex: Int,
        agentName: String,
        actionName: String,
        target: String,
        inputPayload: String,
        outputPayload: String? = null,
        verified: Boolean = true,
        status: String = "SUCCESS",
        errorMessage: String? = null
    ) {
        val log = AgentStepLogEntity(
            missionId = missionId,
            stepIndex = stepIndex,
            agentName = agentName,
            actionName = actionName,
            target = target,
            inputPayload = inputPayload,
            outputPayload = outputPayload,
            verified = verified,
            status = status,
            errorMessage = errorMessage
        )
        soulDao.insertStepLog(log)
    }

    /**
     * Marks a step as completed and updates mission progress in Agent SOUL.
     */
    suspend fun markStepCompleted(
        missionId: String,
        completedStep: String,
        nextStep: String?
    ) {
        val current = soulDao.getMissionById(missionId) ?: return

        val completedType = object : TypeToken<MutableList<String>>() {}.type
        val completedList: MutableList<String> = try {
            gson.fromJson(current.completedStepsJson, completedType) ?: mutableListOf()
        } catch (e: Exception) { mutableListOf() }

        val pendingType = object : TypeToken<MutableList<String>>() {}.type
        val pendingList: MutableList<String> = try {
            gson.fromJson(current.pendingStepsJson, pendingType) ?: mutableListOf()
        } catch (e: Exception) { mutableListOf() }

        completedList.add(completedStep)
        pendingList.remove(completedStep)

        val updated = current.copy(
            completedStepsJson = gson.toJson(completedList),
            pendingStepsJson = gson.toJson(pendingList),
            completedStepsCount = completedList.size,
            currentStepSummary = nextStep ?: "Wrapping up mission...",
            updatedAt = System.currentTimeMillis()
        )
        soulDao.insertOrUpdateMission(updated)
        _activeMission.value = updated
    }

    /**
     * Concludes the mission with verified success.
     */
    suspend fun completeMission(missionId: String, outcomeSummary: String) {
        val current = soulDao.getMissionById(missionId) ?: return
        val updated = current.copy(
            status = AgentSoulEntity.STATUS_COMPLETED,
            finalOutcomeSummary = outcomeSummary,
            currentStepSummary = "Mission Completed Successfully",
            completedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        soulDao.insertOrUpdateMission(updated)
        _activeMission.value = null
        inMemoryBlackboards.remove(missionId)
        Log.d(TAG, "Agent SOUL: Mission [${missionId}] completed: $outcomeSummary")
    }

    /**
     * Fails the mission and records the blocker.
     */
    suspend fun failMission(missionId: String, failureReason: String) {
        val current = soulDao.getMissionById(missionId) ?: return
        val updated = current.copy(
            status = AgentSoulEntity.STATUS_FAILED,
            failureReason = failureReason,
            currentStepSummary = "Failed: $failureReason",
            completedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        soulDao.insertOrUpdateMission(updated)
        _activeMission.value = null
        Log.e(TAG, "Agent SOUL: Mission [${missionId}] failed: $failureReason")
    }

    /**
     * Builds a prompt context block representing current active Agent SOUL state
     * so newly invoked specialist agents instantly know what other agents have already done.
     */
    fun buildAgentSoulPromptContext(): String {
        val active = _activeMission.value ?: return "[AGENT SOUL: All agents idle. Ready for new mission.]"
        val sb = StringBuilder()
        sb.append("[AGENT SOUL — LIVE BLACKBOARD & ACTIVE WORKSPACE]:\n")
        sb.append("• Active Mission ID: ${active.missionId}\n")
        sb.append("• Overall Goal: \"${active.goal}\"\n")
        sb.append("• Current Phase/Step: ${active.currentStepSummary}\n")
        sb.append("• Progress: ${active.progressPercent}% (${active.completedStepsCount}/${active.totalStepsCount} steps done)\n")

        val board = inMemoryBlackboards[active.missionId]
        if (!board.isNullOrEmpty()) {
            sb.append("• Shared Blackboard Variables:\n")
            board.forEach { (k, v) ->
                sb.append("   - $k = $v\n")
            }
        }
        sb.append("Coordinate with other agents using this state. Do not repeat completed steps.")
        return sb.toString()
    }

    private fun restoreBlackboard(missionId: String, json: String) {
        if (json.isBlank() || json == "{}") return
        try {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val map: Map<String, Any>? = gson.fromJson(json, type)
            if (map != null) {
                inMemoryBlackboards[missionId] = ConcurrentHashMap(map)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore blackboard JSON for $missionId", e)
        }
    }
}
