package kr.artel.orchestration.testscenario.controller

import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.testscenario.dto.ScenarioListResponse
import kr.artel.orchestration.testscenario.dto.ScenarioResponse
import kr.artel.orchestration.testscenario.service.TestScenarioService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

/**
 * 프로젝트 스코프 TestScenario 조회 컨트롤러(외부/인증된 요청).
 *
 * FE 목록 화면이 "현재 프로젝트의 시나리오들"을 그리기 위한 읽기 전용 엔드포인트다.
 * 편집 세션용 엔드포인트(SSE·메시지·자동저장 등)와 달리, 이쪽은 프로젝트 단위 진입점이다.
 * 프로젝트 참여자만 접근 가능하며, 비참여자에게는 존재하지 않는 것처럼(404) 보인다.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/test-scenario")
class ProjectTestScenarioController(
    private val service: TestScenarioService,
    private val sessionUserResolver: SessionUserResolver
) {

    /** 프로젝트의 시나리오 목록(요약). FE 목록 화면 렌더용. 비참여자면 404. */
    @GetMapping
    fun list(
        @PathVariable projectId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): Mono<ScenarioListResponse> {
        val appUserId = requireUser(jwt)
        return service.listScenarios(projectId, appUserId)
    }

    /** 프로젝트 스코프 시나리오 단건 조회(payload = ScenarioDraft). 없거나 접근 불가면 404. */
    @GetMapping("/{testScenarioId}")
    fun getOne(
        @PathVariable projectId: Long,
        @PathVariable testScenarioId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): Mono<ResponseEntity<ScenarioResponse>> {
        val appUserId = requireUser(jwt)
        return service.getScenarioInProject(projectId, testScenarioId, appUserId)
            .map { ResponseEntity.ok(it) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    /** 유효한 사용자 토큰이 아니면 401. */
    private fun requireUser(jwt: Jwt): Long =
        sessionUserResolver.resolve(jwt)?.userId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}
