package kr.artel.orchestration.qa.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.qa.entity.QaLogEntity
import kr.artel.orchestration.qa.entity.QaRunEntity
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.entity.QaTryScoreEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant


/** QA 실행 런(TR) 단위 리포지토리 (ARTEL-259). 한 게임 인스턴스에 활성 런은 하나. */
interface QaRunRepository : CoroutineCrudRepository<QaRunEntity, Long> {

    /**
     * 이 TR 로 돌린 QA 런 수. **TR 삭제를 막는 근거다**(ARTEL-487).
     *
     * `qa_run.test_run_id` 는 cascade 없는 외래키라, 실행 이력이 있는 TR 을 지우면 DB 가 거절해
     * 500 으로 튄다. 세어 보고 미리 409 로 답하면 사용자는 무엇이 막는지 읽을 수 있다.
     */
    suspend fun countByTestRunId(testRunId: Long): Long

    @Query(
        """
        SELECT * FROM qa_run
        WHERE game_instance_id = :gameInstanceId AND status IN ('STARTING', 'RUNNING')
        """
    )
    suspend fun findActiveByGameInstanceId(gameInstanceId: Long): QaRunEntity?

    /** 런은 그 test_run이 속한 프로젝트의 멤버에게만 보인다(qa_try 조회와 같은 가시성 규칙). */
    @Query(
        """
        SELECT qr.* FROM qa_run qr
        JOIN test_run tr ON tr.id = qr.test_run_id
        JOIN project_member pm ON pm.project_id = tr.project_id
        WHERE qr.id = :id AND pm.app_user_id = :userId
        """
    )
    suspend fun findAccessibleById(id: Long, userId: Long): QaRunEntity?

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

    /** 한 런의 시나리오별 qa_try를 적재 순서(id 오름차순 = 시나리오 순서)로. */
    @Query("SELECT * FROM qa_try WHERE qa_run_id = :qaRunId ORDER BY id ASC")
    fun findByQaRunId(qaRunId: Long): Flow<QaTryEntity>

    /**
     * One project's runs, newest first. Membership is what makes them visible — unless
     * [seesAllProjects], which the `DEVELOPER` platform role sets. 판단은
     * `PlatformAccessService`가 하고 여기는 그 결과만 받는다.
     */
    @Query(
        """
        SELECT qt.* FROM qa_try qt
        JOIN test_scenario ts ON ts.id = qt.test_scenario_id
        WHERE ts.project_id = :projectId
          AND (:seesAllProjects OR EXISTS (
              SELECT 1 FROM project_member pm
               WHERE pm.project_id = ts.project_id AND pm.app_user_id = :userId
          ))
        ORDER BY qt.id DESC
        LIMIT :limit
        """
    )
    fun findByProject(projectId: Long, userId: Long, seesAllProjects: Boolean, limit: Int): Flow<QaTryEntity>

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

    /**
     * Copies the verdict the Agent reported onto the try (ARTEL-299).
     *
     * Separate from [transition] rather than folded into it. Only a terminal STATUS
     * frame can carry a verdict, while `transition` is shared by cancellation,
     * failure cleanup, and the run's own close — folding four columns in would make
     * every one of those callers drag NULLs it has nothing to say about.
     *
     * The counts are `Long` and go straight into `INT` columns on purpose. Narrowing
     * them in Kotlin would store a number the Agent never reported; letting the
     * column type reject it drops the report instead, which the caller logs. A wrong
     * verdict is worse than a missing one.
     *
     * Every value is nullable and written as given: an absent field means the Agent
     * did not report that count, and NULL is how the table says so.
     */
    @Modifying
    @Query(
        """
        UPDATE qa_try
        SET steps_total = :stepsTotal,
            steps_passed = :stepsPassed,
            cases_total = :casesTotal,
            cases_passed = :casesPassed,
            updated_at = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun promoteVerdict(
        id: Long,
        stepsTotal: Long?,
        stepsPassed: Long?,
        casesTotal: Long?,
        casesPassed: Long?,
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

    /**
     * 이 런의 **스텝 판정** 프레임을 도착 순서로 준다(ARTEL-301 채점 입력).
     *
     * 스텝 판정은 `result`가 없고 `step`이 있는 STATUS 프레임이다 — 종단 프레임은 `result`를 실으므로
     * 그 조건 하나로 갈린다(라우터의 2-scope 규칙과 같은 기준). `direction`은 에이전트가 보낸 것만
     * 남기고 Orchestration이 스스로 남긴 STARTING/RUNNING/FAILED 상태 로그를 걷어낸다.
     *
     * 채점이 요약 대신 이 프레임들을 읽는 이유: 소켓이 죽은 런에는 요약이 없지만 그때까지의 스텝
     * 판정은 남아 있다. 요약만 보면 그런 런이 통째로 미보고가 되어, 실제로는 절반을 판정하고 죽은
     * 런과 아무것도 못 한 런이 같아진다.
     *
     * `payload ->> 'step'`을 쓴다(`payload ? 'step'` 아님) — `?`는 R2DBC 파라미터 자리로 먹힌다.
     */
    @Query(
        """
        SELECT * FROM qa_log
        WHERE qa_try_id = :qaTryId
          AND direction = 'AGENT_TO_ORCHE'
          AND type = 'STATUS'
          AND payload ->> 'result' IS NULL
          AND payload ->> 'step' IS NOT NULL
        ORDER BY id ASC
        """
    )
    fun findStepVerdicts(qaTryId: Long): Flow<QaLogEntity>
}

/** 채점 결과 저장소(ARTEL-301). 읽기는 후속 점수 화면이 쓴다. */
interface QaTryScoreRepository : CoroutineCrudRepository<QaTryScoreEntity, Long> {
    @Query("SELECT * FROM qa_try_score WHERE qa_try_id = :qaTryId ORDER BY id ASC")
    fun findByQaTryId(qaTryId: Long): Flow<QaTryScoreEntity>

    /**
     * 채점 결과를 남기되 **같은 (런, 채점자, 버전)이 이미 있으면 아무것도 하지 않는다.**
     *
     * 종료 경로가 여럿이라 한 런이 두 번 채점될 수 있다(예: 운영자가 취소한 직후 에이전트의 종단
     * 프레임이 지각 도착). 그때 먼저 남은 판정이 옳다 — 나중 것은 같은 입력에 대한 같은 계산이거나,
     * 이미 끝난 런을 다시 본 것이다. 예외로 만들면 그 지각 프레임이 런을 죽인다.
     *
     * 재채점은 `grader_version`을 올려서 한다. 그때는 충돌이 아니라 새 행이다.
     */
    @Modifying
    @Query(
        """
        INSERT INTO qa_try_score (qa_try_id, grader, grader_version, detail)
        VALUES (:qaTryId, :grader, :graderVersion, CAST(:detail AS jsonb))
        ON CONFLICT ON CONSTRAINT uq_qa_try_score DO NOTHING
        """
    )
    suspend fun insertIfAbsent(
        qaTryId: Long,
        grader: String,
        graderVersion: String,
        detail: String
    ): Int
}
