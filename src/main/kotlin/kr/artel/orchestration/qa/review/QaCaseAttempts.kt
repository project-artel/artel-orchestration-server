package kr.artel.orchestration.qa.review

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import io.r2dbc.spi.Row
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.project.event.ProjectChangeStreamManager
import kr.artel.orchestration.project.event.ProjectChangeTopic
import kr.artel.orchestration.knowledge.service.KnowledgeCitationService
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaLogAppendResult
import kr.artel.orchestration.qa.service.QaLogService
import kr.artel.orchestration.testcase.service.TestCaseVerdictService
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.awaitSingleOrNull
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import org.springframework.web.bind.annotation.*
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class StartCaseAttempt(val id: UUID, val testCaseId: Long, val caseSnapshot: JsonNode? = null)
data class ReportCaseAttempt(val verdict: CaseOutcome, val reason: String, val thought: String? = null,
    val checks: JsonNode? = null, val evidence: String? = null, val usedKnowledgeIds: List<String> = emptyList(),
    val usedCheats: JsonNode? = null, val activeBehaviors: JsonNode? = null,
    val rejectedKnowledgeIdCount: Int = 0)
data class CaseAttemptResponse(
    val id: UUID, val qaTryId: String, val testCaseId: String, val startedAt: Instant?, val finishedAt: Instant?,
    val verdict: String?, val logId: String?, val review: String?, val note: String?, val createdAt: Instant,
    val startKnown: Boolean = startedAt != null,
    val playbackStart: Instant? = startedAt?.minusSeconds(10),
    val playbackEnd: Instant? = finishedAt?.plusSeconds(5),
    val qaRunId: String? = null,
    val caseSnapshot: JsonNode? = null,
    val originalReason: String? = null,
)

