package kr.artel.orchestration.project.service

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.auth.entity.AppUserEntity
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.service.UserHandle
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.ForbiddenException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.dto.CreateInvitationRequest
import kr.artel.orchestration.project.dto.InvitationSuggestionResponse
import kr.artel.orchestration.project.dto.ProjectInvitationResponse
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectInvitationEntity
import kr.artel.orchestration.project.entity.ProjectInvitationStatus
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.InvitationSuggestionRepository
import kr.artel.orchestration.project.repository.ProjectInvitationRepository
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.repository.SuggestionMatch
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 초대의 수명. 설정이 아니라 상수인 이유는, stage와 prod가 초대를 다른 기간 뒤에 만료시킬 이유가
 * 없기 때문이다. 배포마다 다르게 두면 "내 초대가 왜 사흘 만에 죽었나"를 설정을 열어보기 전에는
 * 아무도 답할 수 없다. `StorageProperties`가 허용 형식을 상수로 둔 것과 같은 논거다.
 */
private val INVITATION_LIFETIME: Duration = Duration.ofDays(14)

/**
 * 제안을 시작하는 최소 글자 수. 한 글자로는 이 프로젝트 밖의 사람 대부분이 걸려, 검색이 아니라
 * 사용자 명단을 훑는 일이 된다.
 */
private const val MIN_SUGGESTION_QUERY_LENGTH = 2

/** 한 번에 내는 후보 수. 화면이 목록으로 그리는 개수이지 검색의 정확도가 아니다. */
private const val MAX_SUGGESTIONS = 10

/**
 * 초대가 가리키는 대상. [ProjectInvitationService.resolveTarget] 이 이 타입을 낸다.
 *
 * [ProjectInvitationEntity.email] 과 [ProjectInvitationEntity.appUserId] 중 정확히 하나만 차는
 * 것과 같은 모양이다 — 여기서 대상 종류를 가려 두면, [ProjectInvitationService.create] 는 그 뒤로
 * 원래 요청이 이메일이었는지 계정이었는지 다시 묻지 않고 이 타입 하나로 다룬다.
 */
private sealed interface InvitationTarget {
    /** 이미 계정이 있는 사람. 그 계정에 확인을 마친 주소가 없어도 된다 — 배달은 초대함이 한다. */
    data class Account(val appUserId: Long) : InvitationTarget

    /** 아직 계정이 없는 사람. 소문자로 정규화한 주소로 가리킨다. */
    data class Email(val address: String) : InvitationTarget
}

/**
 * 프로젝트에 사람을 부르고, 부름을 받은 사람이 답하는 경로(코루틴).
 *
 * 자격이 두 갈래다. 보내는 쪽(`/api/projects/:projectId/invitations`)은 프로젝트 OWNER여야 하고,
 * 받는 쪽(`/api/invitations`)은 로그인한 계정이 초대의 대상과 같아야 한다 — 계정 대상 초대는
 * 계정 id가, 이메일 대상 초대는 로그인한 계정의 확인된 이메일이 초대의 이메일과 같은지를 본다.
 * 받는 사람은 아직 그 프로젝트의 멤버가 아니라서, 멤버십으로는 판정할 수 없다.
 */
