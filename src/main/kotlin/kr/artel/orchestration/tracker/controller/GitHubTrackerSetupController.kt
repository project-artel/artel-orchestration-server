package kr.artel.orchestration.tracker.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.CancellationException
import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.tracker.github.GitHubInstallationService
import kr.artel.orchestration.tracker.service.ProjectTrackerLinkService
import kr.artel.orchestration.tracker.service.TrackerSetupStateService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * GitHub 이 설치 후 사용자를 되돌려 보내는 자리.
 *
 * **여기서는 무슨 일이 있어도 예외를 밖으로 내보내지 않는다.** 이 요청을 여는 것은 브라우저이고,
 * 브라우저가 받아야 하는 것은 JSON 오류가 아니라 home 으로 돌아가는 302 다. 인증 실패도 마찬가지라
 * `SecurityConfig` 가 이 경로 전용 entry point 로 같은 리다이렉트를 준다(만료된 쿠키로 돌아오는
 * 경우 — 설치는 조직 승인까지 15분보다 오래 걸릴 수 있다).
 *
 * 경로가 `/api/` 아래인데도 인증 대상인 이유는 `project.md` 규칙 2 다 — 무인증 라우트를 `/api/`
 * 아래 두지 않는다. GitHub 의 복귀는 top-level navigation 이라 세션 쿠키가 실린다.
 */
@Tag(name = "Issue tracker", description = "GitHub App 설치 복귀")
@RestController
class GitHubTrackerSetupController(
    private val linkService: ProjectTrackerLinkService,
    private val installationService: GitHubInstallationService,
    private val stateService: TrackerSetupStateService,
    private val authProperties: AuthProperties
) {
    private val logger = LoggerFactory.getLogger(GitHubTrackerSetupController::class.java)

    @Operation(
        summary = "GitHub App 설치 복귀",
        description = "서명된 state로 프로젝트를 찾아 installation_id를 저장하고 home으로 되돌린다."
    )
    @GetMapping("/api/tracker/github/setup")
    suspend fun setup(
        @RequestParam(name = "installation_id", required = false) installationId: String?,
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<Void> {
        val verified = stateService.verify(state)
            ?: return redirect("${authProperties.frontendOrigin}/projects?tracker=failed")

        val settingsPath = "${authProperties.frontendOrigin}/projects/${verified.projectId}/settings"
        if (verified.userId != appUserId || installationId.isNullOrBlank()) {
            // state 를 발급받은 사람과 지금 돌아온 세션이 다르면 붙이지 않는다.
            return redirect("$settingsPath?tracker=failed")
        }

        return try {
            // ⚠️ installation_id 는 공격자가 고를 수 있는 쿼리 값이다. 소유를 확인하지 않으면 남의
            // installation 을 자기 프로젝트에 붙여 그쪽 private 저장소 목록을 읽을 수 있다.
            val owned = installationService.verifyInstallationBelongsToCaller(code, installationId)
            if (!owned) {
                logger.warn(
                    "설치 복귀에서 installation 소유 확인 실패: project={}, installation={}",
                    verified.projectId,
                    installationId
                )
                return redirect("$settingsPath?tracker=failed")
            }
            linkService.attachInstallation(
                projectId = verified.projectId,
                userId = appUserId,
                provider = verified.provider,
                installationRef = installationId
            )
            redirect("$settingsPath?tracker=connected")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn("GitHub App 설치 복귀 처리 실패: project={}", verified.projectId, error)
            redirect("$settingsPath?tracker=failed")
        }
    }

    private fun redirect(location: String): ResponseEntity<Void> =
        ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build()
}
