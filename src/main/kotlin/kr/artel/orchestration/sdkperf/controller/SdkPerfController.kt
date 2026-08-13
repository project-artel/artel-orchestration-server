package kr.artel.orchestration.sdkperf.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.sdkperf.dto.PerformanceBuildTrendResponse
import kr.artel.orchestration.sdkperf.dto.PerformanceRunDetailResponse
import kr.artel.orchestration.sdkperf.service.SdkPerfQueryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class SdkPerfController(private val service:SdkPerfQueryService) {
    @GetMapping("/api/qa-runs/{runId}/performance")
    suspend fun run(@CurrentUserId userId:Long,@PathVariable runId:Long):PerformanceRunDetailResponse =
        service.runDetail(runId,userId) ?: throw NotFoundException("QA 런을 찾을 수 없습니다.")

    @GetMapping("/api/projects/{projectId}/game-builds/{gameBuildId}/performance")
    suspend fun build(@CurrentUserId userId:Long,@PathVariable projectId:Long,@PathVariable gameBuildId:Long):PerformanceBuildTrendResponse =
        service.buildTrend(projectId,gameBuildId,userId) ?: throw NotFoundException("게임 빌드를 찾을 수 없습니다.")
}
