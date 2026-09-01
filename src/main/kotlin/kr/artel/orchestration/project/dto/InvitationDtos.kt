package kr.artel.orchestration.project.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import kr.artel.orchestration.project.entity.ProjectInvitationStatus
import kr.artel.orchestration.project.entity.ProjectRole
import java.time.Instant

/**
 * 초대 요청.
 *
 * @property email 부를 사람의 이메일. ARTEL 계정이 아직 없어도 된다. 저장할 때 소문자로 정규화한다
 * @property role 수락했을 때 갖게 될 역할
 */
data class CreateInvitationRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = 320)
    val email: String,

    @field:NotNull
    val role: ProjectRole
)

/**
 * 초대 한 건.
 *
 * 보낸 쪽(`/api/projects/:projectId/invitations`)과 받는 쪽(`/api/invitations`)이 같은 모양을 쓴다.
 * 받는 쪽에 [projectName]이 필요한데, 보낸 쪽에서 그 필드가 남는 것이 모양을 둘로 가르는 것보다 싸다.
 *
 * @property invitedBy 초대한 사람의 표시 이름. 그 사람이 지워졌으면 null
 * @property expiresAt 이 시각이 지나면 목록에 나오지 않고 수락도 되지 않는다
 */
data class ProjectInvitationResponse(
    val id: String,
    val projectId: String,
    val projectName: String,
    val email: String,
    val role: ProjectRole,
    val status: ProjectInvitationStatus,
    val invitedBy: String?,
    val createdAt: Instant,
    val expiresAt: Instant
)
