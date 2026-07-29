package kr.artel.orchestration.testscenario.controller

import kr.artel.orchestration.common.error.ApiException
import kr.artel.orchestration.common.error.UnauthorizedException
import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.testscenario.dto.ApproveScenarioRequest
import kr.artel.orchestration.testscenario.dto.CreateScenarioRequest
import kr.artel.orchestration.testscenario.dto.CreateScenarioResponse
import kr.artel.orchestration.testscenario.dto.MessageResponse
import kr.artel.orchestration.testscenario.dto.ScenarioResponse
import kr.artel.orchestration.testscenario.dto.ScenarioStreamEvent
import kr.artel.orchestration.testscenario.dto.TestScenarioMessage
import kr.artel.orchestration.testscenario.dto.UpdateScenarioRequest
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * QA 대시보드(React)와 통신하는 TestScenario 챗봇 REST 컨트롤러(외부/인증된 요청, 코루틴).
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
    suspend fun create(
        @RequestBody request: CreateScenarioRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<CreateScenarioResponse> {
        val appUserId = requireUser(jwt)
        return service.createScenario(request.projectId, appUserId)
            ?.let { ResponseEntity.ok(CreateScenarioResponse(it)) }
            ?: ResponseEntity.notFound().build()
    }

    /** 시나리오 단건 조회(payload = ScenarioDraft). FE가 canvas 렌더/재방문 복원에 사용. */
    @GetMapping("/{testScenarioId}")
    suspend fun getScenario(
        @PathVariable testScenarioId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<ScenarioResponse> {
        val appUserId = requireUser(jwt)
        return service.getScenario(testScenarioId, appUserId)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
    }

    /** 사용자별 프라이빗 채팅 스레드 조회(재방문 복원). */
    @GetMapping("/{testScenarioId}/messages")
    suspend fun getMessages(
        @PathVariable testScenarioId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): List<MessageResponse> {
        val appUserId = requireUser(jwt)
        return service.getMessages(testScenarioId, appUserId)
    }

    /** Agent 응답을 실시간 수신하는 SSE 스트림(타입화된 ScenarioStreamEvent). */
    @GetMapping("/{testScenarioId}/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun stream(
        @PathVariable testScenarioId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): Flow<ServerSentEvent<ScenarioStreamEvent>> {
        val appUserId = requireUser(jwt)
        return service.stream(appUserId, testScenarioId)
    }

    /** canvas 편집 실시간 자동저장(debounce PUT). 저장된 payload를 되돌려 FE 정합성을 맞춘다. */
    @PutMapping("/{testScenarioId}")
    suspend fun testScenarioUpdate(
        @PathVariable testScenarioId: Long,
        @RequestBody request: UpdateScenarioRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<ScenarioResponse> {
        val appUserId = requireUser(jwt)
        return ResponseEntity.ok(service.testScenarioUpdate(appUserId, testScenarioId, request.draft))
    }

    /** 사용자 자연어 메시지를 수신하여 Agent로 중계한다(→ WebSocket). */
    @PostMapping("/{testScenarioId}/message")
    suspend fun relayMessage(
        @PathVariable testScenarioId: Long,
        @RequestBody message: TestScenarioMessage,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<String> {
        val appUserId = requireUser(jwt)
        return try {
            service.relay(appUserId, testScenarioId, message)
            ResponseEntity.ok("메시지 전송 완료")
        } catch (e: ApiException) {
            // 접근 거부(404 등) 등 도메인 예외는 그대로 전파해 advice가 매핑하게 둔다.
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 요청 취소(클라이언트 끊김)는 삼키지 않고 전파해야 코루틴이 정상 취소된다.
            throw e
        } catch (e: Exception) {
            // Agent 전송 실패만 502로 변환한다.
            ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(e.message)
        }
    }

    /** 시나리오를 승인(확정)한다: 최종 draft 저장 + Agent WS/SSE 종료(채팅·시나리오는 남김). */
    @PostMapping("/{testScenarioId}/approve")
    suspend fun testScenarioApprove(
        @PathVariable testScenarioId: Long,
        @RequestBody(required = false) request: ApproveScenarioRequest?,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<String> {
        val appUserId = requireUser(jwt)
        service.testScenarioApprove(appUserId, testScenarioId, request?.draft)
        return ResponseEntity.ok("승인 완료")
    }

    /** 유효한 사용자 토큰이 아니면 401. */
    private fun requireUser(jwt: Jwt): Long =
        sessionUserResolver.resolve(jwt)?.userId
            ?: throw UnauthorizedException()
}
