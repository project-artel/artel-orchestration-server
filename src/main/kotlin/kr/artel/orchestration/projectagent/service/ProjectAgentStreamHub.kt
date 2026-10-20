package kr.artel.orchestration.projectagent.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onSubscription
import kr.artel.orchestration.projectagent.config.ProjectAgentProperties
import kr.artel.orchestration.projectagent.dto.ProjectAgentConversationResponse
import kr.artel.orchestration.projectagent.dto.ProjectAgentMessageResponse
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 대화마다 SSE 구독자에게 `message` · `conversation` 이벤트를 나눠 준다(ARTEL-1127).
 *
 * 한 사용자가 탭 여러 개로 같은 대화를 열 수 있어 구독자는 여럿이다. 과거 이벤트는 다시 보내지 않는다 —
 * browser 가 `GET messages` 로 기록을 읽고 나서 구독하고, 이미 가진 id 의 `message` 는 버린다.
 * 구독자가 하나도 없으면 이벤트는 그냥 버려진다.
 */
@Component
class ProjectAgentStreamHub(private val properties: ProjectAgentProperties) {

    private class Channel {
        val events = MutableSharedFlow<ServerSentEvent<Any>>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        var subscribers = 0
    }

    private val channels = ConcurrentHashMap<Long, Channel>()

    /** 이벤트 흐름에 [ProjectAgentProperties.heartbeatInterval] 마다 comment 줄을 섞어 돌려준다. */
    fun subscribe(conversationId: Long): Flow<ServerSentEvent<Any>> {
        val heartbeat = flow<ServerSentEvent<Any>> {
            while (true) {
                delay(properties.heartbeatInterval.toMillis())
                emit(ServerSentEvent.builder<Any>().comment("heartbeat").build())
            }
        }
        return merge(events(conversationId), heartbeat)
    }

    fun publish(message: ProjectAgentMessageResponse) = publish(message.conversationId, "message", message)

    fun publish(conversation: ProjectAgentConversationResponse) = publish(conversation.id, "conversation", conversation)

    private fun publish(conversationId: Long, name: String, data: Any) {
        val channel = channels[conversationId] ?: return
        channel.events.tryEmit(ServerSentEvent.builder(data).event(name).build())
    }

    private fun events(conversationId: Long): Flow<ServerSentEvent<Any>> = flow {
        val channel = channels.compute(conversationId) { _, existing ->
            (existing ?: Channel()).also { it.subscribers++ }
        }!!
        try {
            // 구독이 실제로 등록된 뒤에 comment 한 줄을 먼저 보내, 연결이 열렸음을 browser 가 알게 한다.
            emitAll(channel.events.onSubscription { emit(ServerSentEvent.builder<Any>().comment("open").build()) })
        } finally {
            channels.compute(conversationId) { _, existing ->
                existing?.also { it.subscribers-- }?.takeIf { it.subscribers > 0 }
            }
        }
    }
}
