package kr.artel.orchestration.project.service

import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import org.springframework.stereotype.Service

/**
 * 프로젝트 참여(멤버십) 인가 **공통 모듈**(코루틴). 여러 도메인이 각자 반복하던 `isMember` 확인을 한 곳으로 모은다.
 *
 * "참여자가 아니면 존재하지 않는 것처럼(빈 결과 → 404)" 원칙이라, 예외를 던지지 않고 boolean/nullable로
 * 판단만 제공한다(호출부가 null→404를 정한다). 역할(OWNER 등)이 필요하면 [member]로 엔티티를 받아 확인한다.
 */
@Service
class ProjectAccessService(
    private val projectMemberRepository: ProjectMemberRepository,
) {
    /** 참여 행(역할 포함) 또는 null. */
    suspend fun member(projectId: Long, userId: Long): ProjectMemberEntity? =
        projectMemberRepository.findByProjectIdAndAppUserId(projectId, userId)

    /** 프로젝트 참여자인지. */
    suspend fun isMember(projectId: Long, userId: Long): Boolean =
        member(projectId, userId) != null
}
