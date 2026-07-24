package kr.artel.orchestration.testscenario.service

import kr.artel.orchestration.testscenario.dto.ScenarioStreamEvent
import org.slf4j.LoggerFactory
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.util.concurrent.ConcurrentHashMap

/**
 * TestScenario 챗봇의 SSE 스트림을 관리하는 서비스.
 *
 * 세션 키(`sessionKey`)는 `userId:testScenarioId` 형식으로, 인증된 사용자와 시나리오의 조합이다.
 * `sessionKey` 별로 SSE Sink를 보관하고, Agent 응답을 타입화한 [ScenarioStreamEvent]로 emit 한다.
 *
 * SSE 구독보다 Agent 응답이 먼저 도착하는 경우(스트림 미존재) 이벤트를 드롭하고 경고 로그를 남긴다.
 */
@Service
class TestScenarioStreamManager {

    private val logger = LoggerFactory.getLogger(TestScenarioStreamManager::class.java)

    private val streams = ConcurrentHashMap<String, Sinks.Many<ServerSentEvent<ScenarioStreamEvent>>>()

    /**
     * 주어진 sessionKey에 대한 SSE 스트림을 구독한다. 스트림이 없으면 생성하며, 구독 종료 시 정리한다.
     */
    fun stream(sessionKey: String): Flux<ServerSentEvent<ScenarioStreamEvent>> {
        val sink = streams.computeIfAbsent(sessionKey) {
            Sinks.many().multicast().onBackpressureBuffer()
        }
        return sink.asFlux()
            .doFinally {
                streams.remove(sessionKey)
                logger.info("SSE 스트림 종료 및 정리 [sessionKey: $sessionKey]")
            }
    }

    /**
     * SSE 스트림을 강제 종료한다(Approve/Delete로 세션이 닫힐 때). FE의 EventSource는 스트림이 그냥 완료되면
     * 자동 재연결을 시도하므로, `closed` 이벤트를 먼저 보내 FE가 스스로 EventSource.close()를 호출하게 한 뒤
     * 스트림을 완료한다. 활성 스트림이 없으면 조용히 무시한다.
     */
    fun complete(sessionKey: String) {
        val sink = streams.remove(sessionKey) ?: return
        val closed = ServerSentEvent.builder(ScenarioStreamEvent(type = "closed"))
            .event("closed")
            .build()
        sink.tryEmitNext(closed)
        sink.tryEmitComplete()
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
        val result = sink.tryEmitNext(sse)
        if (result.isFailure) {
            logger.warn("SSE 이벤트 emit 실패 [sessionKey: $sessionKey, type: ${event.type}, result: $result]")
            return false
        }
        return true
    }
}
