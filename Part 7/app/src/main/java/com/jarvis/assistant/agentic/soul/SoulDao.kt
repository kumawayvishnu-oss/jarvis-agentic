package com.jarvis.assistant.agentic.soul

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SoulDao {

    // ==========================================
    // 👤 USER SOUL (User Knowledge & Habits)
    // ==========================================

    @Query("SELECT * FROM user_soul_attributes ORDER BY confidence DESC, updatedAt DESC")
    fun getAllUserSoulAttributesFlow(): Flow<List<UserSoulEntity>>

    @Query("SELECT * FROM user_soul_attributes ORDER BY confidence DESC, updatedAt DESC")
    suspend fun getAllUserSoulAttributes(): List<UserSoulEntity>

    @Query("SELECT * FROM user_soul_attributes WHERE category = :category ORDER BY confidence DESC")
    suspend fun getUserSoulByCategory(category: String): List<UserSoulEntity>

    @Query("SELECT * FROM user_soul_attributes WHERE traitKey = :key LIMIT 1")
    suspend fun getUserSoulByKey(key: String): UserSoulEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateUserSoul(attribute: UserSoulEntity): Long

    @Query("DELETE FROM user_soul_attributes WHERE traitKey = :key")
    suspend fun deleteUserSoulByKey(key: String)

    // ==========================================
    // ⚡ DYNAMIC SYNTHESIZED SKILLS
    // ==========================================

    @Query("SELECT * FROM user_synthesized_skills WHERE isActive = 1 ORDER BY usageCount DESC, updatedAt DESC")
    fun getActiveSkillsFlow(): Flow<List<SynthesizedSkillEntity>>

    @Query("SELECT * FROM user_synthesized_skills WHERE isActive = 1 ORDER BY usageCount DESC")
    suspend fun getActiveSkills(): List<SynthesizedSkillEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSkill(skill: SynthesizedSkillEntity)

    @Query("UPDATE user_synthesized_skills SET usageCount = usageCount + 1, updatedAt = :timestamp WHERE skillId = :skillId")
    suspend fun incrementSkillUsage(skillId: String, timestamp: Long = System.currentTimeMillis())

    // ==========================================
    // 🤖 AGENT SOUL (Missions & Shared Blackboard)
    // ==========================================

    @Query("SELECT * FROM agent_soul_missions WHERE status IN ('PLANNING', 'RUNNING', 'PAUSED', 'RECOVERING') ORDER BY updatedAt DESC LIMIT 1")
    fun getActiveMissionFlow(): Flow<AgentSoulEntity?>

    @Query("SELECT * FROM agent_soul_missions WHERE status IN ('PLANNING', 'RUNNING', 'PAUSED', 'RECOVERING') ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getActiveMission(): AgentSoulEntity?

    @Query("SELECT * FROM agent_soul_missions WHERE missionId = :missionId LIMIT 1")
    suspend fun getMissionById(missionId: String): AgentSoulEntity?

    @Query("SELECT * FROM agent_soul_missions ORDER BY startedAt DESC LIMIT 30")
    fun getRecentMissionsFlow(): Flow<List<AgentSoulEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateMission(mission: AgentSoulEntity)

    @Query("UPDATE agent_soul_missions SET status = :status, updatedAt = :timestamp WHERE missionId = :missionId")
    suspend fun updateMissionStatus(missionId: String, status: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE agent_soul_missions SET blackboardStateJson = :blackboardJson, updatedAt = :timestamp WHERE missionId = :missionId")
    suspend fun updateMissionBlackboard(missionId: String, blackboardJson: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM agent_soul_missions WHERE status IN ('PLANNING', 'RUNNING', 'PAUSED', 'RECOVERING') ORDER BY updatedAt DESC")
    suspend fun getAllActiveMissions(): List<AgentSoulEntity>

    @Query("SELECT * FROM agent_soul_missions ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getRecentMissions(limit: Int = 30): List<AgentSoulEntity>

    @Query("DELETE FROM user_soul_attributes")
    suspend fun clearAllUserSoul()

    // ==========================================
    // 📋 STEP LOGS & AUDIT TRAILS
    // ==========================================

    @Query("SELECT * FROM agent_soul_step_logs WHERE missionId = :missionId ORDER BY timestamp ASC")
    fun getMissionStepLogsFlow(missionId: String): Flow<List<AgentStepLogEntity>>

    @Query("SELECT * FROM agent_soul_step_logs WHERE missionId = :missionId ORDER BY timestamp ASC")
    suspend fun getMissionStepLogs(missionId: String): List<AgentStepLogEntity>

    @Query("SELECT * FROM agent_soul_step_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogsFlow(limit: Int = 30): Flow<List<AgentStepLogEntity>>

    @Query("SELECT * FROM agent_soul_step_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLogs(limit: Int = 30): List<AgentStepLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStepLog(log: AgentStepLogEntity): Long
}
