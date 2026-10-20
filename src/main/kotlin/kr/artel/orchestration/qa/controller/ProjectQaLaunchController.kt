package kr.artel.orchestration.qa.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.qa.dto.QaRunListResponse
import kr.artel.orchestration.qa.dto.SinceLastQaResponse
import kr.artel.orchestration.qa.service.QaLaunchReadService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * QA 실행 화면이 읽는 프로젝트 스코프 조회(ARTEL-1083). 읽기만 한다.
 *
 * 실행 시작은 그대로 `POST /api/qa-runs` 다. 여기는 그 화면의 빠른 시작 · 최근 실행 · 지난 실행 이후가
 * 읽는 값만 낸다. 비참여자에게는 존재하지 않는 것처럼 404 다(`ProjectIssueController` 와 같은 갈래).
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
class ProjectQaLaunchController(
    private val service: QaLaunchReadService,
) {
    /**
     * 프로젝트의 QA 실행, 최신순. `testRunId` 를 주면 그 테스트 런의, `gameBuildId` 를 주면 그 빌드로 돈 실행만.
     *
     * 개수는 `limit`(1~50, ARTEL-1083) 또는 `size`(1~100, ARTEL-1104) 로 받는다 — 두 화면이 이 경로를 따로 만들어 이름이
     * 갈렸다(ARTEL-1161). 둘 다 주면 `limit` 이다. 둘 다 없으면 20 이다.
     */
    @GetMapping("/qa-runs")
    suspend fun listQaRuns(
        @PathVariable projectId: Long,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) testRunId: Long?,
        @RequestParam(required = false) gameBuildId: String?,
        @CurrentUserId appUserId: Long,
    ): QaRunListResponse {
        if (limit != null && limit !in 1..QaLaunchReadService.MAX_LIMIT) {
            throw BadRequestException("limit 은 1~${QaLaunchReadService.MAX_LIMIT} 이어야 합니다.")
        }
        if (size != null && size !in 1..QaLaunchReadService.MAX_SIZE) {
            throw BadRequestException("size must be between 1 and ${QaLaunchReadService.MAX_SIZE}")
        }
        val buildId = gameBuildId?.let { it.toLongOrNull() ?: throw BadRequestException("invalid id: $it") }
        return service.listProjectRuns(projectId, appUserId, limit ?: size ?: DEFAULT_COUNT, testRunId, buildId)
    }

    /** 테스트 런의 마지막 실행 · 그 실행의 이슈 · 그 뒤로 바뀐 것. */
    @GetMapping("/test-runs/{runId}/since-last-qa")
    suspend fun sinceLastQa(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @CurrentUserId appUserId: Long,
    ): SinceLastQaResponse =
        service.sinceLastQa(projectId, runId, appUserId)

    private companion object {
        const val DEFAULT_COUNT = 20
    }
}
