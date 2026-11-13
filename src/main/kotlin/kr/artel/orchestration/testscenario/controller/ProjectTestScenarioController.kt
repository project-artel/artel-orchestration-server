package kr.artel.orchestration.testscenario.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.testcase.dto.ReviewDecisionRequest
import kr.artel.orchestration.testscenario.dto.ScenarioListResponse
import kr.artel.orchestration.testscenario.dto.ScenarioResponse
import kr.artel.orchestration.testscenario.dto.ScenarioReviewItem
import kr.artel.orchestration.testscenario.service.ScenarioReviewService
import kr.artel.orchestration.testscenario.service.TestScenarioService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 프로젝트 스코프 TestScenario 컨트롤러(외부/인증된 요청, 코루틴).
 *
 * 편집 세션용 엔드포인트(SSE·메시지·자동저장 등)와 달리, 이쪽은 프로젝트 단위 진입점이다.
 * 프로젝트 참여자만 접근 가능하며, 비참여자에게는 존재하지 않는 것처럼(404) 보인다.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/test-scenario")
class ProjectTestScenarioController(
    private val service: TestScenarioService,
    private val reviewService: ScenarioReviewService,
) {

    /** 승인 대기 시나리오. 어느 런에 들었는지와 스텝이 쓰는 Test Case 내용까지 싣는다. 비참여자면 404. */
    @GetMapping("/review-queue")
    suspend fun reviewQueue(
        @PathVariable projectId: Long,
        @CurrentUserId appUserId: Long
    ): List<ScenarioReviewItem> =
        reviewService.queue(projectId, appUserId)

    /** 승인 대기 시나리오를 승인하거나 거절(삭제)한다. 읽은 뒤 내용이 바뀌었거나 이미 처리됐으면 409. */
    @PostMapping("/{testScenarioId}/review")
    suspend fun review(
        @PathVariable projectId: Long,
        @PathVariable testScenarioId: Long,
        @RequestBody request: ReviewDecisionRequest,
        @CurrentUserId appUserId: Long,
    ): ResponseEntity<Void> {
        reviewService.decide(projectId, testScenarioId, appUserId, request)
        return ResponseEntity.noContent().build()
    }

    /** 프로젝트의 시나리오 목록(요약). FE 목록 화면 렌더용. 비참여자면 404. */
    @GetMapping
    suspend fun list(
        @PathVariable projectId: Long,
        @CurrentUserId appUserId: Long
    ): ScenarioListResponse =
        service.listScenarios(projectId, appUserId)

    /** 프로젝트 스코프 시나리오 단건 조회(payload = ScenarioDraft). 없거나 접근 불가면 404. */
    @GetMapping("/{testScenarioId}")
    suspend fun getOne(
        @PathVariable projectId: Long,
        @PathVariable testScenarioId: Long,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<ScenarioResponse> =
        service.getScenarioInProject(projectId, testScenarioId, appUserId)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @DeleteMapping("/{testScenarioId}")
    suspend fun delete(
        @PathVariable projectId: Long,
        @PathVariable testScenarioId: Long,
        @RequestParam(defaultValue = "false") force: Boolean,
        @CurrentUserId appUserId: Long,
    ): ResponseEntity<Void> {
        service.delete(appUserId, testScenarioId, force, projectId)
        return ResponseEntity.noContent().build()
    }
}
