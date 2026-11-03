package kr.artel.orchestration.gamefact.controller

import kr.artel.orchestration.gamefact.dto.InternalGameFactListResponse
import kr.artel.orchestration.gamefact.service.GameFactService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * agent-server 의 planner–executer 엔진이 프로젝트의 fact·rule 을 읽는 경로(V174). `facts` knob 이 켜진 런이 시작할 때 읽는다.
 *
 * `/internal/` 아래라 내부 포트에만 있고 인증이 없다(`.agents/docs/project.md` 의 신뢰 경계).
 */
@RestController
@RequestMapping("/internal/projects/{projectId}/game-facts")
class InternalGameFactController(
    private val service: GameFactService,
) {

    @GetMapping
    suspend fun listGameFactsForAgent(@PathVariable projectId: Long): InternalGameFactListResponse =
        service.listForAgent(projectId)
}
