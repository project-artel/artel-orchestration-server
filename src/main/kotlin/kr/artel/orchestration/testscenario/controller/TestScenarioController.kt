package kr.artel.orchestration.testscenario.controller

import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.testscenario.dto.CreateScenarioRequest
import kr.artel.orchestration.testscenario.dto.CreateScenarioResponse
import kr.artel.orchestration.testscenario.dto.MessageResponse
import kr.artel.orchestration.testscenario.dto.ScenarioResponse
import kr.artel.orchestration.testscenario.dto.ScenarioStreamEvent
import kr.artel.orchestration.testscenario.dto.TestScenarioMessage
import kr.artel.orchestration.testscenario.service.TestScenarioService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * QA 대시보드(React)와 통신하는 TestScenario 챗봇 REST 컨트롤러(외부/인증된 요청).
 *
 * 컨트롤러는 얇게 유지한다: JWT에서 userId를 추출하고, 비즈니스 로직은 [TestScenarioService]에 위임하며,
 * HTTP 상태코드 매핑만 담당한다. 라우팅 키(userId:testScenarioId) 조립·저장/조회는 서비스가 처리한다.
 */
@RestController
@RequestMapping("/api/test-scenario")
class TestScenarioController(
    private val service: TestScenarioService,
    private val sessionUserResolver: SessionUserResolver
) {

    /** 새 시나리오를 생성하고 testScenarioId를 반환한다. */
    @PostMapping
    fun create(
        @RequestBody request: CreateScenarioRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): Mono<ResponseEntity<CreateScenarioResponse>> {
        val appUserId = requireUser(jwt)
        return service.createScenario(request.projectId, appUserId)
            .map { ResponseEntity.ok(CreateScenarioResponse(it)) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    /** 시나리오 단건 조회(payload = ScenarioDraft). FE가 canvas 렌더/재방문 복원에 사용. */
    @GetMapping("/{testScenarioId}")
    fun getScenario(
        @PathVariable testScenarioId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): Mono<ResponseEntity<ScenarioResponse>> {
        val appUserId = requireUser(jwt)
        return service.getScenario(testScenarioId, appUserId)
            .map { ResponseEntity.ok(it) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    /** 사용자별 프라이빗 채팅 스레드 조회(재방문 복원). */
    @GetMapping("/{testScenarioId}/messages")
    fun getMessages(
        @PathVariable testScenarioId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): Flux<MessageResponse> {
        val appUserId = requireUser(jwt)
        return service.getMessages(testScenarioId, appUserId)
    }

    /** Agent 응답을 실시간 수신하는 SSE 스트림(타입화된 ScenarioStreamEvent). */
    @GetMapping("/{testScenarioId}/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(
        @PathVariable testScenarioId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): Flux<ServerSentEvent<ScenarioStreamEvent>> {
        val appUserId = requireUser(jwt)
        return service.stream(appUserId, testScenarioId)
    }

    /** 사용자 자연어 메시지를 수신하여 Agent로 중계한다(→ WebSocket). */
    @PostMapping("/{testScenarioId}/message")
    fun relayMessage(
        @PathVariable testScenarioId: Long,
        @RequestBody message: TestScenarioMessage,
        @AuthenticationPrincipal jwt: Jwt
    ): Mono<ResponseEntity<String>> {
        val appUserId = requireUser(jwt)
        return service.relay(appUserId, testScenarioId, message)
            .then(Mono.just(ResponseEntity.ok("메시지 전송 완료")))
            // 접근 거부(404 등 ResponseStatusException)는 그대로 전파하고, Agent 전송 실패만 502로 변환한다.
            .onErrorResume({ it !is ResponseStatusException }) { error ->
                Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error.message))
            }
    }

    /** 유효한 사용자 토큰이 아니면 401. */
    private fun requireUser(jwt: Jwt): Long =
        sessionUserResolver.resolve(jwt)?.userId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}
