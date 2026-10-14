package com.jarvis.assistant.agentic.swarm

import android.content.Context
import com.jarvis.assistant.agentic.soul.AgentSoulManager
import com.jarvis.assistant.agentic.soul.StepLogLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Message passed between agents in the swarm.
 */
data class InterAgentMessage(
    val messageId: String,
    val senderAgent: String,
    val targetAgent: String,
    val topic: String,
    val payload: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * AgentInterCom: Inter-agent communication bus and message router.
 * Enables real-time message passing, blackboard synchronization, and topic pub/sub
 * across all active agents in the JARVIS swarm.
 */
class AgentInterCom private constructor(
    private val context: Context,
    private val agentSoulManager: AgentSoulManager = AgentSoulManager.getInstance(context)
) {
    private val _messageBus = MutableSharedFlow<InterAgentMessage>(extraBufferCapacity = 64)
    val messageBus: SharedFlow<InterAgentMessage> = _messageBus.asSharedFlow()

    private val messageHistory = CopyOnWriteArrayList<InterAgentMessage>()

    /**
     * Sends a message from one agent to another and logs it to the blackboard.
     */
    suspend fun sendMessage(
        sender: String,
        target: String,
        topic: String,
        payload: String,
        taskId: String? = null
    ) {
        val msg = InterAgentMessage(
            messageId = "msg_${System.currentTimeMillis()}_${(100..999).random()}",
            senderAgent = sender,
            targetAgent = target,
            topic = topic,
            payload = payload
        )

        messageHistory.add(msg)
        if (messageHistory.size > 100) {
            messageHistory.removeAt(0)
        }

        // Post to shared blackboard
        val missionId = taskId ?: "global"
        agentSoulManager.postToBlackboard(missionId, "intercom_${msg.senderAgent}_to_${msg.targetAgent}", msg.payload)

        agentSoulManager.postStepLog(
            agentName = sender,
            action = "InterAgentDispatch",
            detail = "Sent message to $target [Topic: $topic]: ${payload.take(100)}",
            level = StepLogLevel.INFO,
            taskId = taskId
        )

        _messageBus.emit(msg)
    }

    /**
     * Subscribes to messages addressed to a specific agent (or wildcard "*").
     */
    fun subscribe(agentName: String): Flow<InterAgentMessage> =
        messageBus.filter { it.targetAgent.equals(agentName, ignoreCase = true) || it.targetAgent == "*" }

    /**
     * Subscribes to messages on a specific topic.
     */
    fun subscribeTopic(topic: String): Flow<InterAgentMessage> =
        messageBus.filter { it.topic.equals(topic, ignoreCase = true) }

    /**
     * Retrieves recent messages from the inter-agent bus.
     */
    fun getRecentMessages(limit: Int = 20): List<InterAgentMessage> =
        messageHistory.takeLast(limit)

    companion object {
        @Volatile
        private var INSTANCE: AgentInterCom? = null

        fun getInstance(context: Context): AgentInterCom {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AgentInterCom(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
