package kr.artel.orchestration.qa.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.qa.entity.QaLogEntity
import kr.artel.orchestration.qa.entity.QaRunEntity
import kr.artel.orchestration.qa.entity.QaTryEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant


/** QA 실행 런(TR) 단위 리포지토리 (ARTEL-259). 한 게임 인스턴스에 활성 런은 하나. */
interface QaRunRepository : CoroutineCrudRepository<QaRunEntity, Long> {
    @Query(
        """
        SELECT * FROM qa_run
        WHERE game_instance_id = :gameInstanceId AND status IN ('STARTING', 'RUNNING')
        """
    )
    suspend fun findActiveByGameInstanceId(gameInstanceId: Long): QaRunEntity?

    @Modifying
    @Query(
        """
        UPDATE qa_run
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

    /** 세션 부착 + Agent가 확정한 세션 공통 run_config를 한 문장으로 반영. */
    @Modifying
    @Query(
        """
        UPDATE qa_run
        SET agent_session_id = :agentSessionId,
            run_config = CAST(:runConfig AS jsonb),
            updated_at = :updatedAt
        WHERE id = :id AND status = 'STARTING' AND agent_session_id IS NULL
        """
    )
    suspend fun attachAgentSession(
        id: Long,
        agentSessionId: String,
        runConfig: String,
        updatedAt: Instant
    ): Int
}

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

    /** 시나리오에 딸린 QA 실행 이력 수. 시나리오 삭제 가드(실행 이력 있으면 차단)가 쓴다(ARTEL-207). */
    suspend fun countByTestScenarioId(testScenarioId: Long): Long

    /**
     * 시나리오의 모든 qa_try를 지운다(강제 삭제 경로). qa_log·issue는 qa_try FK가
     * ON DELETE CASCADE라 함께 사라진다. 부모 qa_run은 test_run 스코프라 건드리지 않는다.
     */
    @Modifying
    @Query("DELETE FROM qa_try WHERE test_scenario_id = :testScenarioId")
    suspend fun deleteByTestScenarioId(testScenarioId: Long): Int

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

    /**
     * 런 안 시나리오의 차례가 왔을 때 PENDING → RUNNING으로 활성한다(ARTEL-259). 세션 공통 설정을
     * 그 try에도 새겨 attribution을 남긴다. WHERE status='PENDING'이라 이미 돈 try는 안 건드린다.
     */
    @Modifying
    @Query(
        """
        UPDATE qa_try
        SET status = 'RUNNING',
            agent_session_id = :agentSessionId,
            model = :model,
            reasoning_effort = :reasoningEffort,
            prompt_version = :promptVersion,
            agent_arch = :agentArch,
            agent_fingerprint = :agentFingerprint,
            run_config = CAST(:runConfig AS jsonb),
            updated_at = :updatedAt
        WHERE id = :id AND status = 'PENDING'
        """
    )
    suspend fun activatePending(
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

    /** 런 시작 실패 시 그 런의 미종단 qa_try(PENDING/STARTING/RUNNING)를 모두 FAILED로 정리한다. */
    @Modifying
    @Query(
        """
        UPDATE qa_try
        SET status = 'FAILED', completed_at = :completedAt, updated_at = :completedAt
        WHERE qa_run_id = :qaRunId AND status IN ('PENDING', 'STARTING', 'RUNNING')
        """
    )
    suspend fun failByQaRunId(qaRunId: Long, completedAt: Instant): Int

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
