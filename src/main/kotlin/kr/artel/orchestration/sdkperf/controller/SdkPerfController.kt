package kr.artel.orchestration.sdkperf.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.sdkperf.dto.PerformanceBuildTrendResponse
import kr.artel.orchestration.sdkperf.dto.PerformanceRunDetailResponse
import kr.artel.orchestration.sdkperf.service.SdkPerfQueryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * SDK 성능 지표 조회 (ARTEL-378).
 *
 * 접근할 수 없는 프로젝트의 런·빌드는 403이 아니라 404다. 권한이 없는 사용자에게 "그 id는
 * 존재한다"를 알려주지 않기 위한 것으로, 확정된 계약이 그렇게 정하고 있다.
 */
@RestController
class SdkPerfController(private val queryService: SdkPerfQueryService) {

    @GetMapping("/api/qa-runs/{runId}/performance")
    suspend fun runPerformance(
        @CurrentUserId userId: Long,
        @PathVariable runId: Long
    ): PerformanceRunDetailResponse =
        queryService.runDetail(runId, userId) ?: throw NotFoundException("QA 런을 찾을 수 없습니다.")

    @GetMapping("/api/projects/{projectId}/game-builds/{gameBuildId}/performance")
    suspend fun buildPerformanceTrend(
        @CurrentUserId userId: Long,
        @PathVariable projectId: Long,
        @PathVariable gameBuildId: Long
    ): PerformanceBuildTrendResponse =
        queryService.buildTrend(projectId, gameBuildId, userId)
            ?: throw NotFoundException("게임 빌드를 찾을 수 없습니다.")
}