@Service
class ProjectInvitationService(
    private val invitationRepository: ProjectInvitationRepository,
    private val memberRepository: ProjectMemberRepository,
    private val projectRepository: ProjectRepository,
    private val appUserRepository: AppUserRepository,
    private val suggestionRepository: InvitationSuggestionRepository,
    private val projectAccessService: ProjectAccessService,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock
) {
    /**
     * 초대를 만든다. OWNER만 할 수 있다.
     *
     * 같은 대상으로 이미 기다리는 초대가 있으면 409다. 그 판정은 조회가 아니라 제약이 한다 —
     * 이메일 대상은 `uk_project_invitation_pending`, 계정 대상은
     * `uk_project_invitation_pending_app_user`. 조회로 먼저 확인하면 두 요청 사이에 경합이 나고,
     * 그 사이로 두 번째 초대가 들어온다.
     */
    suspend fun create(
        projectId: Long,
        userId: Long,
        request: CreateInvitationRequest
    ): ProjectInvitationResponse {
        val now = Instant.now(clock)

        return transactionalOperator.executeAndAwait {
            val project = projectAccessService.requireOwner(
                projectId,
                userId,
                "초대는 소유자만 보낼 수 있습니다."
            )

            // 대상을 가리는 것이 requireOwner 뒤인 것은 순서가 중요해서다. 앞에 두면
            // `appUserId` 가 없는 계정을 가리킬 때 나는 404 `invitation_target_not_found` 를
            // 아무 로그인 사용자나 받을 수 있고, 있는 계정일 때는 requireOwner 가 내는 404
            // `not_found` 로 넘어간다. 두 code 가 갈리므로 남의 프로젝트 id 하나만 알면 번호를
            // 올려 가며 어느 app_user id 가 실재하는지 훑을 수 있다. 소유자를 먼저 확인하면
            // 그 두 답이 모두 소유자에게만 보인다.
            val target = resolveTarget(request)

            if (alreadyMember(projectId, target)) {
                throw AlreadyMemberException()
            }

            // 만료된 채 PENDING 으로 남은 초대를 먼저 거둔다. 그러지 않으면 그 행이 아래 unique
            // index 에 걸려 같은 사람을 다시 부를 수 없다. 만료된 초대는 목록에도 안 나오므로
            // (`findPendingByProjectId` 가 `expires_at > :now` 로 거른다) revoke 로 치울 id 를
            // 얻을 길도 없어, 그대로 두면 그 사람은 이 프로젝트에 영영 못 들어온다.
            //
            // REVOKED 인 것은 보낸 쪽이 끝냈기 때문이다. 만료를 status 로 저장하지 않는다는
            // V73 의 판단은 그대로다 — 여기서 쓰는 것은 때가 되어 뒤집는 배치가 아니라, 다시
            // 부르는 순간 길을 막고 있는 그 행 하나를 치우는 일이다.
            settleExpired(projectId, target, now)

            val saved = try {
                invitationRepository.save(
                    ProjectInvitationEntity(
                        projectId = projectId,
                        email = (target as? InvitationTarget.Email)?.address,
                        appUserId = (target as? InvitationTarget.Account)?.appUserId,
                        role = request.role.name,
                        status = ProjectInvitationStatus.PENDING.name,
                        invitedBy = userId,
                        createdAt = now,
                        expiresAt = now.plus(INVITATION_LIFETIME)
                    )
                )
            } catch (conflict: DataIntegrityViolationException) {
                // 거의 언제나 중복이다 — 이메일 대상이면 `uk_project_invitation_pending`, 계정
                // 대상이면 `uk_project_invitation_pending_app_user`. `role`은 ProjectRole이 CHECK와
                // 같은 값만 허용하고, `project_id`는 위 requireOwner가, `invited_by`는 인증이 이미
                // 실재를 보장한다. `ck_project_invitation_target`도 못 깬다 — 위 두 인자가
                // InvitationTarget 하나에서 나오므로 언제나 정확히 하나만 찬다.
                //
                // 하나 남는 것이 `app_user_id`의 foreign key다. resolveTarget이 그 계정을 이 같은
                // 트랜잭션 안에서 읽지만 그 읽기는 잠그지 않으므로, READ COMMITTED에서는 읽은 뒤
                // 넣기 전에 다른 트랜잭션이 그 계정을 지울 수 있다. 그때 이 catch는 "이미 초대를
                // 보낸 대상입니다"라고 답한다 — 틀린 말이지만, 계정이 방금 사라진 상황에서 소유자가
                // 할 일은 어느 쪽이든 같고(다시 부를 수 없다) 그 창은 밀리초 단위다. 제약 이름으로
                // 가르는 것은 이 catch가 답을 갈라야 할 만큼 그 경우가 잦아질 때 한다.
                throw DuplicateInvitationException()
            }

            toResponse(saved, project)
        }!!
    }

    /**
     * 초대가 가리킬 대상을 정한다. [CreateInvitationRequest.email] 과
     * [CreateInvitationRequest.appUserId] 중 정확히 하나여야 한다.
     *
     * 둘 다 오면 어느 쪽을 따를지 서버가 정할 근거가 없다. 부르는 쪽이 서로 다른 사람을 가리키는
     * 두 값을 보냈을 수 있고, 그때 조용히 하나를 고르면 화면이 보여 준 사람과 초대가 간 사람이
     * 달라진다. 둘 다 없으면 부를 사람이 없다.
     *
     * `appUserId` 로 가리킨 계정은 실재하는지만 본다. 확인을 마친 주소가 있는지는 더 이상 묻지
     * 않는다 — 초대는 웹 초대함으로 배달되므로, 그 계정에 주소가 없어도 초대는 닿는다.
     */
    private suspend fun resolveTarget(request: CreateInvitationRequest): InvitationTarget {
        val email = request.email?.trim()?.takeIf { it.isNotEmpty() }
        val appUserId = request.appUserId?.trim()?.takeIf { it.isNotEmpty() }

        if ((email == null) == (appUserId == null)) {
            throw InvitationTargetAmbiguousException()
        }
        if (email != null) return InvitationTarget.Email(email.lowercase())

        val id = appUserId!!.toLongOrNull() ?: throw InvitationTargetNotFoundException()
        appUserRepository.findById(id) ?: throw InvitationTargetNotFoundException()
        return InvitationTarget.Account(id)
    }

    /**
     * 이 프로젝트에 부를 수 있는 사람 중 [query] 에 걸리는 사람. OWNER만 볼 수 있다.
     *
     * 자격이 초대 발송과 같다. 이름으로 사람을 찾는 것은 초대를 보내기 위한 준비 단계이므로, 초대를
     * 보낼 수 없는 사람이 목록만 볼 수 있어야 할 이유가 없다.
     *
     * 두 글자 미만이면 조회조차 하지 않고 빈 목록이다. 한 글자로는 이 프로젝트 밖의 사람 대부분이
     * 걸려, 검색이 아니라 사용자 명단을 훑는 일이 된다.
     *
     * 무엇을 맞추는지는 [matchFor] 가 정하고, 이메일 전체 일치는 어느 쪽이든 함께 걸린다.
     */
    suspend fun suggest(projectId: Long, userId: Long, query: String): List<InvitationSuggestionResponse> {
        projectAccessService.requireOwner(
            projectId,
            userId,
            "초대 대상 제안은 소유자만 볼 수 있습니다."
        )

        val trimmed = query.trim()
        if (trimmed.length < MIN_SUGGESTION_QUERY_LENGTH) return emptyList()

        return suggestionRepository
            .search(
                projectId = projectId,
                query = trimmed.lowercase(),
                match = matchFor(trimmed),
                now = Instant.now(clock),
                limit = MAX_SUGGESTIONS
            )
            .map {
                InvitationSuggestionResponse(
                    appUserId = it.appUserId.toString(),
                    nickname = it.nickname,
                    userTag = it.userTag,
                    displayName = it.displayName,
                    login = it.login,
                    avatarUrl = it.avatarUrl
                )
            }
    }

    /**
     * 검색어를 보고 이름을 맞추는 방식을 고른다.
     *
     * `#` 이 있으면 [UserHandle] 이 **마지막 `#`** 에서 갈라 `nickname` 과 `user_tag` 를 함께,
     * 정확히 맞춘다. `Yuni#0042` 를 붙여넣어 한 사람을 찾는 길이다. 이때 접두사 검색은 함께 돌지
     * 않는다 — `Yuni#00` 처럼 반쯤 적은 것이 `Yuni` 로 시작하는 사람 전부를 끌고 오면, 한 사람을
     * 가리키려고 붙여넣은 글자가 명단 조회가 된다.
     *
     * `#` 이 있는데 `user_tag` 자리가 숫자가 아니면 가리키는 사람이 없다. 그때는 이름으로 아무것도
     * 맞추지 않고 이메일 전체 일치만 남긴다 — `#` 이 들어간 주소를 통째로 적었을 수 있어서다.
     */
    private fun matchFor(trimmed: String): SuggestionMatch {
        if (!trimmed.contains(UserHandle.SEPARATOR)) {
            return SuggestionMatch.Prefix(escapeLike(trimmed.lowercase()) + "%")
        }
        val handle = UserHandle.parse(trimmed) ?: return SuggestionMatch.EmailOnly
        return SuggestionMatch.Handle(handle.nickname, handle.userTag)
    }

    /**
     * `LIKE` 가 뜻을 갖는 글자를 막는다. 막지 않으면 `%` 하나만 넣어 조건을 참으로 만들 수 있고,
     * 그러면 접두사 검색이 사용자 명단 조회가 된다. `\` 를 먼저 바꿔야 뒤의 치환이 만든 escape 를
     * 다시 escape 하지 않는다.
     */
    private fun escapeLike(value: String): String = value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    /** 보낸 초대 목록. OWNER만 볼 수 있다. 답을 기다리는 것만 낸다. */
    suspend fun listForProject(projectId: Long, userId: Long): List<ProjectInvitationResponse> {
        val project = projectAccessService.requireOwner(
            projectId,
            userId,
            "초대 목록은 소유자만 볼 수 있습니다."
        )

        val invitations = invitationRepository
            .findPendingByProjectId(projectId, Instant.now(clock))
            .toList()
        val inviterNames = inviterNamesFor(invitations)
        val inviteeAccounts = inviteeAccountsFor(invitations)

        return invitations.map { toResponse(it, project, inviterNames, inviteeAccounts) }
    }

    /** 초대를 거둔다. OWNER만 할 수 있다. */
    suspend fun revoke(projectId: Long, userId: Long, invitationId: Long): Unit =
        transactionalOperator.executeAndAwait {
            projectAccessService.requireOwner(
                projectId,
                userId,
                "초대 취소는 소유자만 할 수 있습니다."
            )

            val invitation = invitationRepository.findById(invitationId)
            // 다른 프로젝트의 초대 id를 이 프로젝트 경로로 넣어도 아무것도 알려주지 않는다.
            if (invitation == null || invitation.projectId != projectId) {
                throw invitationNotFound()
            }

            settle(invitationId, ProjectInvitationStatus.REVOKED)
        }

    /**
     * 받은 초대함. 로그인한 계정으로 온 것과, 계정에 확인을 마친 이메일로 온 것을 합쳐 아직
     * 유효한 것만 낸다.
     *
     * 계정에 확인을 마친 이메일이 없어도 계정 대상 초대는 나온다 — 그 초대는 애초에 주소를 보지
     * 않는다. 이메일 쪽만 여전히 ARTEL-732 를 따른다: 주소가 적혀 있어도 확인 전이면 없는 것과
     * 같이 본다. 확인하지 않은 주소로 초대를 받을 수 있으면, 남의 주소를 적어 넣는 것만으로 그
     * 사람에게 간 초대를 가져갈 수 있다.
     *
     * 두 경로가 같은 행을 낼 수는 없다 — `ck_project_invitation_target` 이 한 초대의 대상을
     * 이메일이나 계정 중 하나로 강제한다. 그래도 합친 뒤 id 로 중복을 제거해 둔다. 지금은 방어가
     * 필요 없는 안전장치이지만, 그 제약이 나중에 느슨해져도 이 목록이 같은 초대를 두 번 보여 주는
     * 사고로 이어지지 않는다.
     */
    suspend fun listForUser(userId: Long): List<ProjectInvitationResponse> {
        val now = Instant.now(clock)
        val email = verifiedEmailOf(userId)

        val emailInvitations = email
            ?.let { invitationRepository.findPendingForEmail(it, now).toList() }
            ?: emptyList()
        val accountInvitations = invitationRepository.findPendingForAppUserId(userId, now).toList()

        val invitations = (emailInvitations + accountInvitations)
            .distinctBy { it.id }
            .sortedWith(compareByDescending<ProjectInvitationEntity> { it.createdAt }.thenByDescending { it.id })
        if (invitations.isEmpty()) return emptyList()

        val projectsById = projectRepository
            .findAllById(invitations.map { it.projectId })
            .toList()
            .associateBy { requireNotNull(it.id) }

        val inviterNames = inviterNamesFor(invitations)
        val inviteeAccounts = inviteeAccountsFor(invitations)

        return invitations.mapNotNull { invitation ->
            projectsById[invitation.projectId]?.let {
                toResponse(invitation, it, inviterNames, inviteeAccounts)
            }
        }
    }

    /**
     * 수락한다. 계정 대상 초대는 로그인한 계정이 그 대상과 같아야 하고, 이메일 대상 초대는 초대의
     * 이메일이 로그인한 계정의 이메일과 같아야 한다. 판정은 [requireAddressedTo] 가 한다.
     *
     * 조건부 UPDATE를 먼저 하고 멤버 행을 나중에 넣는다. 그 UPDATE가 직렬화 지점이라, 동시에 들어온
     * 수락 중 하나만 1행을 받는다. 트랜잭션으로 감싸는 것이 함께 필요하다 — 감싸지 않으면 진 쪽이
     * 409를 받고도 그 전에 넣은 멤버 행이 남는다.
     *
     * 이미 멤버인 경우에는 행을 새로 넣지 않고 초대만 닫는다. `uk_project_member_project_user`를
     * 때려 500이 나는 것을 막기 위해서다.
     *
     * 그때 기존 행의 역할을 초대의 역할로 맞추지 **않는다**. 참여는 들어올 때 한 번 정해지고, 그
     * 뒤로 초대는 역할을 건드리지 않는다.
     *
     * 위험이 양쪽으로 대칭이라 그렇다. 올려 주면 MEMBER를 OWNER로 초대해 수락시키는 것이 역할 변경
     * API 없는 역할 변경이 되고, 내려 주면 OWNER를 MEMBER로 초대해 수락시키는 것이 소유권을 뺏는
     * 길이 된다. 뒤쪽이 더 나쁘다 — 내보내기에는 마지막 OWNER를 막는 [LastOwnerException]이 있지만
     * 이 경로에는 그런 방어가 없어, 프로젝트가 주인 없이 남을 수 있다.
     *
     * 대신 응답에는 실제로 갖게 된 역할을 싣는다. 초대에 적힌 역할을 그대로 실으면 응답이 멤버십과
     * 다른 말을 한다.
     */
    suspend fun accept(userId: Long, invitationId: Long): ProjectInvitationResponse =
        transactionalOperator.executeAndAwait {
            val invitation = requireAddressedTo(userId, invitationId)
            val project = projectRepository.findActiveById(invitation.projectId)
                ?: throw invitationNotFound()

            settle(invitationId, ProjectInvitationStatus.ACCEPTED)

            val existing = memberRepository.findByProjectIdAndAppUserId(invitation.projectId, userId)
            val role = existing?.role ?: run {
                memberRepository.save(
                    ProjectMemberEntity(
                        projectId = invitation.projectId,
                        appUserId = userId,
                        role = invitation.role,
                        createdAt = Instant.now(clock)
                    )
                )
                invitation.role
            }

            toResponse(
                invitation.copy(
                    role = role,
                    status = ProjectInvitationStatus.ACCEPTED.name,
                    respondedAt = Instant.now(clock)
                ),
                project
            )
        }!!

    /** 거절한다. 멤버 행은 생기지 않는다. */
    suspend fun decline(userId: Long, invitationId: Long): ProjectInvitationResponse =
        transactionalOperator.executeAndAwait {
            val invitation = requireAddressedTo(userId, invitationId)
            val project = projectRepository.findActiveById(invitation.projectId)
                ?: throw invitationNotFound()

            settle(invitationId, ProjectInvitationStatus.DECLINED)

            toResponse(
                invitation.copy(
                    status = ProjectInvitationStatus.DECLINED.name,
                    respondedAt = Instant.now(clock)
                ),
                project
            )
        }!!

    /**
     * 이 초대가 이 사용자의 것인지 판정한다.
     *
     * 계정 대상 초대는 [ProjectInvitationEntity.appUserId] 가 [userId] 와 같은지만 본다 — 이메일을
     * 읽을 필요가 없다. 그 계정에 확인된 주소가 있는지도 상관없다.
     *
     * 이메일 대상 초대는 방향이 중요하다. 이메일로 사용자를 찾는 것이 아니라, 로그인한 사용자의
     * 이메일을 읽어 초대의 이메일과 맞춰 본다. `app_user.email`에 unique 제약이 없어 반대 방향은
     * 남의 초대를 가져가는 길이 된다. 읽는 이메일도 확인을 마친 것뿐이다(ARTEL-732) — 확인 전
     * 주소로 수락이 되면, 남의 주소를 적어 넣는 것이 그 사람의 초대를 가져가는 길이 된다.
     *
     * 초대가 없으면 404, 남의 것이면 403이다. 존재를 숨기지 않는 이유는 id를 찍어 맞히는 것으로
     * 알아낼 수 있는 것이 "그 번호의 초대가 있다"뿐이고, 그것이 누구에게 갔는지는 여전히 안 나오기
     * 때문이다.
     */
    private suspend fun requireAddressedTo(userId: Long, invitationId: Long): ProjectInvitationEntity {
        val invitation = invitationRepository.findById(invitationId) ?: throw invitationNotFound()

        val addressedToMe = if (invitation.appUserId != null) {
            invitation.appUserId == userId
        } else {
            verifiedEmailOf(userId)?.equals(invitation.email, ignoreCase = true) ?: false
        }
        if (!addressedToMe) {
            throw InvitationNotYoursException()
        }
        if (invitation.expiresAt <= Instant.now(clock)) {
            throw InvitationExpiredException()
        }
        return invitation
    }

    /**
     * PENDING을 벗어나게 한다. 이미 누가 처리했으면 0행이 걸리고 409다.
     *
     * 재요청을 멱등 성공으로 보지 않는다. 거절된 초대가 수락으로 뒤집히거나 그 반대가 조용히
     * 성공하면 안 된다.
     */
    /**
     * 이 대상에게 나갔다가 만료된 채 `PENDING` 으로 남은 초대를 거둔다. 없으면 아무 일도 없다.
     *
     * 다시 부르기 직전에 부른다. 그 행은 만료됐어도 `status` 가 `PENDING` 이라 unique index 를
     * 그대로 차지하고 있고, 목록에는 나오지 않아 revoke 로 치울 수도 없다. 치우지 않으면 같은
     * 사람을 이 프로젝트에 영영 다시 부를 수 없다.
     *
     * 이메일 대상은 주소가 없는 사람에게 초대를 다시 보내는 다른 길이라도 있지만, 계정 대상은
     * 그렇지 않다 — 확인된 주소가 없는 계정은 이 경로가 유일하다.
     */
    private suspend fun settleExpired(projectId: Long, target: InvitationTarget, now: Instant) {
        val status = ProjectInvitationStatus.REVOKED.name
        when (target) {
            is InvitationTarget.Account -> invitationRepository.settleExpiredForAppUser(
                projectId = projectId,
                appUserId = target.appUserId,
                status = status,
                respondedAt = now,
                now = now
            )

            is InvitationTarget.Email -> invitationRepository.settleExpiredForEmail(
                projectId = projectId,
                email = target.address,
                status = status,
                respondedAt = now,
                now = now
            )
        }
    }

    private suspend fun settle(invitationId: Long, status: ProjectInvitationStatus) {
        val settled = invitationRepository.settle(invitationId, status.name, Instant.now(clock))
        if (settled == 0) throw InvitationAlreadySettledException()
    }

    /**
     * 이 계정이 초대를 받을 수 있는 주소. 확인을 마치지 않았으면 null 이다.
     *
     * 방향이 중요하다. 이메일로 사용자를 찾는 것이 아니라, 로그인한 사용자의 행에서 주소를 읽는다.
     * 반대 방향은 같은 주소를 가진 행이 여럿일 때 남의 초대를 가져가는 길이 된다.
     */
    private suspend fun verifiedEmailOf(userId: Long): String? =
        appUserRepository.findById(userId)?.takeIf { it.emailVerifiedAt != null }?.email

    /**
     * 이 대상이 이미 이 프로젝트의 멤버인지.
     *
     * 계정 대상이면 그 계정의 멤버십을 바로 본다. 이메일 대상이면 그 주소를 확인까지 마친 계정만
     * 센다 — 확인 전 주소는 어차피 초대를 받을 수 없으므로, 그 주소를 적어 둔 계정이 있다는 것이
     * 초대를 막을 근거가 못 된다.
     *
     * 이메일 쪽은 `uk_app_user_verified_email`이 확인된 주소를 하나로 묶어 주지만, 이 확인은
     * 여전히 최선을 다하는 것일 뿐 보장이 아니다. 확실한 방어선은 [accept]가 멤버 행을 두 번
     * 넣지 않는 것이다.
     */
    private suspend fun alreadyMember(projectId: Long, target: InvitationTarget): Boolean =
        when (target) {
            is InvitationTarget.Account ->
                memberRepository.findByProjectIdAndAppUserId(projectId, target.appUserId) != null

            is InvitationTarget.Email ->
                appUserRepository.findVerifiedByEmail(target.address)
                    .toList()
                    .any { user ->
                        user.id != null &&
                            memberRepository.findByProjectIdAndAppUserId(projectId, user.id) != null
                    }
        }

    /**
     * 초대한 사람의 표시 이름을 한 번에 읽는다. 목록 한 줄마다 따로 읽으면 N+1이 된다.
     *
     * `invited_by`는 그 사람이 지워지면 NULL이 되므로 없는 것이 정상 상태다.
     */
    private suspend fun inviterNamesFor(
        invitations: List<ProjectInvitationEntity>
    ): Map<Long, String> {
        val inviterIds = invitations.mapNotNull { it.invitedBy }.distinct()
        if (inviterIds.isEmpty()) return emptyMap()

        return appUserRepository.findAllById(inviterIds)
            .toList()
            .associate { requireNotNull(it.id) to it.displayName }
    }

    /**
     * 초대받은 계정의 표시 정보를 한 번에 읽는다. [inviterNamesFor]와 같은 이유로 목록 한 줄마다
     * 따로 읽지 않는다.
     *
     * 이메일 대상 초대는 `appUserId`가 없으므로 여기서 걸러진다 — 그 초대의 응답은
     * [ProjectInvitationResponse.nickname], [ProjectInvitationResponse.userTag],
     * [ProjectInvitationResponse.displayName] 이 모두 null 이다.
     */
    private suspend fun inviteeAccountsFor(
        invitations: List<ProjectInvitationEntity>
    ): Map<Long, AppUserEntity> {
        val accountIds = invitations.mapNotNull { it.appUserId }.distinct()
        if (accountIds.isEmpty()) return emptyMap()

        return appUserRepository.findAllById(accountIds)
            .toList()
            .associateBy { requireNotNull(it.id) }
    }

    private suspend fun toResponse(
        invitation: ProjectInvitationEntity,
        project: ProjectEntity
    ) = toResponse(
        invitation,
        project,
        inviterNamesFor(listOf(invitation)),
        inviteeAccountsFor(listOf(invitation))
    )

    private fun toResponse(
        invitation: ProjectInvitationEntity,
        project: ProjectEntity,
        inviterNames: Map<Long, String>,
        inviteeAccounts: Map<Long, AppUserEntity>
    ): ProjectInvitationResponse {
        val account = invitation.appUserId?.let { inviteeAccounts[it] }
        return ProjectInvitationResponse(
            id = requireNotNull(invitation.id).toString(),
            projectId = requireNotNull(project.id).toString(),
            projectName = project.name,
            email = invitation.email,
            nickname = account?.nickname,
            userTag = account?.userTag,
            displayName = account?.displayName,
            role = invitation.role.toRole(),
            status = invitation.status.toStatus(),
            invitedBy = invitation.invitedBy?.let { inviterNames[it] },
            createdAt = invitation.createdAt,
            expiresAt = invitation.expiresAt
        )
    }

    private fun String.toRole(): ProjectRole =
        ProjectRole.entries.firstOrNull { it.name == this } ?: ProjectRole.MEMBER

    private fun String.toStatus(): ProjectInvitationStatus =
        ProjectInvitationStatus.entries.firstOrNull { it.name == this }
            ?: ProjectInvitationStatus.PENDING

    private fun invitationNotFound() = NotFoundException("초대를 찾을 수 없습니다.")
}

