package kr.artel.orchestration.qa

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaLogEntity
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaCaseHistoryService
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import kr.artel.orchestration.config.InternalApiServer
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Instant
import java.util.UUID

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QaCaseHistoryIntegrationTest {
    @Autowired private lateinit var history: QaCaseHistoryService
    @Autowired private lateinit var tries: QaTryRepository
    @Autowired private lateinit var logs: QaLogRepository
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var scenarios: TestScenarioRepository
    @Autowired private lateinit var cases: TestCaseRepository
    @Autowired private lateinit var instances: GameInstanceRepository
    @Autowired private lateinit var users: OAuthUserService
    @Autowired private lateinit var database: DatabaseClient
    @Autowired private lateinit var internalServer: InternalApiServer
    @LocalServerPort private var publicPort: Int = 0
    private val now = Instant.parse("2026-11-03T00:00:00Z")
    private val createdTries = mutableListOf<Long>()
    private val createdInstances = mutableListOf<Long>()
    private var projectId = 0L
    private var scenarioId = 0L
    private var instanceId = 0L
    private var caseId = 0L
    private var userId = 0L

    @BeforeEach
    fun setup(): Unit = runBlocking {
        userId = users.upsert(OAuthIdentity(
            provider = "github", providerUserId = "case-history-tests", login = "case-history-tests",
            displayName = "case-history-tests", avatarUrl = null, email = "history@example.com",
        ))!!.userId.toLong()
        projectId = projects.save(ProjectEntity(
            name = "history-${UUID.randomUUID()}", genre = "ACTION", createdAt = now, updatedAt = now,
        ))!!.id!!
        scenarioId = scenarios.save(TestScenarioEntity(projectId = projectId, title = "history"))!!.id!!
        instanceId = instances.save(GameInstanceEntity(
            projectId = projectId, name = "history", platform = "UNITY", sdkUuid = UUID.randomUUID().toString(),
            createdAt = now, updatedAt = now,
        ))!!.id!!
        caseId = cases.save(TestCaseEntity(
            projectId = projectId, scene = "Shop", step = "구매", expectedValue = "보상 증가",
        ))!!.id!!
    }

    @AfterEach
    fun cleanup(): Unit = runBlocking {
        for (id in createdTries) tries.deleteById(id)
        createdTries.clear()
        for (id in createdInstances) instances.deleteById(id)
        createdInstances.clear()
        if (caseId != 0L) cases.deleteById(caseId)
        if (instanceId != 0L) instances.deleteById(instanceId)
        if (scenarioId != 0L) scenarios.deleteById(scenarioId)
        if (projectId != 0L) projects.deleteById(projectId)
    }

    @Test
    fun `조회 HTTP 계약은 내부 포트에서만 제공한다`(): Unit = runBlocking {
        val prior = attempt(-100).also { verdict(it) }
        val current = attempt(0, status = "RUNNING")
        val path = "/internal/qa-tries/$current/test-cases/$caseId/verification-history"
        WebTestClient.bindToServer().baseUrl("http://127.0.0.1:${internalServer.port}").build()
            .get().uri(path).exchange().expectStatus().isOk
            .expectBody().jsonPath("$.testCaseId").isEqualTo(caseId.toString())
            .jsonPath("$.selectedTryId").isEqualTo(prior.toString())
            .jsonPath("$.attempts[0].caseSteps[0]").isEqualTo(1)
        WebTestClient.bindToServer().baseUrl("http://127.0.0.1:$publicPort").build()
            .get().uri(path).exchange().expectStatus().isNotFound
    }

    @Test
    fun `같은 TC의 조작과 사람 설명은 출처 범위와 시간순 cursor를 유지한다`(): Unit = runBlocking {
        val prior = attempt(-100)
        val chat = log(prior, "CHAT", "USER_TO_ORCHE", """{"message":"먼저 상점을 열어 주세요"}""", "먼저 상점을 열어 주세요")
        log(prior, "CHAT", "ORCHE_TO_AGENT", """{"message":"먼저 상점을 열어 주세요"}""")
        log(prior, "PULSE", "SDK_TO_ORCHE", "{}")
        log(prior, "TOOL", "AGENT_TO_ORCHE", """{"tool":"read_test_case_history","step":2}""")
        log(prior, "TOOL_RESULT", "AGENT_TO_ORCHE", """{"tool":"read_test_case_history","step":2}""")
        val action = log(prior, "ACTION", "AGENT_TO_ORCHE", """{"step":2,"actions":[{"method":"click_button"}]}""")
        val verdictLog = log(prior, "STATUS", "AGENT_TO_ORCHE", """{"step":2,"case_id":$caseId,"is_verification":true,"status":"COMPLETED"}""")
        val current = attempt(0, status = "RUNNING")
        val first = history.read(current, caseId, limit = 1)
        assertThat(first.selectedTryId).isEqualTo(prior.toString())
        assertThat(first.attempts.single().caseSteps).containsExactly(2)
        assertThat(first.events.single().id).isEqualTo(chat.toString())
        assertThat(first.events.single().scope).isEqualTo("run")
        assertThat(first.events.single().message).isEqualTo("먼저 상점을 열어 주세요")
        val next = history.read(current, caseId, previousTryId = prior, afterLogId = first.nextAfterLogId!!.toLong())
        assertThat(next.events.map { it.id }).containsExactly(action.toString(), verdictLog.toString())
        assertThat(next.events).allMatch { it.scope == "case" }
        assertThat(next.nextAfterLogId).isNull()
    }

    @Test
    fun `현재 실행 시작 이후의 과거 판정 수정 로그는 모든 cursor 페이지에서 제외한다`(): Unit = runBlocking {
        val prior = attempt(-100)
        val originalVerdict = verdict(prior)
        val current = attempt(0, status = "RUNNING")
        val atCutoff = logs.save(QaLogEntity(
            qaTryId = prior, type = "CASE_VERDICT", direction = "ORCHE_INTERNAL",
            payload = Json.of("""{"case_id":$caseId,"status":"FAILED"}"""),
            message = "시작 시각의 사람 수정", createdAt = now,
        ))!!.id!!
        val afterCutoff = logs.save(QaLogEntity(
            qaTryId = prior, type = "CASE_VERDICT", direction = "ORCHE_INTERNAL",
            payload = Json.of("""{"case_id":$caseId,"status":"VERIFIED"}"""),
            message = "시작 이후의 사람 수정", createdAt = now.plusSeconds(1),
        ))!!.id!!

        val first = history.read(current, caseId, limit = 1)
        assertThat(first.events.map { it.id }).containsExactly(originalVerdict.toString())
        val next = history.read(current, caseId, previousTryId = prior, afterLogId = first.nextAfterLogId!!.toLong(), limit = 1)
        assertThat(next.events.map { it.id }).containsExactly(atCutoff.toString())
        assertThat(next.events.map { it.id }).doesNotContain(afterCutoff.toString())
        assertThat(next.nextAfterLogId).isNull()
        assertThat(history.read(current, caseId, previousTryId = prior, afterLogId = atCutoff).events).isEmpty()
    }

    @Test
    fun `TC 시도 페이지를 바꿔도 같은 조작 로그의 scope는 유지한다`(): Unit = runBlocking {
        val prior = attempt(-100)
        repeat(4) { index ->
            val started = now.minusSeconds(99 - index * 2L)
            database.sql("""
                INSERT INTO qa_case_attempt(id, qa_try_id, test_case_id, project_id, case_snapshot, started_at, finished_at, verdict, created_at)
                VALUES (:id, :try, :case, :project, '{}'::jsonb, :started, :finished, 'PASSED', :started)
            """).bind("id", UUID.randomUUID()).bind("try", prior).bind("case", caseId).bind("project", projectId)
                .bind("started", started).bind("finished", started.plusSeconds(1)).fetch().awaitRowsUpdated()
        }
        val action = logs.save(QaLogEntity(qaTryId = prior, type = "ACTION", direction = "AGENT_TO_ORCHE",
            payload = Json.of("""{"actions":[{"method":"click_button"}]}"""), createdAt = now.minusSeconds(99)))!!.id!!
        val current = attempt(0, status = "RUNNING")
        val first = history.read(current, caseId)
        val last = history.read(current, caseId, previousTryId = prior, beforeCaseAttemptId = UUID.fromString(first.nextBeforeCaseAttemptId))
        assertThat(first.events.single { it.id == action.toString() }.scope).isEqualTo("case")
        assertThat(last.events.single { it.id == action.toString() }.scope).isEqualTo("case")
    }

    @Test
    fun `시도별 사람 수정은 snapshot 크기와 무관하며 과거 revision을 cursor로 조회한다`(): Unit = runBlocking {
        val prior = attempt(-100)
        val attemptIds = (1..4).map { UUID.randomUUID() }.sortedBy { it.toString() }.reversed()
        for (id in attemptIds) {
            database.sql("""
                INSERT INTO qa_case_attempt(id, qa_try_id, test_case_id, project_id, case_snapshot, started_at, created_at)
                VALUES (:id, :try, :case, :project, CAST(:snapshot AS jsonb), :started, :started)
            """).bind("id", id).bind("try", prior).bind("case", caseId).bind("project", projectId)
                .bind("snapshot", """{"id":$caseId,"expected":"${"x".repeat(31000)}"}""")
                .bind("started", now.minusSeconds(99)).fetch().awaitRowsUpdated()
            val logId = log(prior, "STATUS", "AGENT_TO_ORCHE", """{"case_id":$caseId,"attempt_id":"$id","is_verification":true,"status":"COMPLETED","case_outcome":"PASSED","message":"과거 판정 이유"}""")
            for ((recorded, note) in listOf(now.minusSeconds(94) to "과거 사람 수정 $id", now.plusSeconds(1) to "미래 수정")) {
                database.sql("""
                    INSERT INTO qa_verdict_review_revision(qa_log_id, decision, note, decided_at, recorded_at)
                    VALUES (:log, 'FAILED', :note, :recorded, :recorded)
                """).bind("log", logId).bind("note", note).bind("recorded", recorded).fetch().awaitRowsUpdated()
            }
        }
        val current = attempt(0, status = "RUNNING")
        val first = history.read(current, caseId)
        assertThat(first.attempts.single().caseAttempts.map { it.id }).containsExactlyElementsOf(attemptIds.take(3).map { it.toString() })
        assertThat(first.attempts.single().caseAttempts).allMatch { it.humanNote!!.startsWith("과거 사람 수정") && it.humanDecision == "FAILED" }
        val last = history.read(current, caseId, previousTryId = prior, beforeCaseAttemptId = UUID.fromString(first.nextBeforeCaseAttemptId))
        assertThat(last.attempts.single().caseAttempts.single().humanNote).isEqualTo("과거 사람 수정 ${attemptIds.last()}")
        assertThat(last.nextBeforeCaseAttemptId).isNull()
    }

    @Test
    fun `질문창 답변은 원문과 답변자를 유지하고 cursor로 끝까지 읽는다`(): Unit = runBlocking {
        val prior = attempt(-100).also { verdict(it) }
        val questionIds = (1..3).map { UUID.randomUUID() }.sortedBy { it.toString() }
        for (id in questionIds) {
            database.sql("""
                INSERT INTO qa_operator_question(id, qa_try_id, project_id, question, state, answer, answered_by, created_at, answered_at)
                VALUES (:id, :try, :project, '어떻게 구매하나요?', 'ANSWERED', '확인 버튼을 누르세요', :user, :created, :answered)
            """).bind("id", id).bind("try", prior).bind("project", projectId).bind("user", userId)
                .bind("created", now.minusSeconds(97)).bind("answered", now.minusSeconds(96))
                .fetch().awaitRowsUpdated()
        }
        val current = attempt(0, status = "RUNNING")
        val first = history.read(current, caseId)
        assertThat(first.questions.map { it.id }).containsExactlyElementsOf(questionIds.take(2).map { it.toString() })
        assertThat(first.questions).allMatch { it.answer == "확인 버튼을 누르세요" && it.answeredBy == userId.toString() }
        val last = history.read(current, caseId, previousTryId = prior, afterQuestionId = UUID.fromString(first.nextAfterQuestionId))
        assertThat(last.questions.single().id).isEqualTo(questionIds.last().toString())
        assertThat(last.nextAfterQuestionId).isNull()
    }

    @Test
    fun `신규 TC 시도의 snapshot과 step 없는 판정 및 조작을 조회한다`(): Unit = runBlocking {
        val prior = attempt(-100)
        val attemptId = UUID.randomUUID()
        database.sql("""
            INSERT INTO qa_case_attempt(id, qa_try_id, test_case_id, project_id, case_snapshot, started_at, created_at)
            VALUES (:id, :try, :case, :project, CAST(:snapshot AS jsonb), :started, :started)
        """).bind("id", attemptId).bind("try", prior).bind("case", caseId).bind("project", projectId)
            .bind("snapshot", """{"id":$caseId,"expected":"당시 기대 결과"}""")
            .bind("started", now.minusSeconds(99)).fetch().awaitRowsUpdated()
        val action = log(prior, "ACTION", "AGENT_TO_ORCHE", """{"actions":[{"method":"click_button"}]}""")
        log(prior, "STATUS", "AGENT_TO_ORCHE", """{"case_id":$caseId,"attempt_id":"$attemptId","is_verification":true,"status":"COMPLETED","case_outcome":"PASSED","message":"보상이 증가했다"}""")
        val current = attempt(0, status = "RUNNING")
        val result = history.read(current, caseId)
        val saved = result.attempts.single().caseAttempts.single()
        assertThat(saved.id).isEqualTo(attemptId.toString())
        assertThat(saved.snapshotOrigin).isEqualTo("attempt_start")
        assertThat(saved.caseSnapshot.path("expected").asText()).isEqualTo("당시 기대 결과")
        assertThat(saved.originalReason).isEqualTo("보상이 증가했다")
        assertThat(saved.verdict).isEqualTo("PASSED")
        assertThat(result.events.first { it.id == action.toString() }.scope).isEqualTo("case")
    }

    @Test
    fun `현재 시작 뒤 종료된 실행과 진행 중 실행 및 다른 scope는 제외한다`(): Unit = runBlocking {
        val included = attempt(-100)
        verdict(included)
        verdict(attempt(-90, completedOffset = 1))
        verdict(attempt(-80, status = "RUNNING"))
        verdict(attempt(-70, scopeId = 33))
        val current = attempt(0, status = "RUNNING")
        verdict(current)
        verdict(attempt(1))
        assertThat(history.read(current, caseId).attempts.map { it.qaTryId }).containsExactly(included.toString())
        assertThrows<NotFoundException> { history.read(current, caseId, previousTryId = current) }
        val scopedCurrent = attempt(2, status = "RUNNING", scopeId = 33)
        assertThat(history.read(scopedCurrent, caseId).attempts).hasSize(1)
        assertThat(history.read(scopedCurrent, caseId).attempts.single().qaTryId).isNotEqualTo(included.toString())
    }

    @Test
    fun `시작 시각이 같은 과거 실행도 중복 없이 다음 페이지에서 조회한다`(): Unit = runBlocking {
        val prior = (1..7).map { attempt(-100).also { verdict(it) } }
        val current = attempt(0, status = "RUNNING")
        val first = history.read(current, caseId)
        assertThat(first.attempts.map { it.qaTryId }).containsExactlyElementsOf(prior.reversed().take(5).map { it.toString() })
        val second = history.read(current, caseId, beforeTryId = first.nextBeforeTryId!!.toLong())
        assertThat(second.attempts.map { it.qaTryId }).containsExactly(prior[1].toString(), prior[0].toString())
        assertThat(second.nextBeforeTryId).isNull()
    }

    @Test
    fun `다른 TC 기록과 잘못된 프로젝트 및 불완전한 cursor는 반환하지 않는다`(): Unit = runBlocking {
        val prior = attempt(-100)
        log(prior, "STATUS", "AGENT_TO_ORCHE", """{"step":1,"case_id":999999,"status":"COMPLETED"}""")
        val current = attempt(0, status = "RUNNING")
        assertThat(history.read(current, caseId).attempts).isEmpty()
        assertThrows<NotFoundException> { history.read(current, caseId, previousTryId = prior) }
        assertThrows<BadRequestException> { history.read(current, caseId, afterLogId = 1) }
        assertThrows<BadRequestException> { history.read(current, caseId, limit = 100) }
        val otherProject = projects.save(ProjectEntity(name = "other", genre = "ACTION", createdAt = now, updatedAt = now))!!.id!!
        val otherCase = cases.save(TestCaseEntity(projectId = otherProject, scene = "Other", step = "x", expectedValue = "x"))!!.id!!
        try {
            assertThrows<NotFoundException> { history.read(current, otherCase) }
        } finally {
            cases.deleteById(otherCase)
            projects.deleteById(otherProject)
        }
    }

    private suspend fun attempt(offset: Long, status: String = "COMPLETED", completedOffset: Long = offset + 10, scopeId: Long? = null): Long {
        val selectedInstanceId = if (status == "RUNNING") {
            instances.save(GameInstanceEntity(
                projectId = projectId, name = "history-active", platform = "UNITY", sdkUuid = UUID.randomUUID().toString(),
                createdAt = now, updatedAt = now,
            ))!!.id!!.also { createdInstances.add(it) }
        } else instanceId
        val id = tries.save(QaTryEntity(
            testScenarioId = scenarioId, gameInstanceId = selectedInstanceId, startedBy = userId,
            status = status, startedAt = now.plusSeconds(offset),
            completedAt = if (status == "RUNNING") null else now.plusSeconds(completedOffset),
            knowledgeScopeId = scopeId, createdAt = now, updatedAt = now,
        ))!!.id!!
        createdTries.add(id)
        return id
    }

    private suspend fun verdict(tryId: Long) = log(tryId, "STATUS", "AGENT_TO_ORCHE",
        """{"step":1,"case_id":$caseId,"is_verification":true,"status":"COMPLETED"}""")

    private suspend fun log(tryId: Long, type: String, direction: String, payload: String, message: String? = null): Long =
        logs.save(QaLogEntity(qaTryId = tryId, type = type, direction = direction, payload = Json.of(payload), message = message, createdAt = now.minusSeconds(95)))!!.id!!
}
