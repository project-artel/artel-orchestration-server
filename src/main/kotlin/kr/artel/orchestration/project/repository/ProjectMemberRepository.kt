package kr.artel.orchestration.project.repository

import kr.artel.orchestration.project.entity.ProjectMemberEntity
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface ProjectMemberRepository : ReactiveCrudRepository<ProjectMemberEntity, Long> {

    fun findByProjectIdAndAppUserId(projectId: Long, appUserId: Long): Mono<ProjectMemberEntity>

    fun findByProjectId(projectId: Long): Flux<ProjectMemberEntity>

    /** 목록 한 줄마다 역할을 따로 조회하지 않도록 한 번에 가져온다. */
    fun findByAppUserIdAndProjectIdIn(
        appUserId: Long,
        projectIds: Collection<Long>
    ): Flux<ProjectMemberEntity>
}
