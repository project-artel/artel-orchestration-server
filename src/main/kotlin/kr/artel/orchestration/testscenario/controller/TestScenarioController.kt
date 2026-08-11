package kr.artel.orchestration.testscenario.controller

import kr.artel.orchestration.common.error.UnauthorizedException
import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.testscenario.dto.ApproveScenarioRequest
import kr.artel.orchestration.testscenario.dto.CreateScenarioRequest
import kr.artel.orchestration.testscenario.dto.CreateScenarioResponse
import kr.artel.orchestration.testscenario.dto.ScenarioResponse
import kr.artel.orchestration.testscenario.dto.UpdateScenarioRequest
import kr.artel.orchestration.testscenario.service.TestScenarioService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
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
 * 컨트롤러는 얇게 유지한다: JWT에서 userId를 추출하고, 비즈니스 로직은 [TestScenarioService]에 위임하며,
 * HTTP 상태코드 매핑만 담당한다. 작성 챗봇 대화(메시지/스트림/중계)는 런 단위이므로
 * [kr.artel.orchestration.testrun.controller.TestRunController]로 분리됐다(ARTEL-206 Step 6).
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

    /** 시나리오를 승인(확정)한다: 최종 draft 저장(대화 세션은 런 단위라 유지). */
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
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<Void> {
        service.delete(requireUser(jwt), testScenarioId, force)
        return ResponseEntity.noContent().build()
    }

    /** 유효한 사용자 토큰이 아니면 401. */
    private fun requireUser(jwt: Jwt): Long =
        sessionUserResolver.resolve(jwt)?.userId
            ?: throw UnauthorizedException()
}