/**
 * 이미 기다리는 초대가 같은 대상으로 있을 때. 이메일 대상이면 `uk_project_invitation_pending`이,
 * 계정 대상이면 `uk_project_invitation_pending_app_user`가 잡는다.
 */
class DuplicateInvitationException :
    ConflictException("이미 초대를 보낸 대상입니다.", code = "duplicate_invitation")

/** 부른 사람이 이미 이 프로젝트의 멤버일 때. 이메일로 불렀든 계정으로 불렀든 같다. */
class AlreadyMemberException :
    ConflictException("이미 이 프로젝트의 멤버입니다.", code = "already_member")

/** 초대가 이미 수락·거절·취소됐을 때. */
class InvitationAlreadySettledException :
    ConflictException("이미 처리된 초대입니다.", code = "invitation_already_settled")

/** 유효 기간이 지난 초대. */
class InvitationExpiredException :
    ConflictException("만료된 초대입니다.", code = "invitation_expired")

/** 부를 사람을 `email` 과 `appUserId` 둘 다로 가리켰거나, 둘 다 안 준 경우. */
class InvitationTargetAmbiguousException :
    BadRequestException(
        "초대할 사람은 이메일이나 계정 중 하나로만 가리킬 수 있습니다.",
        code = "invitation_target_ambiguous"
    )

