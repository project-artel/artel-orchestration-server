package kr.artel.orchestration.tracker.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.tracker.dto.TrackerInstallUrlResponse
import kr.artel.orchestration.tracker.dto.TrackerRepositoryPageResponse
import kr.artel.orchestration.tracker.dto.TrackerRepositoryResponse
import kr.artel.orchestration.tracker.entity.TrackerProvider
import kr.artel.orchestration.tracker.github.GitHubInstallationService
import kr.artel.orchestration.tracker.service.ProjectTrackerLinkService
import kr.artel.orchestration.tracker.service.TrackerSetupStateService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * GitHub App `installation` 흐름. **여기만 경로에 `provider` 가 박힌다.**
 *
 * 그 이유는 설치라는 개념이 GitHub 고유이기 때문이다 — Jira 는 설치 주소도 저장소 목록도 없고, 대신
 * 다른 것이 필요하다. 공통 계약(`tracker-link`)은 `provider` 를 값으로 받고, provider 마다 다른 이
 * 흐름만 자기 경로를 갖는다.
 */
@Tag(name = "Issue tracker", description = "GitHub App 설치 흐름")
@RestController
@RequestMapping("/api/projects/{projectId}/tracker/github")
class GitHubTrackerController(
    private val linkService: ProjectTrackerLinkService,
    private val installationService: GitHubInstallationService,
    private val stateService: TrackerSetupStateService
) {
    @Operation(
        summary = "GitHub App 설치 주소",
        description = "소유자만. state가 서명되어 있어 남의 프로젝트에 설치를 붙일 수 없다."
    )
    @GetMapping("/install-url")
    suspend fun installUrl(
        @PathVariable projectId: Long,
        @CurrentUserId appUserId: Long
    ): TrackerInstallUrlResponse {
        // 설치는 연결을 만드는 동작이므로 소유자만이다. 조회 endpoint 지만 판정은 쓰기와 같다.
        linkService.requireOwnerForSetup(projectId, appUserId)
        val state = stateService.issue(projectId, appUserId, TrackerProvider.GITHUB)
        return TrackerInstallUrlResponse(installationService.installUrl(state))
    }

    @Operation(summary = "설치된 저장소 목록", description = "소유자만. App이 설치된 저장소만 보인다.")
    @GetMapping("/repositories")
    suspend fun repositories(
        @PathVariable projectId: Long,
        @CurrentUserId appUserId: Long
    ): TrackerRepositoryPageResponse {
        val installationRef =
            linkService.requireInstallation(projectId, appUserId, TrackerProvider.GITHUB)
        val items = installationService.listRepositories(installationRef).map {
            TrackerRepositoryResponse(
                workspace = it.workspace,
                repository = it.repository,
                htmlUrl = it.htmlUrl,
                private = it.private
            )
        }
        return TrackerRepositoryPageResponse(items)
    }
}
