package kr.artel.orchestration.project.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.project.dto.ProjectInvitationResponse
import kr.artel.orchestration.project.service.ProjectInvitationService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 받은 초대함(코루틴). 보내는 쪽은 [ProjectInvitationController]에 있다.
 *
 * 프로젝트 경로 아래가 아닌 이유: 초대를 수락하는 사람은 아직 그 프로젝트의 멤버가 아니다.
 * `/api/projects/:projectId/...` 아래에 두면 "참여자가 아니면 404"가 먼저 걸려, 수락하려는
 * 바로 그 사람이 프로젝트를 찾을 수 없게 된다.
 *
 * 자격은 초대가 가리키는 대상과 로그인한 계정이 같은지다. 계정 대상 초대는
 * `project_invitation.app_user_id`가 로그인한 계정 id와 같은지 보고, 이메일 대상 초대는 로그인한
 * 계정의 확인된 `app_user.email`이 초대의 이메일과 같은지 본다.
 */
@Tag(name = "Invitation", description = "내가 받은 초대 조회·수락·거절")
@RestController
@RequestMapping("/api/invitations")
class InvitationController(
    private val projectInvitationService: ProjectInvitationService
) {
    @Operation(
        summary = "받은 초대 목록",
        description = "로그인한 계정으로 온 초대와 그 계정의 확인된 이메일로 온 초대 중 아직 " +
            "유효한 것만 나온다. 계정에 이메일이 없어도 계정으로 온 초대는 나온다."
    )
    @GetMapping
    suspend fun list(
        @CurrentUserId appUserId: Long
    ): List<ProjectInvitationResponse> =
        projectInvitationService.listForUser(appUserId)

    @Operation(
        summary = "초대 수락",
        description = "프로젝트 멤버가 된다. 대상이 아니면 403, 만료됐거나 이미 처리됐으면 409."
    )
    @PostMapping("/{invitationId}/accept")
    suspend fun accept(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "초대 id", required = true) @PathVariable invitationId: Long
    ): ProjectInvitationResponse =
        projectInvitationService.accept(appUserId, invitationId)

    @Operation(summary = "초대 거절", description = "멤버가 되지 않고 초대만 닫는다.")
    @PostMapping("/{invitationId}/decline")
    suspend fun decline(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "초대 id", required = true) @PathVariable invitationId: Long
    ): ProjectInvitationResponse =
        projectInvitationService.decline(appUserId, invitationId)
}
