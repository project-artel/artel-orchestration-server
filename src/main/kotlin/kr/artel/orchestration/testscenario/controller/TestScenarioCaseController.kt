package kr.artel.orchestration.testscenario.controller

import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.testscenario.dto.ScenarioCasesResponse
import kr.artel.orchestration.testscenario.dto.SetScenarioCasesRequest
import kr.artel.orchestration.testscenario.service.ScenarioCompositionService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * 시나리오의 케이스 조합 REST(외부/인증, 코루틴). FE 캔버스가 시나리오를 케이스로 구성/재정렬한다.
 * 조회는 순서대로 리졸브된 케이스, 저장은 caseIds 전체 교체(드래그앤드롭 결과 반영).
 */
@RestController
@RequestMapping("/api/test-scenario/{testScenarioId}/cases")
class TestScenarioCaseController(
    private val service: ScenarioCompositionService,
    private val sessionUserResolver: SessionUserResolver
) {

    @GetMapping
    suspend fun getCases(
        @PathVariable testScenarioId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<ScenarioCasesResponse> =
        service.getCases(testScenarioId, requireUser(jwt))
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @PutMapping
    suspend fun setCases(
        @PathVariable testScenarioId: Long,
        @RequestBody request: SetScenarioCasesRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<ScenarioCasesResponse> {
        val caseIds = request.caseIds.map {
            it.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid caseId: $it")
        }
        return service.setCases(testScenarioId, requireUser(jwt), caseIds)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
    }

    private fun requireUser(jwt: Jwt): Long =
        sessionUserResolver.resolve(jwt)?.userId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}
