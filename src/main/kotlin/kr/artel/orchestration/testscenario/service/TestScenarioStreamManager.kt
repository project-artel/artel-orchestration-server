package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.util.concurrent.ConcurrentHashMap

/**
 * TestScenario 챗봇의 SSE 스트림과 세션 매핑을 관리하는 서비스.
 *
 * 세션 키(`sessionKey`)는 `userId:projectId` 형식으로, 인증된 사용자와 프로젝트의 조합이다.
 * 같은 사용자라도 프로젝트가 다르면 다른 스트림이 되어 동시 멀티 프로젝트 작성을 지원한다.
 *
 * - `sessionKey` 별로 SSE Sink를 보관하고, Agent 응답 이벤트를 해당 스트림으로 emit 한다.
 *
 * SSE 구독보다 Agent 응답이 먼저 도착하는 경우(스트림 미존재) 이벤트를 드롭하고 경고 로그를 남긴다.
 */
@Service
class TestScenarioStreamManager {

    private val logger = LoggerFactory.getLogger(TestScenarioStreamManager::class.java)

    private val streams = ConcurrentHashMap<String, Sinks.Many<ServerSentEvent<JsonNode>>>()

    /**
     * 주어진 sessionKey에 대한 SSE 스트림을 구독한다. 스트림이 없으면 생성하며, 구독 종료 시 정리한다.
     */
    fun stream(sessionKey: String): Flux<ServerSentEvent<JsonNode>> {
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
     * Agent 응답 이벤트를 해당 sessionKey의 SSE 스트림으로 전달한다.
     * `type`은 SSE 이벤트명으로 사용되어 FE가 step 결과/폴백 질문 등을 구분할 수 있게 한다.
     *
     * @return 활성 스트림으로 전달에 성공했는지 여부
     */
    fun emit(sessionKey: String, type: String, payload: JsonNode): Boolean {
        val sink = streams[sessionKey]
        if (sink == null) {
            logger.warn("활성 SSE 스트림이 없어 이벤트 드롭 [sessionKey: $sessionKey, type: $type]")
            return false
        }
        val event = ServerSentEvent.builder(payload)
            .event(type)
            .build()
        val result = sink.tryEmitNext(event)
        if (result.isFailure) {
            logger.warn("SSE 이벤트 emit 실패 [sessionKey: $sessionKey, type: $type, result: $result]")
            return false
        }
        return true
    }
}
