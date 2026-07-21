package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.testscenario.dto.AgentScenarioRequest
import kr.artel.orchestration.testscenario.dto.AgentScenarioResponse
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.Disposable
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * TestScenario 챗봇의 Agent 서버 연동을 담당하는 WebSocket 클라이언트 서비스.
 *
 * Orchestration이 여는 쪽(WS 클라이언트)이며, **1 유저 = 1 sessionId = 1 WS 커넥션**으로 1:1 매핑한다.
 * 커넥션 자체가 세션 컨텍스트이므로 별도의 콜백 엔드포인트나 상관관계 해킹이 필요 없다.
 *
 * - 아웃바운드: 사용자 메시지를 해당 clientId 커넥션의 송신 싱크로 흘려보낸다.
 * - 인바운드: Agent가 보낸 응답을 수신해 StreamManager를 통해 FE의 SSE 스트림으로 중계하고,
 *   Agent가 발급한 agentSessionId를 clientId에 매핑한다.
 */
@Service
class TestScenarioAgentService(
    @Value("\${artel.agent.ws-base-url:ws://localhost:8000}") private val agentWsBaseUrl: String,
    private val objectMapper: ObjectMapper,
    private val streamManager: TestScenarioStreamManager,
    private val scenarioRepository: TestScenarioRepository
) {
    private val logger = LoggerFactory.getLogger(TestScenarioAgentService::class.java)
    private val wsClient = ReactorNettyWebSocketClient()
    private val connections = ConcurrentHashMap<String, AgentConnection>()

    /**
     * clientId 세션의 Agent 커넥션을 통해 메시지를 전송한다. 커넥션이 없으면 새로 연다.
     */
    fun sendMessage(request: AgentScenarioRequest): Mono<Void> {
        return Mono.fromCallable {
            val connection = connections.computeIfAbsent(request.clientId) { openConnection(it) }
            val json = objectMapper.writeValueAsString(request)
            val result = connection.outbound.tryEmitNext(json)
            if (result.isFailure) {
                throw IllegalStateException("Agent WS 전송 실패 [clientId=${request.clientId}, result=$result]")
            }
        }.then()
    }

    /**
     * clientId 세션의 Agent 커넥션을 닫는다(세션 종료 시).
     */
    fun closeConnection(clientId: String) {
        connections.remove(clientId)?.disposable?.dispose()
    }

    private fun openConnection(clientId: String): AgentConnection {
        // unicast 버퍼: WS 핸드셰이크 완료 전 emit된 최초 메시지도 버퍼링 후 전송된다.
        val outbound = Sinks.many().unicast().onBackpressureBuffer<String>()
        val uri = URI.create("$agentWsBaseUrl/testscenario?clientId=$clientId")
        logger.info("Agent WS 연결 시도 [clientId=$clientId, uri=$uri]")

        val sessionMono = wsClient.execute(uri) { session ->
            val send = session.send(outbound.asFlux().map(session::textMessage))
            val receive = session.receive()
                .doOnNext { message -> handleInbound(clientId, message.payloadAsText) }
                .then()
            send.and(receive)
        }.doFinally {
            connections.remove(clientId)
            logger.info("Agent WS 연결 종료 및 정리 [clientId=$clientId]")
        }

        val disposable = sessionMono.subscribe(
            null,
            { error -> logger.error("Agent WS 세션 에러 [clientId=$clientId]: ${error.message}") }
        )
        return AgentConnection(outbound, disposable)
    }

    private fun handleInbound(clientId: String, payloadText: String) {
        try {
            val response = objectMapper.readValue(payloadText, AgentScenarioResponse::class.java)
            response.agentSessionId?.let { streamManager.bindAgentSession(clientId, it) }
            streamManager.emit(clientId, response.type, response.payload)

            // 시나리오 결과(SCENARIO_STEP)는 QA/Report에서 참조하므로 DB에 영속화한다(clientId 기준 upsert).
            if (response.type == "SCENARIO_STEP") {
                persistScenario(clientId, response)
            }
        } catch (e: Exception) {
            logger.error("Agent WS 수신 메시지 처리 실패 [clientId=$clientId]: ${e.message}", e)
        }
    }

    /**
     * 시나리오 payload를 clientId 기준으로 upsert 한다. R2DBC 논블로킹 체인이라 별도 스레드 오프로드가 필요 없다.
     */
    private fun persistScenario(clientId: String, response: AgentScenarioResponse) {
        val payloadJson = objectMapper.writeValueAsString(response.payload)
        scenarioRepository.findByClientId(clientId)
            .flatMap { existing ->
                scenarioRepository.save(
                    existing.copy(
                        payload = payloadJson,
                        agentSessionId = response.agentSessionId ?: existing.agentSessionId
                    )
                )
            }
            .switchIfEmpty(
                scenarioRepository.save(
                    TestScenarioEntity(
                        clientId = clientId,
                        agentSessionId = response.agentSessionId,
                        payload = payloadJson
                    )
                )
            )
            .subscribe(
                { logger.info("시나리오 저장 완료 [clientId=$clientId, id=${it.id}]") },
                { err -> logger.error("시나리오 저장 실패 [clientId=$clientId]: ${err.message}") }
            )
    }

    private class AgentConnection(
        val outbound: Sinks.Many<String>,
        val disposable: Disposable
    )
}
