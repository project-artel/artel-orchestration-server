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
 * 프로젝트 전체의 QA 실행 목록 `GET /api/projects/{projectId}/qa-runs` 는 [ProjectQaLaunchController.listQaRuns] 다.
 * 이쪽도 처음엔 같은 경로였다(ARTEL-1104 와 ARTEL-1083 이 따로 열었다). 그래서 빌드 아래로 옮겼다(ARTEL-1155).
 */
@Tag(name = "QA Run", description = "프로젝트의 QA run 목록")
@RestController
@RequestMapping("/api/projects/{projectId}/game-builds/{gameBuildId}/qa-runs")
class ProjectQaRunController(
    private val qaTryService: QaTryService
) {
    @Operation(
        summary = "빌드 하나로 돈 프로젝트의 QA run 목록",
        description = "새 것이 앞에 온다. `size` 는 1 이상 100 이하이고 벗어나면 400 이다. 프로젝트가 없거나 참여자가 아니면 404 다."
    )
    @GetMapping
    suspend fun list(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "게임 빌드 id", required = true) @PathVariable gameBuildId: Long,
        @RequestParam(defaultValue = "20") size: Int
    ): QaRunListResponse {
        if (size !in 1..100) throw BadRequestException("size must be between 1 and 100")
        return qaTryService.listRunsByProject(projectId, appUserId, gameBuildId, size)
            ?: throw NotFoundException("프로젝트를 찾을 수 없습니다.")
    }
}
