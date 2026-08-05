package kr.artel.orchestration.qa.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.qa.entity.QaLogEntity
import kr.artel.orchestration.qa.entity.QaTryEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

interface QaTryRepository : CoroutineCrudRepository<QaTryEntity, Long> {
    @Query(
        """
        SELECT qt.* FROM qa_try qt
        JOIN test_scenario ts ON ts.id = qt.test_scenario_id
        JOIN project_member pm ON pm.project_id = ts.project_id
        WHERE qt.id = :id AND pm.app_user_id = :userId
        """
    )
    suspend fun findAccessibleById(id: Long, userId: Long): QaTryEntity?

    @Query(
        """
        SELECT qt.* FROM qa_try qt
        WHERE qt.game_instance_id = :gameInstanceId
          AND qt.status IN ('STARTING', 'RUNNING')
        """
    )
    suspend fun findActiveByGameInstanceId(gameInstanceId: Long): QaTryEntity?

    /** One project's runs, newest first. Membership is what makes them visible. */
    @Query(
        """
        SELECT qt.* FROM qa_try qt
        JOIN test_scenario ts ON ts.id = qt.test_scenario_id
        JOIN project_member pm ON pm.project_id = ts.project_id
        WHERE ts.project_id = :projectId AND pm.app_user_id = :userId
        ORDER BY qt.id DESC
        LIMIT :limit
        """
    )
    fun findByProject(projectId: Long, userId: Long, limit: Int): Flow<QaTryEntity>

    // @Modifying is what makes these return the affected row count. Without it
    // Spring Data R2DBC maps the statement as a result set, the suspend function
    // completes empty, and every `== 1` check below reads a successful update as a
    // failure — which then rolls the update back.
    @Modifying
    @Query(
        """
        UPDATE qa_try
        SET status = :nextStatus, completed_at = :completedAt, updated_at = :updatedAt
        WHERE id = :id AND status = :expectedStatus
        """
    )
    suspend fun transition(
        id: Long,
        expectedStatus: String,
        nextStatus: String,
        completedAt: Instant?,
        updatedAt: Instant
    ): Int

    /**
     * Attaches the Agent session and the settings that session resolved.
     *
     * One statement, not two: a run whose session is attached but whose settings
     * are still empty is a window in which the try looks started and is not
     * attributable, and nothing later goes back to fill it in.
     *
     * The settings are all nullable because an Agent that does not report them —
     * one deployed before it could — must still produce a running try. A run
     * missing from the comparison is a gap; a run that fails to start is an
     * outage.
     */
    @Modifying
    @Query(
        """
        UPDATE qa_try
        SET agent_session_id = :agentSessionId,
            model = :model,
            reasoning_effort = :reasoningEffort,
            prompt_version = :promptVersion,
            agent_arch = :agentArch,
            agent_fingerprint = :agentFingerprint,
            run_config = CAST(:runConfig AS jsonb),
            updated_at = :updatedAt
        WHERE id = :id AND status = 'STARTING' AND agent_session_id IS NULL
        """
    )
    suspend fun attachAgentSession(
        id: Long,
        agentSessionId: String,
        model: String?,
        reasoningEffort: String?,
        promptVersion: String?,
        agentArch: String?,
        agentFingerprint: String?,
        runConfig: String,
        updatedAt: Instant
    ): Int

    @Modifying
    @Query(
        """
        UPDATE qa_try
        SET status = 'FAILED', completed_at = :completedAt, updated_at = :completedAt
        WHERE game_instance_id = :gameInstanceId
          AND status IN ('STARTING', 'RUNNING')
        """
    )
    suspend fun failActiveByGameInstanceId(gameInstanceId: Long, completedAt: Instant): Int

    @Modifying
    @Query(
        """
        UPDATE qa_try
        SET status = 'FAILED', completed_at = :completedAt, updated_at = :completedAt
        WHERE id = :id AND status IN ('STARTING', 'RUNNING')
        """
    )
    suspend fun failActiveById(id: Long, completedAt: Instant): Int

    /**
     * Ends a run at the operator's request.
     *
     * Separate from [failActiveById] because a cancelled run is not a failed one:
     * the distinction is what the timeline and any later report read.
     */
    @Modifying
    @Query(
        """
        UPDATE qa_try
        SET status = 'CANCELLED', completed_at = :completedAt, updated_at = :completedAt
        WHERE id = :id AND status IN ('STARTING', 'RUNNING')
        """
    )
    suspend fun cancelActiveById(id: Long, completedAt: Instant): Int
}

interface QaLogRepository : CoroutineCrudRepository<QaLogEntity, Long> {
    @Query(
        """
        SELECT * FROM qa_log
        WHERE qa_try_id = :qaTryId AND (:beforeId IS NULL OR id < :beforeId)
        ORDER BY id DESC
        LIMIT :limit
        """
    )
    fun findPage(qaTryId: Long, beforeId: Long?, limit: Int): Flow<QaLogEntity>

    @Query(
        """
        SELECT * FROM qa_log
        WHERE qa_try_id = :qaTryId AND id > :afterId AND id <= :highWater
        ORDER BY id ASC
        """
    )
    fun findReplay(qaTryId: Long, afterId: Long, highWater: Long): Flow<QaLogEntity>

    @Query("SELECT COALESCE(MAX(id), 0) FROM qa_log WHERE qa_try_id = :qaTryId")
    suspend fun findHighWater(qaTryId: Long): Long

    suspend fun findByQaTryIdAndDirectionAndMessageId(
        qaTryId: Long,
        direction: String,
        messageId: String
    ): QaLogEntity?
}
