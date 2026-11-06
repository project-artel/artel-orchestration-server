package kr.artel.orchestration.developermacro.controller

import kr.artel.orchestration.developermacro.dto.InternalDeveloperMacroListResponse
import kr.artel.orchestration.developermacro.service.DeveloperMacroService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * agent-server 의 planner–executer 엔진이 프로젝트의 developer macro 를 읽는 경로(ARTEL-1088). 런 시작 때 이름·
 * parameter·docstring 을 planner prompt 에 보이고, `from macros import` 를 이 원문들로 푼다.
 *
 * `/internal/` 아래라 내부 포트에만 있고 인증이 없다(`.agents/docs/project.md` 의 신뢰 경계).
 */
@RestController
@RequestMapping("/internal/projects/{projectId}/developer-macros")
class InternalDeveloperMacroController(
    private val service: DeveloperMacroService,
) {

    // 이름이 `list` 면 다른 컨트롤러의 `list` 와 겹쳐 OpenAPI operationId 번호가 밀린다(`DeveloperMacroController` 참고).
    @GetMapping
    suspend fun listDeveloperMacrosForAgent(@PathVariable projectId: Long): InternalDeveloperMacroListResponse =
        service.listForAgent(projectId)
}