/**
 * `appUserId` 가 실재하는 계정을 가리키지 않을 때 — 값 자체가 숫자가 아니거나, 그 id 의 계정이
 * 없을 때.
 *
 * 예전에는 "그 계정에 확인을 마친 이메일이 없다"도 같은 예외로 묶었다. `appUserId` 로 가리킨
 * 계정은 더 이상 주소를 요구하지 않으므로(초대는 웹 초대함으로 배달된다) 그 경우는 이제
 * 성공이고, 이 예외에는 "그런 계정이 없다"만 남는다.
 *
 * 그렇게 되면서 예전 KDoc 이 적어 둔 우려 — 응답을 가르면 어느 id 가 실재하는지 번호를 올려 가며
 * 훑을 수 있다 — 가 형태를 바꿔 남는다. 이제 404 와 201 이 그 답을 준다. 그래도 이 갈래를 두는
 * 것은, 성공한 쪽이 조용하지 않기 때문이다. 훑으려면 실제 초대가 만들어져 그 사람의 초대함에
 * 뜨고 소유자의 보낸 목록에도 남는다. 답만 받고 흔적을 남기지 않는 oracle 이 아니다.
 */
class InvitationTargetNotFoundException :
    NotFoundException(
        "그런 계정을 찾을 수 없습니다.",
        code = "invitation_target_not_found"
    )

/** 다른 계정이나 다른 주소로 간 초대이거나, 이메일 대상 초대인데 로그인한 계정에 확인을 마친 이메일이 없을 때. */
class InvitationNotYoursException :
    ForbiddenException("이 초대의 대상이 아닙니다.", code = "invitation_not_yours")
