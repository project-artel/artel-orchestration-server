package kr.artel.orchestration.gameidentity.controller

import kr.artel.orchestration.gameidentity.dto.InternalGameIdentityListResponse
import kr.artel.orchestration.gameidentity.service.GameIdentityService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RequestParam

/**
 * agent-server 의 planner–executer 엔진이 프로젝트의 게임 값 선언을 읽는 경로(V173). 런 시작 때 읽어 엔진이 게임 state
 * 의 값이 무엇을 가리키는지 알게 한다.
 *
 * `/internal/` 아래라 내부 포트에만 있고 인증이 없다(`.agents/docs/project.md` 의 신뢰 경계).
 */
@RestController
@RequestMapping("/internal/projects/{projectId}/game-identities")
class InternalGameIdentityController(
    private val service: GameIdentityService,
) {

    // 이름이 `list` 면 다른 컨트롤러의 `list` 와 겹쳐 OpenAPI operationId 번호가 밀린다(`DeveloperMacroController` 참고).
    @GetMapping
    suspend fun listGameIdentitiesForAgent(@PathVariable projectId: Long, @RequestParam(required = false) gameBuildId: Long?): InternalGameIdentityListResponse =
        service.listForAgent(projectId, gameBuildId)
}
