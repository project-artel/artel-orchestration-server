package kr.artel.orchestration.issue.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.issue.dto.IssueFacetsResponse
import kr.artel.orchestration.issue.dto.IssuePageResponse
import kr.artel.orchestration.issue.service.IssueService
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
    private val service: IssueService
) {
    @GetMapping("/{issueId}")
    suspend fun get(@PathVariable projectId: Long, @PathVariable issueId: Long, @CurrentUserId appUserId: Long) =
        service.getByProject(projectId, issueId, appUserId)

    /**
     * @param status 없으면 해결·미해결 모두. `OPEN`/`RESOLVED` 외의 값은 400.
     * @param severity 없으면 모든 등급. 사다리 밖의 값은 400.
     * @param beforeId 이 id보다 작은 이슈부터(최신순 커서).
     * @param gameBuildId 이 빌드 버전에서 나온 이슈만. run 이 고른 빌드, 없으면 TR 의 빌드로 본다.
     * @param testRunId 이 TR 의 실행에서 나온 이슈만.
     * @param folderId 이 이슈 folder 에 담긴 이슈만.
     * @param unversioned true 면 빌드를 알 수 없는 이슈만(버전 목록의 "버전 없음").
     * @param categoryId 이 카테고리를 붙인 이슈만.
     * @param archived true 면 보관한 이슈만(보관함), 아니면 보관하지 않은 이슈만.
     * @param query 제목에 이 글이 든 이슈만(대소문자 무시).
     */
    @GetMapping
    suspend fun list(
        @PathVariable projectId: Long,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) severity: String?,
        @RequestParam(required = false) beforeId: Long?,
        @RequestParam(defaultValue = "50") size: Int,
        @RequestParam(required = false) gameBuildId: Long?,
        @RequestParam(required = false) testRunId: Long?,
        @RequestParam(required = false) folderId: Long?,
        @RequestParam(defaultValue = "false") unversioned: Boolean,
        @RequestParam(required = false) categoryId: Long?,
        @RequestParam(defaultValue = "false") archived: Boolean,
        @RequestParam(required = false) query: String?,
        @CurrentUserId appUserId: Long
    ): IssuePageResponse =
        service.listByProject(
            projectId, appUserId, status, severity, beforeId, size, gameBuildId, testRunId, folderId, unversioned, categoryId, archived, query
        )

    /**
     * 빌드 · TR · 심각도별 이슈 수. [status] 는 목록과 같은 상태 거르기다. [gameBuildId] · [testRunId] 를 주면 그 안에서만
     * 센다 — 고른 빌드에서 심각도를 누르기 전에 몇 건이 남는지 보이게 한다.
     */
    @GetMapping("/facets")
    suspend fun facets(
        @PathVariable projectId: Long,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) gameBuildId: Long?,
        @RequestParam(required = false) testRunId: Long?,
        @CurrentUserId appUserId: Long
    ): IssueFacetsResponse = service.facetsByProject(projectId, appUserId, status, gameBuildId, testRunId)
}
