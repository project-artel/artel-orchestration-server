package kr.artel.orchestration.project.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.project.dto.CreateInvitationRequest
import kr.artel.orchestration.project.dto.ProjectInvitationResponse
import kr.artel.orchestration.project.service.ProjectInvitationService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 보내는 쪽의 초대 REST(코루틴). 받는 쪽은 [InvitationController]에 있다.
 *
 * 둘을 가른 것은 자격이 다르기 때문이다. 여기는 프로젝트 OWNER여야 하고, 받는 쪽은 로그인한 계정의
 * 이메일이 초대의 이메일과 같아야 한다. 받는 사람은 아직 멤버가 아니라서 이 경로 아래에 두면
 * "멤버가 아니면 404" 규칙에 먼저 걸린다.
 */
@Tag(name = "Project Invitation", description = "프로젝트 초대 발송·조회·취소")
@RestController
@RequestMapping("/api/projects/{projectId}/invitations")
class ProjectInvitationController(
    private val projectInvitationService: ProjectInvitationService
) {
    @Operation(
        summary = "초대 보내기",
        description = "소유자만 가능하다. 부를 사람은 `email` 이나 `appUserId` 중 정확히 하나로 " +
            "가리킨다. `email` 은 ARTEL 계정이 없는 주소도 된다. 둘 다 오거나 둘 다 없으면 400 " +
            "(`invitation_target_ambiguous`), `appUserId` 가 가리키는 계정이 없거나 확인된 이메일이 " +
            "없으면 409 (`invitation_target_unreachable`). 이미 멤버이거나 이미 기다리는 초대가 " +
            "있어도 409."
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Valid @RequestBody request: CreateInvitationRequest
    ): ProjectInvitationResponse =
        projectInvitationService.create(projectId, appUserId, request)

    @Operation(
        summary = "보낸 초대 목록",
        description = "소유자만 볼 수 있다. 아직 답을 기다리는 초대만 나온다."
    )
    @GetMapping
    suspend fun list(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long
    ): List<ProjectInvitationResponse> =
        projectInvitationService.listForProject(projectId, appUserId)

    @Operation(summary = "초대 취소", description = "소유자만 가능하다. 이미 처리된 초대면 409.")
    @DeleteMapping("/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun revoke(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "초대 id", required = true) @PathVariable invitationId: Long
    ) {
        projectInvitationService.revoke(projectId, appUserId, invitationId)
    }
}
