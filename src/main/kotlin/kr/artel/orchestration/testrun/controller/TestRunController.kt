package kr.artel.orchestration.testrun.controller

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.UnauthorizedException
import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.testrun.dto.RunChatMessage
import kr.artel.orchestration.testrun.dto.RunScenariosResponse
import kr.artel.orchestration.testrun.dto.SetRunScenariosRequest
import kr.artel.orchestration.testrun.dto.TestRunCreateRequest
import kr.artel.orchestration.testrun.dto.TestRunListResponse
import kr.artel.orchestration.testrun.dto.TestRunResponse
import kr.artel.orchestration.testrun.dto.TestRunUpdateRequest
import kr.artel.orchestration.testrun.service.TestRunChatService
import kr.artel.orchestration.testrun.service.TestRunService
import kr.artel.orchestration.testscenario.dto.MessageResponse
import kr.artel.orchestration.testscenario.dto.ScenarioStreamEvent
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * TestRun REST(외부/인증, 코루틴). 여러 시나리오를 묶은 실행 세트를 만들고 시나리오 조합을 편집한다.
 *
 * 저작 챗봇 대화(메시지/스트림/중계/종료)는 런 단위이므로 여기에 함께 둔다(ARTEL-206 Step 6): 한 번의 대화로
 * 여러 시나리오를 추가·수정하며, 결과는 이 런에 반영된다. 대화 로직은 [TestRunChatService]에 위임한다.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/test-runs")
class TestRunController(
    private val service: TestRunService,
    private val chatService: TestRunChatService,
    private val sessionUserResolver: SessionUserResolver
) {

    @GetMapping
    suspend fun list(
        @PathVariable projectId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): TestRunListResponse =
        service.list(projectId, requireUser(jwt))

    @PostMapping
    suspend fun create(
        @PathVariable projectId: Long,
        @RequestBody request: TestRunCreateRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<TestRunResponse> =
        service.create(projectId, requireUser(jwt), request)
            ?.let { ResponseEntity.status(HttpStatus.CREATED).body(it) }
            ?: ResponseEntity.notFound().build()

    @GetMapping("/{runId}")
    suspend fun get(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<TestRunResponse> =
        service.get(runId, requireUser(jwt))
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @PutMapping("/{runId}")
    suspend fun update(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @RequestBody request: TestRunUpdateRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<TestRunResponse> =
        service.update(runId, requireUser(jwt), request)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @DeleteMapping("/{runId}")
    suspend fun delete(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<Void> {
        service.delete(runId, requireUser(jwt))
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{runId}/scenarios")
    suspend fun getScenarios(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<RunScenariosResponse> =
        service.getScenarios(runId, requireUser(jwt))
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @PutMapping("/{runId}/scenarios")
    suspend fun setScenarios(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @RequestBody request: SetRunScenariosRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<RunScenariosResponse> {
        val scenarioIds = request.scenarioIds.map {
            it.toLongOrNull() ?: throw BadRequestException("invalid scenarioId: $it")
        }
        return service.setScenarios(runId, requireUser(jwt), scenarioIds)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
    }

    // --- 저작 챗봇 대화(런 단위) — ARTEL-206 Step 6 ---

    /** 사용자별 프라이빗 채팅 스레드 조회(재방문 복원). */
    @GetMapping("/{runId}/chat/messages")
    suspend fun chatMessages(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): List<MessageResponse> =
        chatService.getMessages(runId, requireUser(jwt))

    /** Agent 응답을 실시간 수신하는 SSE 스트림(타입화된 ScenarioStreamEvent). */
    @GetMapping("/{runId}/chat/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun chatStream(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): Flow<ServerSentEvent<ScenarioStreamEvent>> =
        chatService.stream(requireUser(jwt), runId)

    /** 사용자 자연어 메시지를 수신하여 Agent로 중계한다(→ WebSocket). 결과 시나리오는 이 런에 반영된다. */
    @PostMapping("/{runId}/chat/message")
    suspend fun chatRelay(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @RequestBody message: RunChatMessage,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<String> {
        val appUserId = requireUser(jwt)
        return try {
            chatService.relay(appUserId, runId, message)
            ResponseEntity.ok("메시지 전송 완료")
        } catch (e: kr.artel.orchestration.common.error.ApiException) {
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

    /** 저작 세션을 종료한다(런 편집 종료 시): Agent WS/SSE를 닫는다(채팅·시나리오는 남김). */
    @PostMapping("/{runId}/chat/close")
    suspend fun chatClose(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<String> {
        chatService.close(requireUser(jwt), runId)
        return ResponseEntity.ok("세션 종료 완료")
    }

    private fun requireUser(jwt: Jwt): Long =
        sessionUserResolver.resolve(jwt)?.userId
            ?: throw UnauthorizedException()
}
