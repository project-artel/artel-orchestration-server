package kr.artel.orchestration.project.service

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.auth.entity.AppUserEntity
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.dto.ProjectMemberResponse
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * 프로젝트에 누가 있는지 읽고, 내보낸다(코루틴).
 *
 * 초대는 [ProjectInvitationService]가 맡는다. 여기는 이미 참여가 확정된 행만 다룬다.
 */
@Service
class ProjectMemberService(
    private val memberRepository: ProjectMemberRepository,
    private val appUserRepository: AppUserRepository,
    private val projectAccessService: ProjectAccessService,
    private val transactionalOperator: TransactionalOperator
) {
    /** 참여자면 누구나 볼 수 있다. 누가 같이 있는지는 권한이 아니라 프로젝트의 사실이다. */
    suspend fun list(projectId: Long, userId: Long): List<ProjectMemberResponse> {
        projectAccessService.requireMember(projectId, userId)

        val members = memberRepository.findByProjectId(projectId).toList()
        if (members.isEmpty()) return emptyList()

        // 줄마다 사용자를 따로 읽으면 N+1이 된다. 한 번에 가져와 id로 맞춘다.
        val usersById = appUserRepository
            .findAllById(members.map { it.appUserId })
            .toList()
            .associateBy { requireNotNull(it.id) }

        return members
            .mapNotNull { member -> usersById[member.appUserId]?.let { member to it } }
            .map { (member, user) -> toResponse(member, user) }
            .sortedWith(compareBy({ it.role != ProjectRole.OWNER }, { it.joinedAt }))
    }

    /**
     * 멤버를 내보낸다. OWNER만 할 수 있다.
     *
     * 마지막 OWNER는 내보낼 수 없다. 나가면 그 프로젝트를 삭제하거나 사람을 부를 수 있는 사람이
     * 아무도 없어지고, 되돌릴 API가 없어 프로젝트가 그대로 갇힌다.
     *
     * OWNER가 자기를 내보내는 것은 막지 않는다 — 다른 OWNER가 있다면 그것은 나가기이지 사고가 아니다.
     */
    suspend fun remove(projectId: Long, userId: Long, targetUserId: Long): Unit =
        transactionalOperator.executeAndAwait {
            projectAccessService.requireOwner(
                projectId,
                userId,
                "멤버를 내보내는 것은 소유자만 할 수 있습니다."
            )

            val target = memberRepository.findByProjectIdAndAppUserId(projectId, targetUserId)
                ?: throw NotFoundException("프로젝트 멤버를 찾을 수 없습니다.")

            // 세는 것은 OWNER를 내보낼 때뿐이다. 앞 조건이 먼저 걸려 평범한 멤버를 내보낼 때는
            // COUNT가 나가지 않는다.
            if (target.role == ProjectRole.OWNER.name &&
                memberRepository.countByProjectIdAndRole(projectId, ProjectRole.OWNER.name) <= 1
            ) {
                throw LastOwnerException()
            }

            memberRepository.delete(target)
        }

    private fun toResponse(member: ProjectMemberEntity, user: AppUserEntity) =
        ProjectMemberResponse(
            userId = requireNotNull(user.id).toString(),
            displayName = user.displayName,
            email = user.email,
            nickname = user.nickname,
            battleTag = user.battleTag,
            role = member.role.toRole(),
            joinedAt = member.createdAt
        )

    /** 저장된 값이 현재 enum에 없으면 MEMBER로 떨어뜨린다. 권한을 넓히지 않는 쪽이 안전한 방향이다. */
    private fun String.toRole(): ProjectRole =
        ProjectRole.entries.firstOrNull { it.name == this } ?: ProjectRole.MEMBER
}

/** 마지막 OWNER를 내보내려 할 때. 프로젝트가 주인 없이 남는 상태를 만들지 않는다. */
class LastOwnerException :
    ConflictException("마지막 소유자는 내보낼 수 없습니다.", code = "last_owner")
