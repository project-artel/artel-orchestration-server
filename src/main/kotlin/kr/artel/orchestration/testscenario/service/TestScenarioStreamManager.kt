package kr.artel.orchestration.testscenario.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.transformWhile
import kr.artel.orchestration.testscenario.dto.ScenarioStreamEvent
import org.slf4j.LoggerFactory
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * TestScenario 챗봇의 SSE 스트림을 관리하는 서비스(코루틴).
 *
 * 세션 키(`sessionKey`)는 `userId:testScenarioId` 형식으로, 인증된 사용자와 시나리오의 조합이다.
 * `sessionKey` 별로 [MutableSharedFlow]를 보관하고, Agent 응답을 타입화한 [ScenarioStreamEvent]로 emit 한다.
 *
 * SharedFlow는 스스로 완료되지 않으므로, 종료는 `closed` 이벤트를 sentinel로 흘려보내고
 * `transformWhile`이 그 이벤트를 마지막으로 스트림을 끝내는 방식으로 처리한다(Sinks.tryEmitComplete 대체).
 * SSE 구독보다 Agent 응답이 먼저 도착하는 경우(스트림 미존재) 이벤트를 드롭하고 경고 로그를 남긴다.
 */
@Service
class TestScenarioStreamManager {

    private val logger = LoggerFactory.getLogger(TestScenarioStreamManager::class.java)

    private val streams = ConcurrentHashMap<String, MutableSharedFlow<ServerSentEvent<ScenarioStreamEvent>>>()

    /**
     * 주어진 sessionKey에 대한 SSE 스트림을 구독한다. 스트림이 없으면 생성한다.
     * `closed` 이벤트를 수신하면 그 이벤트까지 전달한 뒤 스트림을 완료하고, 완료 시 정리한다.
     */
    fun stream(sessionKey: String): Flow<ServerSentEvent<ScenarioStreamEvent>> {
        val sink = streams.computeIfAbsent(sessionKey) {
            MutableSharedFlow(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }
        return sink
            .transformWhile { sse ->
                emit(sse)
                // `closed` 이벤트를 마지막으로 스트림을 종료한다(그 전까지는 계속 흘린다).
                sse.event() != "closed"
            }
            .onCompletion {
                streams.remove(sessionKey)
                logger.info("SSE 스트림 종료 및 정리 [sessionKey: $sessionKey]")
            }
    }

    /**
     * SSE 스트림을 강제 종료한다(Approve/Delete로 세션이 닫힐 때). FE의 EventSource는 스트림이 그냥 완료되면
     * 자동 재연결을 시도하므로, `closed` 이벤트를 먼저 보내 FE가 스스로 EventSource.close()를 호출하게 한 뒤
     * 스트림을 완료한다(sentinel을 흘리면 [stream]의 transformWhile이 스트림을 끝낸다).
     * 활성 스트림이 없으면 조용히 무시한다.
     */
    fun complete(sessionKey: String) {
        val sink = streams.remove(sessionKey) ?: return
        val closed = ServerSentEvent.builder(ScenarioStreamEvent(type = "closed"))
            .event("closed")
            .build()
        sink.tryEmit(closed)
        logger.info("SSE 스트림 종료 이벤트 전송 및 완료 [sessionKey: $sessionKey]")
    }

    /**
     * Agent 응답 이벤트를 해당 sessionKey의 SSE 스트림으로 전달한다.
     * `event.type`을 SSE 이벤트명으로 사용해 FE가 result/error 등을 구분할 수 있게 한다.
     *
     * @return 활성 스트림으로 전달에 성공했는지 여부
     */
    fun emit(sessionKey: String, event: ScenarioStreamEvent): Boolean {
        val sink = streams[sessionKey]
        if (sink == null) {
            logger.warn("활성 SSE 스트림이 없어 이벤트 드롭 [sessionKey: $sessionKey, type: ${event.type}]")
            return false
        }
        val sse = ServerSentEvent.builder(event)
            .event(event.type)
            .build()
        val ok = sink.tryEmit(sse)
        if (!ok) {
            logger.warn("SSE 이벤트 emit 실패 [sessionKey: $sessionKey, type: ${event.type}]")
            return false
        }
        return true
    }
}
