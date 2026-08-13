package kr.artel.orchestration.testrun.controller

import kr.artel.orchestration.common.error.BadRequestException
import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.testrun.dto.CommitScenariosRequest
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
 * 작성 챗봇 대화(메시지/스트림/중계/종료)는 런 단위이므로 여기에 함께 둔다(ARTEL-206 Step 6): 한 번의 대화로
 * 여러 시나리오를 추가·수정하며, 결과는 이 런에 반영된다. 대화 로직은 [TestRunChatService]에 위임한다.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/test-runs")
class TestRunController(
    private val service: TestRunService,
    private val chatService: TestRunChatService
) {

    @GetMapping
    suspend fun list(
        @PathVariable projectId: Long,
        @CurrentUserId appUserId: Long
    ): TestRunListResponse =
        service.list(projectId, appUserId)

    @PostMapping
    suspend fun create(
        @PathVariable projectId: Long,
        @RequestBody request: TestRunCreateRequest,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<TestRunResponse> =
        service.create(projectId, appUserId, request)
            ?.let { ResponseEntity.status(HttpStatus.CREATED).body(it) }
            ?: ResponseEntity.notFound().build()

    @GetMapping("/{runId}")
    suspend fun get(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<TestRunResponse> =
        service.get(runId, appUserId)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @PutMapping("/{runId}")
    suspend fun update(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @RequestBody request: TestRunUpdateRequest,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<TestRunResponse> =
        service.update(runId, appUserId, request)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @DeleteMapping("/{runId}")
    suspend fun delete(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<Void> {
        service.delete(runId, appUserId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{runId}/scenarios")
    suspend fun getScenarios(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<RunScenariosResponse> =
        service.getScenarios(runId, appUserId)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @PutMapping("/{runId}/scenarios")
    suspend fun setScenarios(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @RequestBody request: SetRunScenariosRequest,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<RunScenariosResponse> {
        val scenarioIds = request.scenarioIds.map {
            it.toLongOrNull() ?: throw BadRequestException("invalid scenarioId: $it")
        }
        return service.setScenarios(runId, appUserId, scenarioIds)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
    }

    // --- 작성 챗봇 대화(런 단위) — ARTEL-206 Step 6 ---

    /** 사용자별 프라이빗 채팅 스레드 조회(재방문 복원). */
    @GetMapping("/{runId}/chat/messages")
    suspend fun chatMessages(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @CurrentUserId appUserId: Long
    ): List<MessageResponse> =
        chatService.getMessages(runId, appUserId)

    /** Agent 응답을 실시간 수신하는 SSE 스트림(타입화된 ScenarioStreamEvent). */
    @GetMapping("/{runId}/chat/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    suspend fun chatStream(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @CurrentUserId appUserId: Long
    ): Flow<ServerSentEvent<ScenarioStreamEvent>> =
        chatService.stream(appUserId, runId)

    /** 사용자 자연어 메시지를 수신하여 Agent로 중계한다(→ WebSocket). 결과 시나리오는 이 런에 반영된다. */
    @PostMapping("/{runId}/chat/message")
    suspend fun chatRelay(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @RequestBody message: RunChatMessage,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<String> {
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

    /**
     * 카드 검토 모드 커밋: 사용자가 카드로 고른/편집한 시나리오를 이 런에 반영한다(추가/수정은 scenario_id로 갈림).
     * 자동저장(autoApply)과 같은 엔진을 쓴다. 빈 배열은 무동작.
     */
    @PostMapping("/{runId}/scenarios/commit")
    suspend fun commitScenarios(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @RequestBody request: CommitScenariosRequest,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<RunScenariosResponse> {
        chatService.commitScenarios(appUserId, runId, request.scenarios)
        return service.getScenarios(runId, appUserId)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
    }

    /** 작성 세션을 종료한다(런 편집 종료 시): Agent WS/SSE를 닫는다(채팅·시나리오는 남김). */
    @PostMapping("/{runId}/chat/close")
    suspend fun chatClose(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<String> {
        chatService.close(appUserId, runId)
        return ResponseEntity.ok("세션 종료 완료")
    }
}
