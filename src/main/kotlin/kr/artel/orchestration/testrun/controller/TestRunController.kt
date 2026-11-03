package kr.artel.orchestration.testrun.controller

import kr.artel.orchestration.common.error.BadRequestException
import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.testrun.dto.CommitScenariosRequest
import kr.artel.orchestration.testrun.dto.RunAuthoringLockResponse
import kr.artel.orchestration.testrun.dto.RunChatCancellation
import kr.artel.orchestration.testrun.dto.RunChatMessage
import kr.artel.orchestration.testrun.dto.RunCasesOutsideBuildResponse
import kr.artel.orchestration.testrun.dto.RunCoverageResponse
import kr.artel.orchestration.testrun.dto.RunDeletionPreview
import kr.artel.orchestration.testrun.dto.RunDeletionResult
import kr.artel.orchestration.testrun.dto.RunScenariosResponse
import kr.artel.orchestration.testrun.dto.ScenarioImportRequest
import kr.artel.orchestration.testrun.dto.ScenarioImportResponse
import kr.artel.orchestration.testrun.dto.SetRunScenariosRequest
import kr.artel.orchestration.testrun.dto.TestRunCreateRequest
import kr.artel.orchestration.testrun.dto.TestRunGameBuildRequest
import kr.artel.orchestration.testrun.dto.TestRunListResponse
import kr.artel.orchestration.testrun.dto.TestRunResponse
import kr.artel.orchestration.testrun.dto.TestRunUpdateRequest
import kr.artel.orchestration.testrun.service.ScenarioLibraryService
import kr.artel.orchestration.testrun.service.TestRunChatService
import kr.artel.orchestration.testrun.service.TestRunService
import kr.artel.orchestration.testscenario.dto.DraftApplyResult
import kr.artel.orchestration.testscenario.dto.DraftScenario
import kr.artel.orchestration.testscenario.dto.DraftScenarioEdit
import kr.artel.orchestration.testscenario.dto.DraftSelection
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
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
    private val chatService: TestRunChatService,
    private val libraryService: ScenarioLibraryService,
    private val recommendationService: kr.artel.orchestration.testscenario.service.CaseRecommendationService,
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

    /** TR 의 TC 버전을 바꾼다(ARTEL-1048). `gameBuildId` 가 null 이면 버전 없음. 남의 빌드면 404. editor 버전도 고를 수 있다(ARTEL-1180). */
    @PutMapping("/{runId}/game-build")
    suspend fun changeGameBuild(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @RequestBody request: TestRunGameBuildRequest,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<TestRunResponse> =
        service.changeGameBuild(runId, appUserId, request)
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

    /** 이 런을 지우면 무엇이 같이 없어지는지(ARTEL-487). 삭제 확인창이 물어보기 위해 읽는다. */
    @GetMapping("/{runId}/deletion-preview")
    suspend fun deletionPreview(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<RunDeletionPreview> =
        service.deletionPreview(runId, appUserId)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    /**
     * 런 삭제. `dropScenarios=true` 면 **이 런에만 담긴** 시나리오도 함께 지운다(ARTEL-487).
     *
     * 기본값은 남기기다 — 되돌릴 수 없는 삭제의 기본값은 덜 지우는 쪽이어야 한다. 화면이 미리
     * 세어 보고(`/deletion-preview`) 사용자에게 물은 뒤 그 답을 여기로 보낸다.
     */
    @DeleteMapping("/{runId}")
    suspend fun delete(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @RequestParam(required = false, defaultValue = "false") dropScenarios: Boolean,
        @CurrentUserId appUserId: Long
    ): RunDeletionResult = service.delete(runId, appUserId, dropScenarios)

    /**
     * 다른 TR 의 TS 를 **복사해** 이 TR 끝에 붙인다(ARTEL-994). 복사본의 `case_id` 는 이 TR 의 build 에서
     * 같은 케이스로 바꿔 끼우고, 짝이 없는 것은 `unmatchedCaseIds` 로 알린다.
     */
    @PostMapping("/{runId}/scenarios/imports")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun importScenarios(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @RequestBody request: ScenarioImportRequest,
        @CurrentUserId appUserId: Long
    ): ScenarioImportResponse =
        libraryService.importIntoRun(projectId, runId, appUserId, request.testScenarioIds)

    @GetMapping("/{runId}/scenarios")
    suspend fun getScenarios(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<RunScenariosResponse> =
        service.getScenarios(runId, appUserId)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    /**
     * **이 런의 시나리오들이 무엇을 담았는가**(ARTEL-903).
     *
     * 프로젝트 전량을 재는 `/api/projects/{projectId}/test-cases/coverage` 와 다른 축이다 —
     * 저작 중에 알고 싶은 것은 지금 만들고 있는 것들이 덮은 범위다. 앞서는 이 정보가 대화로
     * 씬별 집계(`TurnBattleScene 8/29`)로 나갔는데, 시나리오는 여러 씬을 지나는 흐름이라 그
     * 수로는 다음에 무엇을 할지 정할 수 없었다.
     */
    @GetMapping("/{runId}/coverage")
    suspend fun coverage(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<RunCoverageResponse> =
        service.coverage(runId, appUserId)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    /**
     * 이 런의 케이스 중 [gameInstanceId] 게임의 버전에 없는 것(ARTEL-1069). QA 는 그 케이스를 판정하지 않고 넘기므로,
     * QA 를 시작하기 전에 화면이 알린다.
     */
    @GetMapping("/{runId}/cases-outside-build")
    suspend fun casesOutsideBuild(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @RequestParam gameInstanceId: Long,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<RunCasesOutsideBuildResponse> =
        service.casesOutsideBuild(runId, gameInstanceId, appUserId)
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

    /**
     * 이 런이 Artel Bot 에게 잠겨 있나(ARTEL-1264). 맵 · TS 편집 화면이 잠금 화면을 띄울지 읽는다.
     * 잠금 · 풀림 · 진행 단계가 바뀌면 프로젝트 변경 stream 이 `RUN_LOCKS` 로 알린다.
     */
    @GetMapping("/{runId}/authoring-lock")
    suspend fun authoringLock(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @CurrentUserId appUserId: Long
    ): RunAuthoringLockResponse =
        chatService.authoringLock(appUserId, runId)

    /** 자동저장을 끈 턴이 모아 둔 draft(ARTEL-1074). 대화가 없으면 빈 배열. */
    @GetMapping("/{runId}/chat/draft")
    suspend fun chatDraft(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @CurrentUserId appUserId: Long
    ): List<DraftScenario> =
        chatService.draft(appUserId, runId)

    /** draft 하나를 사용자가 고친 본문으로 바꾼다(ARTEL-1074). 시나리오에는 쓰지 않는다. */
    @PutMapping("/{runId}/chat/draft/{draftId}")
    suspend fun updateChatDraft(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @PathVariable draftId: String,
        @RequestBody edit: DraftScenarioEdit,
        @CurrentUserId appUserId: Long
    ): DraftScenario =
        chatService.updateDraft(appUserId, runId, draftId, edit)

    /** 고른 draft 를 자동저장과 같은 엔진으로 반영한다(ARTEL-1074). */
    // --- 추천 TC: 저작이 추천한 TC 를 저장하거나 버린다 ---

    /** 이 런의 추천 TC. 답 말풍선의 카드가 상태(대기 · 저장됨 · 버림)를 다시 읽는다. */
    @GetMapping("/{runId}/case-recommendations")
    suspend fun caseRecommendations(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @CurrentUserId appUserId: Long,
    ): List<Map<String, Any?>> = recommendationService.list(projectId, runId, appUserId).map { it.wire() }

    /** 추천 TC 를 저장한다. `ids` 를 비우면 이 런의 대기 중인 추천 전부다. 스텝은 새 TC 를 확인하는 스텝으로 바뀐다. */
    @PostMapping("/{runId}/case-recommendations/save")
    suspend fun saveCaseRecommendations(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @RequestBody request: CaseRecommendationIds,
        @CurrentUserId appUserId: Long,
    ): List<Map<String, Any?>> =
        recommendationService.save(projectId, runId, appUserId, request.longIds()).map { it.wire() }

    /** 추천 TC 를 버린다. 그 스텝은 판정 없는 스텝으로 남는다. */
    @PostMapping("/{runId}/case-recommendations/discard")
    suspend fun discardCaseRecommendations(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @RequestBody request: CaseRecommendationIds,
        @CurrentUserId appUserId: Long,
    ): Map<String, Int> = mapOf("discarded" to recommendationService.discard(projectId, runId, appUserId, request.longIds()))

    @PostMapping("/{runId}/chat/draft/apply")
    suspend fun applyChatDraft(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @RequestBody selection: DraftSelection,
        @CurrentUserId appUserId: Long
    ): DraftApplyResult =
        chatService.applyDraft(appUserId, runId, selection.draftIds)

    /** 고른 draft 를 버린다(ARTEL-1074). 남은 draft 를 돌려준다. */
    @PostMapping("/{runId}/chat/draft/discard")
    suspend fun discardChatDraft(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @RequestBody selection: DraftSelection,
        @CurrentUserId appUserId: Long
    ): List<DraftScenario> =
        chatService.discardDraft(appUserId, runId, selection.draftIds)

    /**
     * 도는 저작 요청을 취소한다(ARTEL-955). 화면에서 ESC 를 두 번 누른 길이다.
     *
     * **세션을 닫는 아래 `close` 와 다른 창구다.** 사용자가 멈추려는 것은 기다림이지 대화가
     * 아니어서, 대화·세션·이미 저장한 시나리오는 그대로 남는다. 끊을 턴이 없으면 `cancelled=false`
     * 로 답한다 — 404 가 아니다. 답이 방금 도착했는데 ESC 를 누른 경우가 그 길이고, 그것은
     * 오류가 아니다.
     */
    @PostMapping("/{runId}/chat/cancel")
    suspend fun chatCancel(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<RunChatCancellation> =
        ResponseEntity.ok(chatService.cancel(appUserId, runId))

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

/** 추천 TC id 목록. 비우면 이 런의 대기 중인 추천 전부다. */
data class CaseRecommendationIds(val ids: List<String> = emptyList()) {
    fun longIds(): List<Long> = ids.mapNotNull { it.toLongOrNull() }
}
