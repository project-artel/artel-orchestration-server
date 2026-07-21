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
 * - `clientId`(FE 발급) 별로 SSE Sink를 보관하고, Agent 콜백으로 도착한 이벤트를 해당 스트림으로 emit 한다.
 * - `clientId ↔ agentSessionId`(Agent 발급) 매핑을 보관하여 이후 턴의 Agent 요청에 실어 대화 맥락을 유지한다.
 *
 * SSE 구독보다 콜백이 먼저 도착하는 경우(스트림 미존재)에는 이벤트를 드롭하고 경고 로그를 남긴다(MVP 정책).
 */
@Service
class TestScenarioStreamManager {

    private val logger = LoggerFactory.getLogger(TestScenarioStreamManager::class.java)

    private val streams = ConcurrentHashMap<String, Sinks.Many<ServerSentEvent<JsonNode>>>()
    private val agentSessions = ConcurrentHashMap<String, String>()

    /**
     * 주어진 clientId에 대한 SSE 스트림을 구독한다. 스트림이 없으면 생성하며, 구독 종료 시 정리한다.
     */
    fun stream(clientId: String): Flux<ServerSentEvent<JsonNode>> {
        val sink = streams.computeIfAbsent(clientId) {
            Sinks.many().multicast().onBackpressureBuffer()
        }
        return sink.asFlux()
            .doFinally {
                streams.remove(clientId)
                agentSessions.remove(clientId)
                logger.info("SSE 스트림 종료 및 정리 [clientId: $clientId]")
            }
    }

    /**
     * Agent 콜백 이벤트를 해당 clientId의 SSE 스트림으로 전달한다.
     * `type`은 SSE 이벤트명으로 사용되어 FE가 step 결과/폴백 질문 등을 구분할 수 있게 한다.
     *
     * @return 활성 스트림으로 전달에 성공했는지 여부
     */
    fun emit(clientId: String, type: String, payload: JsonNode): Boolean {
        val sink = streams[clientId]
        if (sink == null) {
            logger.warn("활성 SSE 스트림이 없어 이벤트 드롭 [clientId: $clientId, type: $type]")
            return false
        }
        val event = ServerSentEvent.builder(payload)
            .event(type)
            .build()
        val result = sink.tryEmitNext(event)
        if (result.isFailure) {
            logger.warn("SSE 이벤트 emit 실패 [clientId: $clientId, type: $type, result: $result]")
            return false
        }
        return true
    }

    /**
     * Agent가 발급한 agentSessionId를 clientId와 매핑하여 보관한다(이후 턴의 맥락 유지용).
     */
    fun bindAgentSession(clientId: String, agentSessionId: String) {
        agentSessions[clientId] = agentSessionId
    }

    /**
     * clientId에 매핑된 agentSessionId를 반환한다. 첫 턴 등 아직 없으면 null.
     */
    fun agentSessionOf(clientId: String): String? = agentSessions[clientId]
}
