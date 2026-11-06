package kr.artel.orchestration.project.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.project.dto.ProjectMemberResponse
import kr.artel.orchestration.project.service.ProjectMemberService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 프로젝트 멤버 REST(코루틴). 컨트롤러는 얇게 — 인가와 비즈니스는 [ProjectMemberService]가 하고,
 * 없거나 권한이 없는 경우는 서비스가 404·403으로 갈라 던진다.
 */
@Tag(name = "Project Member", description = "프로젝트 멤버 조회·내보내기")
@RestController
@RequestMapping("/api/projects/{projectId}/members")
class ProjectMemberController(
    private val projectMemberService: ProjectMemberService
) {
    @Operation(
        summary = "멤버 목록",
        description = "참여자면 누구나 볼 수 있다. 소유자가 먼저, 그 안에서는 참여한 순서다. 참여자가 아니면 404."
    )
    @GetMapping
    suspend fun list(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long
    ): List<ProjectMemberResponse> =
        projectMemberService.list(projectId, appUserId)

    @Operation(
        summary = "멤버 내보내기",
        description = "소유자만 가능하다. 마지막 소유자는 내보낼 수 없다(409)."
    )
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun remove(
        @CurrentUserId requesterId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "내보낼 사용자 id", required = true) @PathVariable userId: Long
    ) {
        projectMemberService.remove(projectId, requesterId, userId)
    }
}