@Service
class QaCaseAttemptService(
    private val database: DatabaseClient,
    private val tries: QaTryRepository,
    private val access: ProjectAccessService,
    private val logs: QaLogService,
    private val caseVerdicts: TestCaseVerdictService,
    private val mapper: ObjectMapper,
    private val transactions: TransactionalOperator,
    private val clock: Clock,
    private val changes: ProjectChangeStreamManager,
    private val citations: KnowledgeCitationService,
) {
    suspend fun start(tryId: Long, request: StartCaseAttempt): CaseAttemptResponse = transactions.executeAndAwait {
        val qaTry = activeTry(tryId)
        if (request.caseSnapshot != null && (!request.caseSnapshot.isObject || request.caseSnapshot.path("id").asLong() != request.testCaseId || request.caseSnapshot.toString().length > 32000))
            throw BadRequestException("TC snapshot을 확인해 주세요.")
        val changed = database.sql("""
            INSERT INTO qa_case_attempt(id, qa_try_id, test_case_id, project_id, case_snapshot, started_at)
            SELECT :id, t.id, c.id, c.project_id, COALESCE(CAST(:snapshot AS jsonb), to_jsonb(c)), :started
            FROM qa_try t JOIN test_scenario s ON s.id = t.test_scenario_id
            JOIN test_case c ON c.project_id = s.project_id AND c.id = :caseId
            WHERE t.id = :tryId AND EXISTS (SELECT 1 FROM jsonb_array_elements(s.steps) AS reference(value) WHERE reference.value->>'case_id' = c.id::text)
            ON CONFLICT (id) DO NOTHING
        """).bind("id", request.id).bind("caseId", request.testCaseId).bind("tryId", qaTry.id!!)
            .let { sql -> request.caseSnapshot?.let { sql.bind("snapshot", it.toString()) } ?: sql.bindNull("snapshot", String::class.java) }
            .bind("started", Instant.now(clock)).fetch().awaitRowsUpdated()
        val attempt = find(request.id)
        if (attempt == null || attempt.qaTryId != tryId.toString() || attempt.testCaseId != request.testCaseId.toString()) {
            if (changed == 0L) throw BadRequestException("이 QA 시나리오에 포함된 TC가 아닙니다.")
            throw ConflictException("검증 시도 ID가 다른 실행에 사용됐습니다.")
        }
        attempt
    }

    suspend fun report(tryId: Long, id: UUID, request: ReportCaseAttempt): CaseAttemptResponse {
        if (request.reason.length > 4000 || (request.thought?.length ?: 0) > 4000 || request.verdict == CaseOutcome.INTERRUPTED || request.rejectedKnowledgeIdCount < 0)
            throw BadRequestException("판정 결과와 이유를 확인해 주세요.")
        var appended: QaLogAppendResult? = null
        val result = transactions.executeAndAwait {
            lockTry(tryId)
            database.sql("SELECT id FROM qa_case_attempt WHERE id = :id AND qa_try_id = :try FOR UPDATE")
                .bind("id", id).bind("try", tryId).map { row, _ -> row.get("id")!! }.awaitSingleOrNull()
                ?: throw NotFoundException()
            val current = find(id) ?: throw NotFoundException()
            if (current.verdict != null) {
                if (current.verdict != request.verdict.name) throw ConflictException("이미 다른 결과로 보고된 검증입니다.")
                return@executeAndAwait current
            }
            val qaTry = activeTry(tryId)
            val payload = mapper.createObjectNode().put("case_id", current.testCaseId.toLong())
                .put("is_verification", true).put("attempt_id", id.toString())
                .put("status", when (request.verdict) {
                    CaseOutcome.PASSED -> "COMPLETED"
                    CaseOutcome.FAILED -> "FAILED"
                    else -> "INCONCLUSIVE"
                }).put("case_outcome", request.verdict.name).put("message", request.reason)
                .put("reason", request.thought).put("evidence", request.evidence)
            request.checks?.let { payload.set<JsonNode>("checks", it) }
            request.usedCheats?.let { payload.set<JsonNode>("used_cheats", it) }
            request.activeBehaviors?.let { payload.set<JsonNode>("active_behaviors", it) }
            val citation = citations.recordCitations(tryId, request.usedKnowledgeIds.take(100))
            payload.set<JsonNode>("used_knowledge_ids", mapper.valueToTree(citation.cited))
            payload.put("rejected_knowledge_id_count", request.rejectedKnowledgeIdCount.toLong() + citation.rejected.size +
                (request.usedKnowledgeIds.size - 100).coerceAtLeast(0))
            val log = logs.append(qaTryId = tryId, direction = "AGENT_TO_ORCHE", type = "STATUS",
                messageId = id.toString(), message = request.reason, payload = payload)
            appended = log
            database.sql("UPDATE qa_case_attempt SET finished_at = :finished, verdict = :verdict, qa_log_id = :log WHERE id = :id")
                .bind("finished", Instant.now(clock)).bind("verdict", request.verdict.name).bind("log", log.log.id.toLong())
                .bind("id", id).fetch().awaitRowsUpdated()
            if (request.verdict in setOf(CaseOutcome.PASSED, CaseOutcome.FAILED))
                caseVerdicts.recordStepVerdict(qaTry, current.testCaseId.toLong(), request.verdict == CaseOutcome.PASSED, log.log.id.toLong(), publish = false)
            find(id) ?: throw NotFoundException()
        }
        appended?.let(logs::publish)
        if (appended != null) {
            val project = database.sql("SELECT project_id FROM qa_case_attempt WHERE id = :id").bind("id", id)
                .map { row, _ -> row.get("project_id", java.lang.Long::class.java)!!.toLong() }.awaitSingleOrNull()
            project?.let { changes.notify(it, ProjectChangeTopic.TEST_CASES); changes.notify(it, ProjectChangeTopic.ISSUES); changes.notify(it, ProjectChangeTopic.QA_RUNS) }
        }
        return result
    }

    suspend fun list(projectId: Long, userId: Long, tryId: Long?, caseId: Long?, before: Instant?, beforeId: UUID? = null): List<CaseAttemptResponse> {
        access.requireMember(projectId, userId)
        return database.sql("""
            $SELECT WHERE a.project_id = :project AND (:try = 0 OR a.qa_try_id = :try)
                AND (:case = 0 OR a.test_case_id = :case) AND (a.created_at, a.id) < (:before, :beforeId)
            ORDER BY a.created_at DESC, a.id DESC LIMIT 100
        """).bind("project", projectId).bind("try", tryId ?: 0L).bind("case", caseId ?: 0L)
            .bind("before", before ?: Instant.now(clock).plusSeconds(1)).bind("beforeId", beforeId ?: UUID(-1L, -1L))
            .map { row, _ -> response(row) }.flow().toList()
    }

    suspend fun get(projectId: Long, userId: Long, id: UUID): CaseAttemptResponse {
        access.requireMember(projectId, userId)
        return database.sql("$SELECT WHERE a.id = :id AND a.project_id = :project")
            .bind("id", id).bind("project", projectId).map { row, _ -> response(row) }.awaitSingleOrNull()
            ?: throw NotFoundException()
    }

    private suspend fun find(id: UUID): CaseAttemptResponse? = database.sql("$SELECT WHERE a.id = :id")
        .bind("id", id).map { row, _ -> response(row) }.awaitSingleOrNull()

    private suspend fun lockTry(id: Long) {
        database.sql("SELECT id FROM qa_try WHERE id = :id FOR UPDATE").bind("id", id)
            .map { row, _ -> row.get("id")!! }.awaitSingleOrNull() ?: throw NotFoundException()
    }

    private suspend fun activeTry(id: Long): QaTryEntity {
        lockTry(id)
        return tries.findById(id)?.takeIf { it.status in setOf("STARTING", "RUNNING") }
            ?: throw ConflictException("진행 중인 QA 실행이 아닙니다.")
    }

    companion object {
        private const val SELECT = "SELECT a.*, r.decision, r.note, t.qa_run_id, l.payload->>'message' AS original_reason FROM qa_case_attempt a JOIN qa_try t ON t.id = a.qa_try_id LEFT JOIN qa_verdict_review r ON r.qa_log_id = a.qa_log_id LEFT JOIN qa_log l ON l.id = a.qa_log_id"
    }
    private fun response(row: Row) = CaseAttemptResponse(
            row.get("id", UUID::class.java)!!, row.get("qa_try_id")!!.toString(), row.get("test_case_id")!!.toString(),
            row.get("started_at", Instant::class.java), row.get("finished_at", Instant::class.java), row.get("verdict", String::class.java),
            row.get("qa_log_id")?.toString(), row.get("decision", String::class.java), row.get("note", String::class.java),
            row.get("created_at", Instant::class.java)!!, qaRunId = row.get("qa_run_id")?.toString(),
            caseSnapshot = mapper.readTree(row.get("case_snapshot", io.r2dbc.postgresql.codec.Json::class.java)!!.asString()),
            originalReason = row.get("original_reason", String::class.java))
}

