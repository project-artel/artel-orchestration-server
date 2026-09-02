package kr.artel.orchestration.project.dto

import kr.artel.orchestration.project.entity.ProjectRole
import java.time.Instant

/**
 * 멤버 목록 한 줄.
 *
 * id는 내부적으로 Long이지만 문자열로 내보낸다. `/api/auth/me`와 `/api/projects`가 이미 그렇게 하고
 * 있고, 클라이언트는 이 값을 파싱하지 않는 불투명 식별자로 다룬다.
 *
 * @property email 없을 수 있다. GitHub에서 공개 이메일을 받지 못한 계정이 그렇고, 그런 사람은 초대를
 *   받을 수도 없다. 화면이 그 사실을 말할 수 있도록 감추지 않고 그대로 낸다
 * @property nickname 사용자가 고른 이름. `app_user.nickname`에서 온다
 * @property userTag 같은 nickname을 쓰는 사람들을 가르는 번호. `app_user.user_tag`에서 온다.
 *   화면에 나가는 `nickname#userTag`는 클라이언트가 두 값을 붙여 만든다
 * @property joinedAt 참여한 시각. 프로젝트를 만든 사람이면 프로젝트 생성 시각과 같다
 */
data class ProjectMemberResponse(
    val userId: String,
    val displayName: String,
    val email: String?,
    val nickname: String,
    val userTag: String,
    val role: ProjectRole,
    val joinedAt: Instant
)
