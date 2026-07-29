package kr.artel.orchestration.project.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface ProjectMemberRepository : CoroutineCrudRepository<ProjectMemberEntity, Long> {

    suspend fun findByProjectIdAndAppUserId(projectId: Long, appUserId: Long): ProjectMemberEntity?

    fun findByProjectId(projectId: Long): Flow<ProjectMemberEntity>

    /** 목록 한 줄마다 역할을 따로 조회하지 않도록 한 번에 가져온다. */
    fun findByAppUserIdAndProjectIdIn(
        appUserId: Long,
        projectIds: Collection<Long>
    ): Flow<ProjectMemberEntity>
}
