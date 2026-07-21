package kr.artel.orchestration.testscenario.controller

import com.fasterxml.jackson.databind.JsonNode
import kr.artel.orchestration.testscenario.dto.AgentScenarioRequest
import kr.artel.orchestration.testscenario.dto.TestScenarioMessage
import kr.artel.orchestration.testscenario.service.TestScenarioAgentService
import kr.artel.orchestration.testscenario.service.TestScenarioStreamManager
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * QA 대시보드(React)와 통신하는 TestScenario 챗봇 REST 컨트롤러.
 *
 * - 자연어 메시지 전송: HTTP POST (`/message`) → Agent로는 WebSocket으로 중계
 * - Agent 응답/폴백 질문 수신: SSE 스트림 (`/stream`)
 *
 * `clientId`는 FE가 발급하는 상관관계 키로, SSE 스트림과 Agent WS 커넥션의 안정적 식별자다.
 */
@RestController
@RequestMapping("/api/test-scenario")
class TestScenarioController(
    private val agentService: TestScenarioAgentService,
    private val streamManager: TestScenarioStreamManager
) {

    /**
     * FE가 Agent 응답/폴백 질문을 실시간으로 수신하기 위해 구독하는 SSE 스트림.
     */
    @GetMapping("/{clientId}/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@PathVariable clientId: String): Flux<ServerSentEvent<JsonNode>> {
        return streamManager.stream(clientId)
    }

    /**
     * FE로부터 자연어 메시지를 HTTP로 수신하여, Agent 서버로는 WebSocket으로 중계한다.
     * (FE → Orchestration: HTTP 수신 / Orchestration → Agent: WS 송신)
     * 이전 턴에서 확보한 agentSessionId가 있으면 함께 실어 대화 맥락을 유지한다.
     */
    @PostMapping("/{clientId}/message")
    fun relayMessage(
        @PathVariable clientId: String,
        @RequestBody message: TestScenarioMessage
    ): Mono<ResponseEntity<String>> {
        val request = AgentScenarioRequest(
            type = message.type,
            testScenarioMessage = message.testScenarioMessage,
            clientId = clientId,
            agentSessionId = streamManager.agentSessionOf(clientId)
        )
        /* WS를 통해 Agent에 전달 */
        return agentService.sendMessage(request)
            .then(Mono.just(ResponseEntity.ok("메시지 전송 완료")))
            .onErrorResume { error ->
                Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error.message))
            }
    }
}
