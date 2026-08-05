package kr.artel.orchestration.issue.controller

import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.common.error.UnauthorizedException
import kr.artel.orchestration.issue.dto.IssuePageResponse
import kr.artel.orchestration.issue.service.IssueService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 프로젝트 스코프 이슈 목록(ARTEL-245).
 *
 * 여러 실행에 흩어진 이슈를 한자리에서 보는 진입점이다. 실행 단위 목록은
 * `QaTryController.issues`에 있다 — 두 목록은 스코프만 다르고 같은 페이지 모양을 돌려준다.
 *
 * `ProjectTestScenarioController`와 같은 갈래이며, 비참여자에게는 존재하지 않는 것처럼 404다.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/issues")
class ProjectIssueController(
    private val service: IssueService,
    private val userResolver: SessionUserResolver
) {
    /**
     * @param status 없으면 해결·미해결 모두. `OPEN`/`RESOLVED` 외의 값은 400.
     * @param severity 없으면 모든 등급. 사다리 밖의 값은 400.
     * @param beforeId 이 id보다 작은 이슈부터(최신순 커서).
     */
    @GetMapping
    suspend fun list(
        @PathVariable projectId: Long,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) severity: String?,
        @RequestParam(required = false) beforeId: Long?,
        @RequestParam(defaultValue = "50") size: Int,
        @AuthenticationPrincipal jwt: Jwt
    ): IssuePageResponse =
        service.listByProject(projectId, requireUser(jwt), status, severity, beforeId, size)

    private fun requireUser(jwt: Jwt): Long =
        userResolver.resolve(jwt)?.userId ?: throw UnauthorizedException()
}
