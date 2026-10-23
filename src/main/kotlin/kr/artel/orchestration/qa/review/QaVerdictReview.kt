package kr.artel.orchestration.qa.review

import kotlinx.coroutines.flow.Flow
import org.springframework.data.annotation.Id
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

/**
 * 판정 하나의 추가 검토와 사람의 확정(ARTEL-1254). [qaLogId] 는 그 판정을 실은 STATUS 행이다.
 *
 * [flagged] 면 코드 검사가 추가 검토로 골랐다. [decision] 이 null 이면 아직 사람이 정하지 않았다.
 */
@Table("qa_verdict_review")
data class QaVerdictReviewEntity(
    @Id @Column("qa_log_id") val qaLogId: Long,
    @Column("qa_run_id") val qaRunId: Long,
    @Column("qa_try_id") val qaTryId: Long,
    @Column("test_case_id") val testCaseId: Long?,
    val flagged: Boolean,
    @Column("ai_passed") val aiPassed: Boolean,
    val decision: String?,
    val note: String?,
    @Column("decided_by") val decidedBy: Long?,
    @Column("decided_at") val decidedAt: Instant?,
    @Column("created_at") val createdAt: Instant,
)

/** 실행 하나의 추가 검토 수: 고른 것 전부와 그중 사람이 아직 정하지 않은 것. */
data class ReviewCountRow(val qaRunId: Long, val flagged: Int, val pending: Int)

interface QaVerdictReviewRepository : CoroutineCrudRepository<QaVerdictReviewEntity, Long> {

    /** 코드 검사가 고른 판정을 남긴다. 이미 있으면(다시 계산했거나 사람이 먼저 정했으면) 그대로 둔다. */
    @Modifying
    @Query(
        """
        INSERT INTO qa_verdict_review (qa_log_id, qa_run_id, qa_try_id, test_case_id, flagged, ai_passed)
        VALUES (:qaLogId, :qaRunId, :qaTryId, :testCaseId, TRUE, :aiPassed)
        ON CONFLICT (qa_log_id) DO NOTHING
        """
    )
    suspend fun flag(qaLogId: Long, qaRunId: Long, qaTryId: Long, testCaseId: Long?, aiPassed: Boolean): Int

    /** 사람이 판정을 정한다. 고르지 않은 판정이어도 행을 만들고(flagged = false), 다시 정하면 덮어쓴다. */
    @Modifying
    @Query(
        """
        INSERT INTO qa_verdict_review (qa_log_id, qa_run_id, qa_try_id, test_case_id, flagged, ai_passed,
                                       decision, note, decided_by, decided_at)
        VALUES (:qaLogId, :qaRunId, :qaTryId, :testCaseId, FALSE, :aiPassed, :decision, :note, :decidedBy, :decidedAt)
        ON CONFLICT (qa_log_id) DO UPDATE
           SET decision = EXCLUDED.decision, note = EXCLUDED.note,
               decided_by = EXCLUDED.decided_by, decided_at = EXCLUDED.decided_at
        """
    )
    suspend fun decide(
        qaLogId: Long,
        qaRunId: Long,
        qaTryId: Long,
        testCaseId: Long?,
        aiPassed: Boolean,
        decision: String,
        note: String?,
        decidedBy: Long,
        decidedAt: Instant,
    ): Int

    fun findByQaRunId(qaRunId: Long): Flow<QaVerdictReviewEntity>

    @Query(
        """
        SELECT qa_run_id,
               COUNT(*) AS flagged,
               COUNT(*) FILTER (WHERE decision IS NULL) AS pending
          FROM qa_verdict_review
         WHERE flagged AND qa_run_id IN (:qaRunIds)
         GROUP BY qa_run_id
        """
    )
    fun countByRuns(qaRunIds: Collection<Long>): Flow<ReviewCountRow>
}
