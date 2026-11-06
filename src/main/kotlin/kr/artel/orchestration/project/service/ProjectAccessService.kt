package kr.artel.orchestration.project.service

import kr.artel.orchestration.common.error.ForbiddenException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import org.springframework.stereotype.Service

/**
 * 프로젝트 참여(멤버십) 인가 **공통 모듈**(코루틴). 여러 도메인이 각자 반복하던 `isMember` 확인을 한 곳으로 모은다.
 *
 * "참여자가 아니면 존재하지 않는 것처럼(빈 결과 → 404)" 원칙이라, [member]와 [isMember]는 예외를 던지지 않고
 * boolean/nullable로 판단만 제공한다(호출부가 null→404를 정한다).
 *
 * 호출부가 "없으면 404, 권한 없으면 403"을 그대로 원할 때는 [requireMember]와 [requireOwner]를 쓴다. 그 분기를
 * 호출부마다 다시 쓰면 어느 한 곳에서 삭제 여부나 역할 확인이 빠져도 아무도 모른다.
 */
@Service
class ProjectAccessService(
    private val projectRepository: ProjectRepository,
    private val projectMemberRepository: ProjectMemberRepository,
) {
    /** 참여 행(역할 포함) 또는 null. */
    suspend fun member(projectId: Long, userId: Long): ProjectMemberEntity? =
        projectMemberRepository.findByProjectIdAndAppUserId(projectId, userId)

    /** 프로젝트 참여자인지. */
    suspend fun isMember(projectId: Long, userId: Long): Boolean =
        member(projectId, userId) != null

    /**
     * 참여자에게만 프로젝트를 준다. 아니면 [NotFoundException].
     *
     * 멤버십만 보지 않고 [ProjectRepository.findAccessibleById]를 거치는 것이 핵심이다. `project_member` 행은
     * soft delete된 뒤에도 남으므로, 멤버십만 보는 확인은 삭제된 프로젝트의 참여자를 통과시킨다. 그러면 지운
     * 프로젝트에 계속 쓰기가 된다.
     */
    suspend fun requireMember(projectId: Long, userId: Long): ProjectEntity =
        projectRepository.findAccessibleById(projectId, userId)
            ?: throw projectNotFound()

    /**
     * OWNER에게만 프로젝트를 준다. 참여자가 아니면 [NotFoundException], 참여자지만 OWNER가 아니면
     * [ProjectAccessDeniedException].
     *
     * 두 갈래를 가르는 이유: 이미 프로젝트를 볼 수 있는 사람에게 404를 주는 것은 숨기는 시늉일 뿐이고,
     * 그 사람은 "없다"가 아니라 "권한이 없다"는 답을 받아야 다음에 무엇을 할지 안다.
     *
     * @param deniedMessage 403일 때 클라이언트가 받을 문장. 동작마다 다르므로 호출부가 정한다
     */
    suspend fun requireOwner(
        projectId: Long,
        userId: Long,
        deniedMessage: String
    ): ProjectEntity {
        val project = requireMember(projectId, userId)
        if (member(projectId, userId)?.role != ProjectRole.OWNER.name) {
            throw ProjectAccessDeniedException(deniedMessage)
        }
        return project
    }

    /** 참여자가 아닌 프로젝트는 존재 여부조차 알리지 않는다. */
    private fun projectNotFound() = NotFoundException("프로젝트를 찾을 수 없습니다.")
}

/**
 * 접근은 가능하지만 그 동작에 필요한 역할이 아닐 때. [kr.artel.orchestration.config.ApiExceptionHandler]가 403으로 옮긴다.
 *
 * 던지는 주체가 [ProjectAccessService]라 여기 산다. 패키지가 같아 이 클래스를 import하던 코드는 그대로 유효하다.
 */
class ProjectAccessDeniedException(message: String) : ForbiddenException(message)
