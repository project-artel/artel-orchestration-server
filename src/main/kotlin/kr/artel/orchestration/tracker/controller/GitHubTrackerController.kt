package kr.artel.orchestration.tracker.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.tracker.client.TrackerAuthorizationRequiredException
import kr.artel.orchestration.tracker.client.TrackerInstallationNotVerifiedException
import kr.artel.orchestration.tracker.dto.TrackerInstallUrlResponse
import kr.artel.orchestration.tracker.dto.TrackerInstallationPageResponse
import kr.artel.orchestration.tracker.dto.TrackerInstallationResponse
import kr.artel.orchestration.tracker.dto.TrackerInstallationSelectRequest
import kr.artel.orchestration.tracker.dto.TrackerLinkEnvelope
import kr.artel.orchestration.tracker.dto.TrackerRepositoryPageResponse
import kr.artel.orchestration.tracker.dto.TrackerRepositoryResponse
import kr.artel.orchestration.tracker.entity.TrackerProvider
import kr.artel.orchestration.tracker.github.GitHubInstallationService
import kr.artel.orchestration.tracker.service.ProjectTrackerLinkService
import kr.artel.orchestration.tracker.service.TrackerInstallationChoiceStore
import kr.artel.orchestration.tracker.service.TrackerSetupStateService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
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
    private val stateService: TrackerSetupStateService,
    private val choiceStore: TrackerInstallationChoiceStore
) {
    @Operation(
        summary = "GitHub App 설치 주소",
        description = "소유자만. state가 서명되어 있어 남의 프로젝트에 설치를 붙일 수 없다."
    )
    @GetMapping("/install-url")
    suspend fun installUrl(
        @PathVariable projectId: Long,
        @CurrentUserId appUserId: Long
    ): TrackerInstallUrlResponse = signedUrl(projectId, appUserId, installationService::installUrl)

    @Operation(
        summary = "GitHub App 사용자 인가 주소",
        description = "소유자만. 이미 App이 설치된 계정을 고르는 흐름의 시작이다. " +
            "인가가 끝나면 /api/tracker/github/setup 이 tracker=choose 로 되돌린다."
    )
    @GetMapping("/authorize-url")
    suspend fun authorizeUrl(
        @PathVariable projectId: Long,
        @CurrentUserId appUserId: Long
    ): TrackerInstallUrlResponse = signedUrl(projectId, appUserId, installationService::authorizeUrl)

    @Operation(
        summary = "고를 수 있는 installation 목록",
        description = "소유자만. 사용자 인가 복귀에서 서버가 GitHub 에서 읽어 둔 목록이다. " +
            "없거나 만료됐으면 409 tracker_authorization_required."
    )
    @GetMapping("/installations")
    suspend fun installations(
        @PathVariable projectId: Long,
        @CurrentUserId appUserId: Long
    ): TrackerInstallationPageResponse {
        linkService.requireOwnerForSetup(projectId, appUserId)
        val installations = choiceStore.read(projectId, appUserId)
            ?: throw authorizationRequired()
        return TrackerInstallationPageResponse(
            installations.map {
                TrackerInstallationResponse(
                    installationId = it.id.toString(),
                    account = it.account,
                    accountType = it.accountType
                )
            }
        )
    }

    @Operation(
        summary = "installation 연결",
        description = "소유자만. 사용자 인가로 확인한 목록 안의 installation만 붙는다. " +
            "installation이 바뀌면 고른 저장소는 비워진다."
    )
    @PutMapping("/installation")
    suspend fun selectInstallation(
        @PathVariable projectId: Long,
        @CurrentUserId appUserId: Long,
        @Valid @RequestBody request: TrackerInstallationSelectRequest
    ): TrackerLinkEnvelope {
        // 404 와 403 을 먼저 판정한다. 목록 유무(409)나 id 형식(400)이 먼저 나가면 참여자가 아닌
        // 사람에게도 이 프로젝트에 대한 단서가 생긴다.
        linkService.requireOwnerForSetup(projectId, appUserId)
        val installationId = request.installationId.trim().toLongOrNull()
            ?: throw installationNotVerified()
        val installations = choiceStore.read(projectId, appUserId)
            ?: throw authorizationRequired()
        // ⚠️ 클라이언트가 보낸 id 를 믿지 않는다. 서버가 GitHub 에서 직접 읽어 둔 목록에 있어야 한다.
        if (installations.none { it.id == installationId }) {
            throw installationNotVerified()
        }
        linkService.attachInstallation(
            projectId = projectId,
            userId = appUserId,
            provider = TrackerProvider.GITHUB,
            installationRef = installationId.toString()
        )
        choiceStore.delete(projectId, appUserId)
        return TrackerLinkEnvelope(linkService.read(projectId, appUserId, TrackerProvider.GITHUB))
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

    /**
     * 설치 주소와 인가 주소가 같이 쓰는 순서. 둘 다 `link` 를 만드는 흐름의 시작이므로 조회 endpoint 라도
     * 판정은 쓰기와 같은 OWNER 다.
     */
    private suspend fun signedUrl(
        projectId: Long,
        appUserId: Long,
        urlOf: (String) -> String
    ): TrackerInstallUrlResponse {
        linkService.requireOwnerForSetup(projectId, appUserId)
        val state = stateService.issue(projectId, appUserId, TrackerProvider.GITHUB)
        return TrackerInstallUrlResponse(urlOf(state))
    }

    private fun authorizationRequired() = TrackerAuthorizationRequiredException(
        "GitHub 인가가 필요합니다. 다시 연결을 시작해 주세요."
    )

    private fun installationNotVerified() = TrackerInstallationNotVerifiedException(
        "GitHub 인가로 확인된 installation이 아닙니다."
    )
}
