package com.jarvis.assistant.agentic.swarm

import android.content.Context
import com.jarvis.assistant.agentic.soul.AgentSoulManager
import com.jarvis.assistant.agentic.soul.StepLogLevel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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
 */
class AgentInterCom private constructor(
    private val context: Context,
    private val agentSoulManager: AgentSoulManager = AgentSoulManager.getInstance(context)
) {
    private val _messageBus = MutableSharedFlow<InterAgentMessage>(extraBufferCapacity = 64)
    val messageBus: SharedFlow<InterAgentMessage> = _messageBus.asSharedFlow()

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
            messageId = "msg_${System.currentTimeMillis()}",
            senderAgent = sender,
            targetAgent = target,
            topic = topic,
            payload = payload
        )

        agentSoulManager.postStepLog(
            agentName = sender,
            action = "InterAgentDispatch",
            detail = "Sent message to $target [Topic: $topic]: ${payload.take(100)}",
            level = StepLogLevel.INFO,
            taskId = taskId
        )

        _messageBus.emit(msg)
    }

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
