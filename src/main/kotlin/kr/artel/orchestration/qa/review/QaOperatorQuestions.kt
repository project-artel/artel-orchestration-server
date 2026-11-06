package kr.artel.orchestration.qa.review

import io.r2dbc.spi.Row
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.event.ProjectChangeStreamManager
import kr.artel.orchestration.project.event.ProjectChangeTopic
import kr.artel.orchestration.project.service.ProjectAccessService
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.awaitSingleOrNull
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

data class AskOperatorRequest(val id: UUID, val question: String)
data class AnswerOperatorRequest(val answer: String)
data class QuestionSubscription(val subscribed: Boolean)
data class OperatorQuestion(
    val id: UUID, val qaTryId: String, val qaRunId: String?, val projectId: String,
    val question: String, val state: String, val answer: String?, val answeredBy: String?,
    val createdAt: Instant, val answeredAt: Instant?,
)

@Service
class QaOperatorQuestions(
    private val database: DatabaseClient,
    private val access: ProjectAccessService,
    private val transactions: TransactionalOperator,
    private val changes: ProjectChangeStreamManager,
) {
    suspend fun ask(tryId: Long, request: AskOperatorRequest): OperatorQuestion {
        if (request.question.isBlank() || request.question.length > 4000) throw BadRequestException("질문은 1~4000자로 입력해 주세요.")
        val result = transactions.executeAndAwait {
            val active = lockTry(tryId)
            val existing = get(tryId, request.id)
            if (existing != null) {
                if (existing.question != request.question) throw ConflictException("이미 사용한 질문 ID입니다.")
                return@executeAndAwait existing
            }
            if (!active) throw ConflictException("종료된 QA입니다.")
            val pending = database.sql("SELECT id FROM qa_operator_question WHERE qa_try_id = :try AND state = 'WAITING'")
                .bind("try", tryId).map { row, _ -> row.get("id")!! }.awaitSingleOrNull()
            if (pending != null) throw ConflictException("답변을 기다리는 질문이 있습니다.")
            database.sql("""
                INSERT INTO qa_operator_question(id, qa_try_id, project_id, question)
                SELECT :id, t.id, s.project_id, :question FROM qa_try t JOIN test_scenario s ON s.id = t.test_scenario_id WHERE t.id = :try
            """).bind("id", request.id).bind("try", tryId).bind("question", request.question).fetch().awaitRowsUpdated()
            get(tryId, request.id) ?: throw NotFoundException()
        }
        changes.notify(result.projectId.toLong(), ProjectChangeTopic.QA_QUESTIONS)
        return result
    }

    suspend fun get(tryId: Long, id: UUID): OperatorQuestion? = database.sql("$SELECT WHERE q.qa_try_id = :try AND q.id = :id")
        .bind("try", tryId).bind("id", id).map { row, _ -> response(row) }.awaitSingleOrNull()

    suspend fun list(projectId: Long, userId: Long): List<OperatorQuestion> {
        access.requireMember(projectId, userId)
        return database.sql("$SELECT WHERE q.project_id = :project ORDER BY (q.state = 'WAITING') DESC, q.created_at DESC LIMIT 100")
            .bind("project", projectId).map { row, _ -> response(row) }.flow().toList()
    }

    suspend fun answer(projectId: Long, id: UUID, userId: Long, request: AnswerOperatorRequest): OperatorQuestion {
        access.requireMember(projectId, userId)
        if (request.answer.isBlank() || request.answer.length > 4000) throw BadRequestException("답변은 1~4000자로 입력해 주세요.")
        val result = transactions.executeAndAwait {
            val question = database.sql("$SELECT WHERE q.project_id = :project AND q.id = :id")
                .bind("project", projectId).bind("id", id).map { row, _ -> response(row) }.awaitSingleOrNull() ?: throw NotFoundException()
            if (!lockTry(question.qaTryId.toLong())) throw ConflictException("종료된 QA입니다.")
            val changed = database.sql("""
                UPDATE qa_operator_question SET state = 'ANSWERED', answer = :answer, answered_by = :user, answered_at = clock_timestamp()
                WHERE id = :id AND state = 'WAITING'
            """).bind("id", id).bind("answer", request.answer.trim()).bind("user", userId).fetch().awaitRowsUpdated()
            if (changed == 0L) throw ConflictException("이미 답변되었거나 종료된 질문입니다.")
            get(question.qaTryId.toLong(), id) ?: throw NotFoundException()
        }
        changes.notify(projectId, ProjectChangeTopic.QA_QUESTIONS)
        return result
    }

    suspend fun subscription(projectId: Long, userId: Long): QuestionSubscription {
        access.requireMember(projectId, userId)
        val found = database.sql("SELECT app_user_id FROM qa_question_subscription WHERE project_id = :project AND app_user_id = :user")
            .bind("project", projectId).bind("user", userId).map { row, _ -> row.get("app_user_id")!! }.awaitSingleOrNull()
        return QuestionSubscription(found != null)
    }

    suspend fun subscribedProjects(userId: Long): List<String> = database.sql("""
        SELECT s.project_id FROM qa_question_subscription s
        JOIN project_member m ON m.project_id = s.project_id AND m.app_user_id = s.app_user_id
        JOIN project p ON p.id = s.project_id AND p.deleted_at IS NULL
        WHERE s.app_user_id = :user ORDER BY s.project_id
    """).bind("user", userId).map { row, _ -> row.get("project_id")!!.toString() }.flow().toList()

    suspend fun subscribe(projectId: Long, userId: Long, request: QuestionSubscription): QuestionSubscription {
        access.requireMember(projectId, userId)
        val sql = if (request.subscribed) "INSERT INTO qa_question_subscription(project_id, app_user_id) VALUES(:project, :user) ON CONFLICT DO NOTHING"
            else "DELETE FROM qa_question_subscription WHERE project_id = :project AND app_user_id = :user"
        database.sql(sql).bind("project", projectId).bind("user", userId).fetch().awaitRowsUpdated()
        return request
    }

    private suspend fun lockTry(tryId: Long): Boolean = database.sql("SELECT status FROM qa_try WHERE id = :id FOR UPDATE")
        .bind("id", tryId).map { row, _ -> row.get("status", String::class.java)!! }.awaitSingleOrNull()
        ?.let { it in setOf("STARTING", "RUNNING") } ?: throw NotFoundException()

    companion object {
        private const val SELECT = "SELECT q.*, t.qa_run_id FROM qa_operator_question q JOIN qa_try t ON t.id = q.qa_try_id"
        private fun response(row: Row) = OperatorQuestion(
            row.get("id", UUID::class.java)!!, row.get("qa_try_id")!!.toString(), row.get("qa_run_id")?.toString(),
            row.get("project_id")!!.toString(), row.get("question", String::class.java)!!, row.get("state", String::class.java)!!,
            row.get("answer", String::class.java), row.get("answered_by")?.toString(), row.get("created_at", Instant::class.java)!!,
            row.get("answered_at", Instant::class.java))
    }
}

