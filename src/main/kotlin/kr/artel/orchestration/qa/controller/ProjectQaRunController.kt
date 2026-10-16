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

/** 프로젝트 단위 QA run 목록. 한 run 의 조회와 시작은 [QaRunController] 다. */
@Tag(name = "QA Run", description = "프로젝트의 QA run 목록")
@RestController
@RequestMapping("/api/projects/{projectId}/qa-runs")
class ProjectQaRunController(
    private val qaTryService: QaTryService
) {
    @Operation(
        summary = "프로젝트의 QA run 목록",
        description = "새 것이 앞에 온다. `gameBuildId` 를 주면 그 빌드로 돈 run 만 온다. `size` 는 1 이상 100 이하이고 " +
            "벗어나면 400 이다. 프로젝트가 없거나 참여자가 아니면 404 다."
    )
    @GetMapping
    suspend fun list(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "이 게임 빌드로 돈 run 만") @RequestParam(required = false) gameBuildId: String?,
        @RequestParam(defaultValue = "20") size: Int
    ): QaRunListResponse {
        if (size !in 1..100) throw BadRequestException("size must be between 1 and 100")
        val buildId = gameBuildId?.let { it.toLongOrNull() ?: throw BadRequestException("invalid id: $it") }
        return qaTryService.listRunsByProject(projectId, appUserId, buildId, size)
            ?: throw NotFoundException("프로젝트를 찾을 수 없습니다.")
    }
}
