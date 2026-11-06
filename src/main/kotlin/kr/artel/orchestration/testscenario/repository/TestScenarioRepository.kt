package kr.artel.orchestration.testscenario.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

/**
 * TestScenario 엔티티에 대한 R2DBC 리액티브 리포지토리.
 *
 * 프로젝트당 시나리오가 여러 개일 수 있어 `findByProjectId`는 Flow를 반환한다.
 */
interface TestScenarioRepository : CoroutineCrudRepository<TestScenarioEntity, Long> {
    fun findByProjectId(projectId: Long): Flow<TestScenarioEntity>

    fun findByProjectIdAndReviewStatusAndReviewRequestedByOrderByIdAsc(
        projectId: Long,
        reviewStatus: String,
        reviewRequestedBy: Long,
    ): Flow<TestScenarioEntity>

    /** 사람이 본 내용 그대로일 때만 승인한다. 바뀐 수는 0 또는 1 이다. */
    @Modifying
    @Query(
        """
        UPDATE test_scenario
           SET review_status = 'READY', review_requested_by = NULL, review_edits_approved = FALSE, updated_at = now()
         WHERE id = :id AND project_id = :projectId AND review_status = 'DRAFT' AND updated_at = :expectedUpdatedAt
        """
    )
    suspend fun approveDraft(id: Long, projectId: Long, expectedUpdatedAt: Instant): Int

    /** 사람이 본 내용 그대로이고 새로 만든 시나리오일 때만 지운다. 바뀐 수는 0 또는 1 이다. */
    @Modifying
    @Query(
        """
        DELETE FROM test_scenario
         WHERE id = :id AND project_id = :projectId AND review_status = 'DRAFT' AND updated_at = :expectedUpdatedAt
           AND review_requested_by IS NOT NULL AND NOT review_edits_approved
        """
    )
    suspend fun rejectDraft(id: Long, projectId: Long, expectedUpdatedAt: Instant): Int
}
