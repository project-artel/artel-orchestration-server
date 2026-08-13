package kr.artel.orchestration.testscenario.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.testscenario.dto.ApproveScenarioRequest
import kr.artel.orchestration.testscenario.dto.CreateScenarioRequest
import kr.artel.orchestration.testscenario.dto.CreateScenarioResponse
import kr.artel.orchestration.testscenario.dto.ScenarioResponse
import kr.artel.orchestration.testscenario.dto.UpdateExpectedLabelsRequest
import kr.artel.orchestration.testscenario.dto.UpdateScenarioRequest
import kr.artel.orchestration.testscenario.service.TestScenarioService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * QA 대시보드(React)와 통신하는 TestScenario 본체 REST 컨트롤러(외부/인증된 요청, 코루틴).
 *
 * 컨트롤러는 얇게 유지한다: 비즈니스 로직은 [TestScenarioService]에 위임하고
 * HTTP 상태코드 매핑만 담당한다. 작성 챗봇 대화(메시지/스트림/중계)는 런 단위이므로
 * [kr.artel.orchestration.testrun.controller.TestRunController]로 분리됐다(ARTEL-206 Step 6).
 */
@RestController
@RequestMapping("/api/test-scenario")
class TestScenarioController(
    private val service: TestScenarioService
) {

    /** 새 시나리오를 생성하고 testScenarioId를 반환한다. */
    @PostMapping
    suspend fun create(
        @RequestBody request: CreateScenarioRequest,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<CreateScenarioResponse> =
        service.createScenario(request.projectId, appUserId)
            ?.let { ResponseEntity.ok(CreateScenarioResponse(it)) }
            ?: ResponseEntity.notFound().build()

    /** 시나리오 단건 조회(payload = ScenarioDraft). FE가 canvas 렌더/재방문 복원에 사용. */
    @GetMapping("/{testScenarioId}")
    suspend fun getScenario(
        @PathVariable testScenarioId: Long,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<ScenarioResponse> =
        service.getScenario(testScenarioId, appUserId)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    /** canvas 편집 실시간 자동저장(debounce PUT). 저장된 payload를 되돌려 FE 정합성을 맞춘다. */
    @PutMapping("/{testScenarioId}")
    suspend fun testScenarioUpdate(
        @PathVariable testScenarioId: Long,
        @RequestBody request: UpdateScenarioRequest,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<ScenarioResponse> =
        ResponseEntity.ok(service.testScenarioUpdate(appUserId, testScenarioId, request.draft))

    /**
     * 스텝의 기대 판정 라벨만 갈아끼운다(ARTEL-301). **라벨을 바꿀 수 있는 유일한 경로다.**
     *
     * 위 자동저장(PUT)과 아래 승인은 들어온 라벨을 버리고 기존 값을 보존한다 — 저작 클라이언트는
     * 라벨을 모르므로, 그쪽으로 받으면 스텝을 한 글자 고쳤을 뿐인데 정답지가 통째로 사라진다.
     * 본문과 라벨을 한 요청으로 받지 않는 것도 같은 이유의 뒷면이다: 함께 받으면 라벨링 도구가
     * 본문을 되돌린다.
     */
    @PutMapping("/{testScenarioId}/expected-labels")
    suspend fun updateExpectedLabels(
        @PathVariable testScenarioId: Long,
        @RequestBody request: UpdateExpectedLabelsRequest,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<ScenarioResponse> =
        ResponseEntity.ok(
            service.updateExpectedLabels(appUserId, testScenarioId, request.toLabelMap())
        )

    /** 시나리오를 승인(확정)한다: 최종 draft 저장(대화 세션은 런 단위라 유지). */
    @PostMapping("/{testScenarioId}/approve")
    suspend fun testScenarioApprove(
        @PathVariable testScenarioId: Long,
        @RequestBody(required = false) request: ApproveScenarioRequest?,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<String> {
        service.testScenarioApprove(appUserId, testScenarioId, request?.draft)
        return ResponseEntity.ok("승인 완료")
    }

    /**
     * 시나리오와 그 조합 링크를 삭제한다(케이스/런 본체는 남김). 접근 불가/미존재면 404.
     *
     * QA 실행 이력이 있으면 기본적으로 409(`scenario_has_qa_history`)로 막는다. `?force=true`면
     * 실행 이력(qa_try·qa_log·issue)까지 지우고 삭제한다.
     */
    @DeleteMapping("/{testScenarioId}")
    suspend fun delete(
        @PathVariable testScenarioId: Long,
        @RequestParam(required = false, defaultValue = "false") force: Boolean,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<Void> {
        service.delete(appUserId, testScenarioId, force)
        return ResponseEntity.noContent().build()
    }
}
