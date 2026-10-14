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
import kotlinx.coroutines.flow.map
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

    private val _tasksFlow = MutableStateFlow<List<SoulTask>>(emptyList())
    private val _recentLogsFlow = MutableStateFlow<List<SoulStepLog>>(emptyList())

    fun observeTasksByStatus(status: TaskStatus): Flow<List<SoulTask>> {
        return _tasksFlow.map { tasks ->
            tasks.filter { it.status == status }
        }
    }

    fun observeAllTasks(limit: Int = 10): Flow<List<SoulTask>> {
        return _tasksFlow.map { tasks ->
            tasks.take(limit)
        }
    }

    fun observeRecentLogs(limit: Int = 10): Flow<List<SoulStepLog>> {
        return _recentLogsFlow.map { logs ->
            logs.take(limit)
        }
    }

    init {
        scope.launch {
            soulDao.getActiveMissionFlow().collect { mission ->
                _activeMission.value = mission
                mission?.let {
                    restoreBlackboard(it.missionId, it.blackboardStateJson)
                }
            }
        }

        // Restore recent persistent step logs into live reactive flow
        scope.launch {
            try {
                val recentLogs = soulDao.getRecentLogs(25)
                val mapped = recentLogs.map { entity ->
                    val lvl = try {
                        StepLogLevel.valueOf(entity.status)
                    } catch (e: Exception) {
                        StepLogLevel.INFO
                    }
                    SoulStepLog(
                        logId = entity.logId,
                        agentName = entity.agentName,
                        action = entity.actionName,
                        detail = entity.target.ifBlank { entity.inputPayload },
                        level = lvl,
                        timestamp = entity.timestamp
                    )
                }
                _recentLogsFlow.value = mapped
            } catch (e: Exception) {
                Log.w(TAG, "Failed to preload recent step logs: ${e.message}")
            }
        }

        // Restore active missions into live task flow
        scope.launch {
            try {
                val activeMissions = soulDao.getAllActiveMissions()
                val tasks = activeMissions.map { m ->
                    val status = try {
                        TaskStatus.valueOf(m.status)
                    } catch (e: Exception) {
                        TaskStatus.IN_PROGRESS
                    }
                    val agents = try {
                        val type = object : TypeToken<List<String>>() {}.type
                        val list: List<String>? = gson.fromJson(m.assignedAgentsJson, type)
                        list?.firstOrNull() ?: "ManagerAgent"
                    } catch (e: Exception) {
                        "ManagerAgent"
                    }
                    SoulTask(
                        taskId = m.missionId,
                        title = m.goal.take(50),
                        description = m.goal,
                        initiator = "System",
                        assignedTo = agents,
                        priority = TaskPriority.NORMAL,
                        status = status,
                        createdAt = m.startedAt
                    )
                }
                _tasksFlow.value = tasks
            } catch (e: Exception) {
                Log.w(TAG, "Failed to preload active missions: ${e.message}")
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

    fun postStepLog(
        agentName: String,
        action: String,
        detail: String,
        level: StepLogLevel = StepLogLevel.INFO,
        taskId: String? = null
    ) {
        val mission = _activeMission.value
        val missionId = taskId ?: mission?.missionId ?: "ad-hoc"
        Log.i(TAG, "[$level][$agentName][$action] $detail")
        scope.launch {
            try {
                recordStepLog(
                    missionId = missionId,
                    stepIndex = mission?.completedStepsCount ?: 0,
                    agentName = agentName,
                    actionName = action,
                    target = detail.take(120),
                    inputPayload = detail,
                    outputPayload = null,
                    verified = level == StepLogLevel.SUCCESS,
                    status = level.name,
                    errorMessage = if (level == StepLogLevel.ERROR) detail else null
                )
                val newLog = SoulStepLog(
                    agentName = agentName,
                    action = action,
                    detail = detail,
                    level = level,
                    timestamp = System.currentTimeMillis()
                )
                val currentLogs = _recentLogsFlow.value.toMutableList()
                currentLogs.add(0, newLog)
                if (currentLogs.size > 25) {
                    _recentLogsFlow.value = currentLogs.take(25)
                } else {
                    _recentLogsFlow.value = currentLogs
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error posting step log: ${e.message}")
            }
        }
    }

    suspend fun createTask(
        title: String,
        description: String,
        initiator: String = "User",
        assignedTo: String = "ManagerAgent",
        priority: TaskPriority = TaskPriority.NORMAL
    ): String {
        val mission = startMission(
            goal = description,
            assignedAgents = listOf(assignedTo)
        )
        val task = SoulTask(
            taskId = mission.missionId,
            title = title,
            description = description,
            initiator = initiator,
            assignedTo = assignedTo,
            priority = priority,
            status = TaskStatus.IN_PROGRESS
        )
        val currentTasks = _tasksFlow.value.toMutableList()
        currentTasks.add(0, task)
        _tasksFlow.value = currentTasks
        return mission.missionId
    }

    suspend fun createMissionTask(
        title: String,
        description: String,
        initiator: String = "User",
        assignedTo: String = "ManagerAgent",
        priority: TaskPriority = TaskPriority.NORMAL
    ): AgentSoulEntity {
        val mission = startMission(
            goal = description,
            assignedAgents = listOf(assignedTo)
        )
        val task = SoulTask(
            taskId = mission.missionId,
            title = title,
            description = description,
            initiator = initiator,
            assignedTo = assignedTo,
            priority = priority,
            status = TaskStatus.IN_PROGRESS
        )
        val currentTasks = _tasksFlow.value.toMutableList()
        currentTasks.add(0, task)
        _tasksFlow.value = currentTasks
        return mission
    }

    fun updateTaskStatus(
        taskId: String,
        status: TaskStatus,
        resultSummary: String? = null
    ) {
        scope.launch {
            try {
                when (status) {
                    TaskStatus.COMPLETED -> completeMission(taskId, resultSummary ?: "Task completed")
                    TaskStatus.FAILED -> failMission(taskId, resultSummary ?: "Task failed")
                    else -> {
                        val current = soulDao.getMissionById(taskId)
                        if (current != null) {
                            val updated = current.copy(
                                status = status.name,
                                currentStepSummary = resultSummary ?: current.currentStepSummary,
                                updatedAt = System.currentTimeMillis()
                            )
                            soulDao.insertOrUpdateMission(updated)
                            _activeMission.value = updated
                        }
                    }
                }
                val updatedTasks = _tasksFlow.value.map {
                    if (it.taskId == taskId) it.copy(status = status, resultSummary = resultSummary ?: it.resultSummary) else it
                }
                _tasksFlow.value = updatedTasks
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update task status: ${e.message}")
            }
        }
    }
}

