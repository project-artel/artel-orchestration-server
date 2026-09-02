package kr.artel.orchestration.project.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import kr.artel.orchestration.project.entity.ProjectInvitationStatus
import kr.artel.orchestration.project.entity.ProjectRole
import java.time.Instant

/**
 * 초대 요청. 부를 사람을 가리키는 방법이 둘이고, 둘 중 정확히 하나를 써야 한다.
 *
 * [email] 은 ARTEL 계정이 아직 없는 사람도 부를 수 있는 길이라 없앨 수 없다. [appUserId] 는
 * `GET /api/projects/:projectId/invitation-suggestions` 가 고른 사람을 그대로 가리키는 길이고,
 * 그 응답에 이메일이 실리지 않으므로 화면이 대신 쓸 값이 필요하다.
 *
 * XOR 판정은 이 클래스가 아니라 `ProjectInvitationService` 가 한다. Bean Validation 으로 "둘 중
 * 하나"를 적으려면 클래스 수준 제약이 필요한데, 그러면 규칙이 서비스가 던지는 다른 초대 규칙들과
 * 떨어진 곳에 놓인다.
 *
 * @property email 부를 사람의 이메일. 저장할 때 소문자로 정규화한다
 * @property appUserId 부를 사람의 계정 id. 그 계정이 확인을 마친 주소로 초대가 나간다
 * @property role 수락했을 때 갖게 될 역할
 */
data class CreateInvitationRequest(
    @field:Email
    @field:Size(max = 320)
    val email: String? = null,

    val appUserId: String? = null,

    @field:NotNull
    val role: ProjectRole
)

/**
 * 초대 대상 후보 한 줄.
 *
 * 이메일 주소를 싣지 않는다. 이름으로 사람을 찾는 화면이 남의 주소를 알아내는 통로가 되면 안 된다
 * — 부르는 쪽은 [appUserId] 로 초대하면 되고, 주소는 서버가 그 계정에서 읽는다.
 *
 * [nickname] 과 [userTag] 는 따로 실어 보낸다. 화면이 `nickname#userTag` 로 붙여 그리고,
 * `UserHandle` 이 같은 형식을 만들고 가른다.
 *
 * @property appUserId 불투명 서버 식별자. 초대 요청의 `appUserId` 에 그대로 실어 보낸다
 * @property nickname 사용자가 고른 이름. `app_user.nickname` 은 NOT NULL 이라 항상 있다
 * @property userTag 같은 [nickname] 을 쓰는 사람을 가르는 번호. 서버가 배정하고 항상 있다
 * @property displayName 제공자가 준 이름. 항상 있다
 * @property login 가장 최근에 로그인한 제공자 신원의 login. 연결된 신원이 없으면 null
 * @property avatarUrl 그 신원의 아바타. 제공자가 주지 않았으면 null
 */
data class InvitationSuggestionResponse(
    val appUserId: String,
    val nickname: String,
    val userTag: String,
    val displayName: String,
    val login: String?,
    val avatarUrl: String?
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
