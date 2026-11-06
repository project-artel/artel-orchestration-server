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
    @Column("qa_run_id") val qaRunId: Long?,
    @Column("qa_try_id") val qaTryId: Long,
    @Column("test_case_id") val testCaseId: Long?,
    val flagged: Boolean,
    @Column("ai_passed") val aiPassed: Boolean?,
    val decision: String?,
    val note: String?,
    @Column("decided_by") val decidedBy: Long?,
    @Column("decided_at") val decidedAt: Instant?,
    @Column("created_at") val createdAt: Instant,
)

/** TC 하나에 사람이 정한 판정 한 건(ARTEL-1256). 다음 QA 가 같은 TC 를 판정할 때 참고로 넘긴다. */
data class CaseDecisionRow(
    val testCaseId: Long,
    val aiPassed: Boolean?,
    val decision: String,
    val note: String?,
    val decidedAt: Instant?,
    val gameBuildVersion: String?,
    val caseSnapshot: String? = null,
    val caseVersionChanged: Boolean? = null,
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
        qaRunId: Long?,
        qaTryId: Long,
        testCaseId: Long?,
        aiPassed: Boolean?,
        decision: String,
        note: String?,
        decidedBy: Long,
        decidedAt: Instant,
    ): Int

    fun findByQaRunId(qaRunId: Long): Flow<QaVerdictReviewEntity>

    /**
     * TC 마다 사람이 정한 판정 중 최근 [perCase] 건(ARTEL-1256). 같은 판정을 다시 정했으면 마지막 결정 하나다.
     * 판정한 빌드의 버전을 함께 읽는다 — 다른 버전의 기록인지 agent 가 보게 한다.
     */
    @Query(
        """
        SELECT test_case_id, ai_passed, decision, note, decided_at, game_build_version, case_snapshot, case_version_changed
          FROM (
                SELECT r.test_case_id, r.ai_passed, r.decision, r.note, r.decided_at, gb.version AS game_build_version,
                       a.case_snapshot::text AS case_snapshot,
                       CASE WHEN a.id IS NULL THEN NULL ELSE
                         (a.case_snapshot->>'scene', a.case_snapshot->>'precondition',
                          COALESCE(a.case_snapshot->>'test_step', a.case_snapshot->>'step'),
                          COALESCE(a.case_snapshot->>'expected', a.case_snapshot->>'expected_value'))
                         IS DISTINCT FROM (c.scene, c.precondition, c.step, c.expected_value)
                       END AS case_version_changed,
                       ROW_NUMBER() OVER (PARTITION BY r.test_case_id ORDER BY r.decided_at DESC, r.qa_log_id DESC) AS rank
                  FROM qa_verdict_review r
                  LEFT JOIN qa_case_attempt a ON a.qa_log_id = r.qa_log_id
                  LEFT JOIN test_case c ON c.id = r.test_case_id
                  LEFT JOIN qa_run run ON run.id = r.qa_run_id
                  LEFT JOIN game_build gb ON gb.id = run.game_build_id
                 WHERE r.decision IS NOT NULL AND r.test_case_id IN (:testCaseIds)
               ) ranked
         WHERE rank <= :perCase
         ORDER BY test_case_id, decided_at DESC
        """
    )
    fun findDecisionsByCases(testCaseIds: Collection<Long>, perCase: Int): Flow<CaseDecisionRow>

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