@RestController
class QaCaseAttemptController(private val attempts: QaCaseAttemptService) {
    @GetMapping("/api/projects/{projectId}/qa-evaluation/attempts/{id}")
    suspend fun get(@PathVariable projectId: Long, @CurrentUserId userId: Long, @PathVariable id: UUID) = attempts.get(projectId, userId, id)

    @PostMapping("/internal/qa/tries/{tryId}/case-attempts/start")
    suspend fun start(@PathVariable tryId: Long, @RequestBody request: StartCaseAttempt) = attempts.start(tryId, request)

    @PostMapping("/internal/qa/tries/{tryId}/case-attempts/{id}/report")
    suspend fun report(@PathVariable tryId: Long, @PathVariable id: UUID, @RequestBody request: ReportCaseAttempt) = attempts.report(tryId, id, request)

    @GetMapping("/api/projects/{projectId}/qa-evaluation/attempts")
    suspend fun list(@PathVariable projectId: Long, @CurrentUserId userId: Long,
        @RequestParam(required = false) qaTryId: Long?, @RequestParam(required = false) testCaseId: Long?,
        @RequestParam(required = false) before: Instant?, @RequestParam(required = false) beforeId: UUID?) = attempts.list(projectId, userId, qaTryId, testCaseId, before, beforeId)
}
