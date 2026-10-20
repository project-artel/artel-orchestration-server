package kr.artel.orchestration.qa.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.qa.dto.QaRunListResponse
import kr.artel.orchestration.qa.service.QaTryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 빌드 하나로 돈 프로젝트의 QA run 목록. 한 run 의 조회와 시작은 [QaRunController] 다.
 *
 * 경로 `GET /api/projects/{projectId}/qa-runs` 는 [ProjectQaLaunchController.listQaRuns] 와 같다(ARTEL-1083 과
 * ARTEL-1104 가 같은 경로를 따로 열었다). 둘이 같은 조건으로 매핑되면 WebFlux 가 기동할 때 `Ambiguous mapping` 으로
 * 멈춘다. 그래서 이쪽은 `gameBuildId` 가 있을 때만 받는다 — home 의 빌드 상세가 늘 그것을 붙여 부르고, QA 실행
 * 화면은 붙이지 않는다. 같은 경로가 응답 모양 둘을 갖는 임시 상태이고, 경로를 가르는 정리는 따로 한다.
 */
@Tag(name = "QA Run", description = "프로젝트의 QA run 목록")
@RestController
@RequestMapping("/api/projects/{projectId}/qa-runs")
class ProjectQaRunController(
    private val qaTryService: QaTryService
) {
    @Operation(
        summary = "빌드 하나로 돈 프로젝트의 QA run 목록",
        description = "`gameBuildId` 가 있을 때만 이 API 다. 없으면 같은 경로의 QA 실행 목록(`QaRunSummaryListResponse`)이 답한다. " +
            "새 것이 앞에 온다. `size` 는 1 이상 100 이하이고 벗어나면 400 이다. 프로젝트가 없거나 참여자가 아니면 404 다."
    )
    @GetMapping(params = ["gameBuildId"])
    suspend fun list(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "이 게임 빌드로 돈 run 만", required = true) @RequestParam gameBuildId: String,
        @RequestParam(defaultValue = "20") size: Int
    ): QaRunListResponse {
        if (size !in 1..100) throw BadRequestException("size must be between 1 and 100")
        val buildId = gameBuildId.toLongOrNull() ?: throw BadRequestException("invalid id: $gameBuildId")
        return qaTryService.listRunsByProject(projectId, appUserId, buildId, size)
            ?: throw NotFoundException("프로젝트를 찾을 수 없습니다.")
    }
}