@RestController
class QaOperatorQuestionController(private val questions: QaOperatorQuestions) {
    @GetMapping("/api/qa-question-subscriptions")
    suspend fun subscribedProjects(@CurrentUserId userId: Long) = questions.subscribedProjects(userId)

    @PostMapping("/internal/qa/tries/{tryId}/questions")
    suspend fun ask(@PathVariable tryId: Long, @RequestBody request: AskOperatorRequest) = questions.ask(tryId, request)

    @GetMapping("/internal/qa/tries/{tryId}/questions/{id}")
    suspend fun get(@PathVariable tryId: Long, @PathVariable id: UUID) = questions.get(tryId, id) ?: throw NotFoundException()

    @GetMapping("/api/projects/{projectId}/qa-evaluation/questions")
    suspend fun list(@PathVariable projectId: Long, @CurrentUserId userId: Long) = questions.list(projectId, userId)

    @PostMapping("/api/projects/{projectId}/qa-evaluation/questions/{id}/answer")
    suspend fun answer(@PathVariable projectId: Long, @PathVariable id: UUID, @CurrentUserId userId: Long,
        @RequestBody request: AnswerOperatorRequest) = questions.answer(projectId, id, userId, request)

    @GetMapping("/api/projects/{projectId}/qa-evaluation/question-subscription")
    suspend fun subscription(@PathVariable projectId: Long, @CurrentUserId userId: Long) = questions.subscription(projectId, userId)

    @PutMapping("/api/projects/{projectId}/qa-evaluation/question-subscription")
    suspend fun subscribe(@PathVariable projectId: Long, @CurrentUserId userId: Long, @RequestBody request: QuestionSubscription) =
        questions.subscribe(projectId, userId, request)
}
