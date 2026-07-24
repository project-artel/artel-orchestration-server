package kr.artel.orchestration.qa.repository

import kr.artel.orchestration.qa.entity.QaLogEntity
import kr.artel.orchestration.qa.entity.QaTryEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Instant

interface QaTryRepository : ReactiveCrudRepository<QaTryEntity, Long> {
    @Query(
        """
        SELECT qt.* FROM qa_try qt
        JOIN test_scenario ts ON ts.id = qt.test_scenario_id
        JOIN project_member pm ON pm.project_id = ts.project_id
        WHERE qt.id = :id AND pm.app_user_id = :userId
        """
    )
    fun findAccessibleById(id: Long, userId: Long): Mono<QaTryEntity>

    @Query(
        """
        SELECT qt.* FROM qa_try qt
        WHERE qt.game_instance_id = :gameInstanceId
          AND qt.status IN ('STARTING', 'RUNNING')
        """
    )
    fun findActiveByGameInstanceId(gameInstanceId: Long): Mono<QaTryEntity>

    @Query(
        """
        UPDATE qa_try
        SET status = :nextStatus, completed_at = :completedAt, updated_at = :updatedAt
        WHERE id = :id AND status = :expectedStatus
        """
    )
    fun transition(
        id: Long,
        expectedStatus: String,
        nextStatus: String,
        completedAt: Instant?,
        updatedAt: Instant
    ): Mono<Int>

    @Query(
        """
        UPDATE qa_try
        SET agent_session_id = :agentSessionId, updated_at = :updatedAt
        WHERE id = :id AND status = 'STARTING' AND agent_session_id IS NULL
        """
    )
    fun attachAgentSession(id: Long, agentSessionId: String, updatedAt: Instant): Mono<Int>

    @Query(
        """
        UPDATE qa_try
        SET status = 'FAILED', completed_at = :completedAt, updated_at = :completedAt
        WHERE game_instance_id = :gameInstanceId
          AND status IN ('STARTING', 'RUNNING')
        """
    )
    fun failActiveByGameInstanceId(gameInstanceId: Long, completedAt: Instant): Mono<Int>

    @Query(
        """
        UPDATE qa_try
        SET status = 'FAILED', completed_at = :completedAt, updated_at = :completedAt
        WHERE id = :id AND status IN ('STARTING', 'RUNNING')
        """
    )
    fun failActiveById(id: Long, completedAt: Instant): Mono<Int>
}

interface QaLogRepository : ReactiveCrudRepository<QaLogEntity, Long> {
    @Query(
        """
        SELECT * FROM qa_log
        WHERE qa_try_id = :qaTryId AND (:beforeId IS NULL OR id < :beforeId)
        ORDER BY id DESC
        LIMIT :limit
        """
    )
    fun findPage(qaTryId: Long, beforeId: Long?, limit: Int): Flux<QaLogEntity>

    @Query(
        """
        SELECT * FROM qa_log
        WHERE qa_try_id = :qaTryId AND id > :afterId AND id <= :highWater
        ORDER BY id ASC
        """
    )
    fun findReplay(qaTryId: Long, afterId: Long, highWater: Long): Flux<QaLogEntity>

    @Query("SELECT COALESCE(MAX(id), 0) FROM qa_log WHERE qa_try_id = :qaTryId")
    fun findHighWater(qaTryId: Long): Mono<Long>

    fun findByQaTryIdAndDirectionAndMessageId(
        qaTryId: Long,
        direction: String,
        messageId: String
    ): Mono<QaLogEntity>
}
