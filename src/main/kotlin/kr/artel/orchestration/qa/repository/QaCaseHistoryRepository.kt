package kr.artel.orchestration.qa.repository

import io.r2dbc.postgresql.codec.Json
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.qa.entity.QaLogEntity
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.dto.QaCaseHistoryCaseAttempt
import kr.artel.orchestration.qa.dto.QaCaseHistoryQuestion
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class QaCaseHistoryRepository(private val databaseClient: DatabaseClient, private val objectMapper: ObjectMapper) {
    /** 과거 시나리오 본문 대신 TC 시도와 판정 기록으로 연결을 확인한다. */
    suspend fun attempts(
        current: QaTryEntity,
        projectId: Long,
        caseId: Long,
        beforeTryId: Long?,
        previousTryId: Long?,
        limit: Int,
    ): List<QaCaseHistoryRow> = databaseClient.sql(
        """
        SELECT qt.id, qt.test_scenario_id, qr.game_build_id, qt.status, qt.started_at, qt.completed_at
          FROM qa_try qt
          JOIN test_scenario ts ON ts.id = qt.test_scenario_id
          LEFT JOIN qa_run qr ON qr.id = qt.qa_run_id
         WHERE ts.project_id = :projectId
           AND qt.id <> :currentId
           AND (qt.started_at, qt.id) < (:startedAt, :currentId)
           AND qt.completed_at <= :startedAt
           AND qt.status IN ('COMPLETED', 'FAILED', 'CANCELLED')
           AND qt.knowledge_scope_id IS NOT DISTINCT FROM :scopeId
           AND (:previousTryId IS NULL OR qt.id = :previousTryId)
           AND (:beforeTryId IS NULL OR (qt.started_at, qt.id) < (
               SELECT cursor.started_at, cursor.id FROM qa_try cursor WHERE cursor.id = :beforeTryId
           ))
           AND (EXISTS (
               SELECT 1 FROM qa_case_attempt attempt
                WHERE attempt.qa_try_id = qt.id AND attempt.test_case_id = :caseIdNumber
                  AND attempt.project_id = :projectId
           ) OR EXISTS (
               SELECT 1 FROM qa_log verdict
                WHERE verdict.qa_try_id = qt.id AND verdict.type = 'STATUS'
                  AND verdict.direction = 'AGENT_TO_ORCHE'
                  AND verdict.payload ->> 'case_id' = :caseId
           ))
         ORDER BY qt.started_at DESC, qt.id DESC
         LIMIT :limit
        """.trimIndent()
    ).bind("projectId", projectId)
        .bind("currentId", requireNotNull(current.id))
        .bind("startedAt", current.startedAt)
        .bindNullableLong("scopeId", current.knowledgeScopeId)
        .bindNullableLong("previousTryId", previousTryId)
        .bindNullableLong("beforeTryId", beforeTryId)
        .bind("caseId", caseId.toString())
        .bind("caseIdNumber", caseId)
        .bind("limit", limit)
        .map { row, _ ->
            QaCaseHistoryRow(
                qaTryId = row.get("id", java.lang.Long::class.java)!!.toLong(),
                testScenarioId = row.get("test_scenario_id", java.lang.Long::class.java)!!.toLong(),
                gameBuildId = row.get("game_build_id", java.lang.Long::class.java)?.toLong(),
                status = row.get("status", String::class.java)!!,
                startedAt = row.get("started_at", Instant::class.java)!!,
                completedAt = row.get("completed_at", Instant::class.java)!!,
            )
        }.flow().toList()

    suspend fun caseAttempts(qaTryId: Long, caseId: Long, cutoff: Instant, beforeAttemptId: UUID?): List<QaCaseHistoryCaseAttempt> =
        databaseClient.sql(
            """
            SELECT a.id, a.started_at, a.finished_at, a.verdict, a.case_snapshot,
                   log.payload ->> 'message' AS original_reason, review.decision, review.note
              FROM qa_case_attempt a
              LEFT JOIN qa_log log ON log.id = a.qa_log_id
              LEFT JOIN LATERAL (
                  SELECT decision, note FROM qa_verdict_review_revision
                   WHERE qa_log_id = a.qa_log_id AND recorded_at <= :cutoff
                   ORDER BY recorded_at DESC, id DESC LIMIT 1
              ) review ON TRUE
             WHERE a.qa_try_id = :qaTryId AND a.test_case_id = :caseId
               AND (:beforeAttemptId IS NULL OR (a.created_at, a.id) < (
                   SELECT cursor.created_at, cursor.id FROM qa_case_attempt cursor
                    WHERE cursor.id = :beforeAttemptId AND cursor.qa_try_id = :qaTryId AND cursor.test_case_id = :caseId
               ))
             ORDER BY a.created_at DESC, a.id DESC LIMIT 4
            """.trimIndent()
        ).bind("qaTryId", qaTryId).bind("caseId", caseId).bind("cutoff", cutoff)
            .let { if (beforeAttemptId == null) it.bindNull("beforeAttemptId", UUID::class.java) else it.bind("beforeAttemptId", beforeAttemptId) }
            .map { row, _ ->
                val startedAt = row.get("started_at", Instant::class.java)
                QaCaseHistoryCaseAttempt(
                    id = row.get("id")!!.toString(),
                    startedAt = startedAt,
                    finishedAt = row.get("finished_at", Instant::class.java),
                    verdict = row.get("verdict", String::class.java),
                    caseSnapshot = objectMapper.readTree(row.get("case_snapshot", Json::class.java)!!.asString()),
                    snapshotOrigin = if (startedAt == null) "legacy_backfill" else "attempt_start",
                    originalReason = row.get("original_reason", String::class.java),
                    humanDecision = row.get("decision", String::class.java),
                    humanNote = row.get("note", String::class.java),
                )
            }.flow().toList()

    /** 질문창 답변은 CHAT과 별도로 저장되므로 원문을 직접 읽는다. */
    suspend fun questions(qaTryId: Long, afterQuestionId: UUID?, cutoff: Instant): List<QaCaseHistoryQuestion> =
        databaseClient.sql(
            """
            SELECT id, question, answer, answered_by, created_at, answered_at FROM qa_operator_question q
             WHERE q.qa_try_id = :qaTryId AND state = 'ANSWERED' AND answered_at <= :cutoff
               AND (:afterId IS NULL OR (q.created_at, q.id) > (
                   SELECT cursor.created_at, cursor.id FROM qa_operator_question cursor
                    WHERE cursor.id = :afterId AND cursor.qa_try_id = :qaTryId
               ))
             ORDER BY q.created_at ASC, q.id ASC LIMIT 3
            """.trimIndent()
        ).bind("qaTryId", qaTryId).bind("cutoff", cutoff)
            .let { if (afterQuestionId == null) it.bindNull("afterId", UUID::class.java) else it.bind("afterId", afterQuestionId) }
            .map { row, _ ->
                QaCaseHistoryQuestion(
                    id = row.get("id")!!.toString(), question = row.get("question", String::class.java)!!,
                    answer = row.get("answer", String::class.java)!!,
                    answeredBy = row.get("answered_by")?.toString(),
                    createdAt = row.get("created_at", Instant::class.java)!!,
                    answeredAt = row.get("answered_at", Instant::class.java)!!,
                )
            }.flow().toList()

    /** CHAT 중계 사본과 history 조회 결과는 다시 읽지 않는다. */
    suspend fun events(qaTryId: Long, caseId: Long, cutoff: Instant, afterLogId: Long, limit: Int): List<QaCaseHistoryLogRow> =
        databaseClient.sql(
            """
            SELECT q.*, EXISTS (
                SELECT 1 FROM qa_case_attempt a
                 WHERE a.qa_try_id = q.qa_try_id AND a.test_case_id = :caseId
                   AND a.started_at <= q.created_at AND a.finished_at >= q.created_at
            ) AS in_case_attempt FROM qa_log q
             WHERE qa_try_id = :qaTryId AND id > :afterLogId
               AND q.created_at <= :cutoff
               AND (
                   (direction = 'USER_TO_ORCHE' AND type = 'CHAT')
                   OR (direction = 'AGENT_TO_ORCHE' AND type IN
                       ('CHAT', 'LOG', 'TOOL', 'TOOL_RESULT', 'ACTION', 'STATUS', 'ISSUE'))
                   OR (direction = 'SDK_TO_ORCHE' AND type IN ('ACTION_RESULT', 'SCREENSHOT'))
                   OR (direction = 'ORCHE_INTERNAL' AND type = 'CASE_VERDICT')
               )
               AND COALESCE(payload ->> 'tool', '') <> 'read_test_case_history'
             ORDER BY id ASC LIMIT :limit
            """.trimIndent()
        ).bind("qaTryId", qaTryId).bind("caseId", caseId).bind("cutoff", cutoff)
            .bind("afterLogId", afterLogId).bind("limit", limit)
            .map { row, _ ->
                val log = QaLogEntity(
                    id = row.get("id", java.lang.Long::class.java)!!.toLong(),
                    qaTryId = qaTryId,
                    direction = row.get("direction", String::class.java)!!,
                    type = row.get("type", String::class.java)!!,
                    message = row.get("message", String::class.java),
                    payload = row.get("payload", Json::class.java)!!,
                    createdAt = row.get("created_at", Instant::class.java)!!,
                )
                QaCaseHistoryLogRow(log, row.get("in_case_attempt", java.lang.Boolean::class.java)!!.booleanValue())
            }.flow().toList()

    private fun DatabaseClient.GenericExecuteSpec.bindNullableLong(name: String, value: Long?) =
        if (value == null) bindNull(name, java.lang.Long::class.java) else bind(name, value)
}

data class QaCaseHistoryRow(
    val qaTryId: Long,
    val testScenarioId: Long,
    val gameBuildId: Long?,
    val status: String,
    val startedAt: Instant,
    val completedAt: Instant,
)

data class QaCaseHistoryLogRow(val log: QaLogEntity, val inCaseAttempt: Boolean)
