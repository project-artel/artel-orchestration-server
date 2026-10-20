package kr.artel.orchestration.telemetry

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.auth.entity.PlatformRole
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.common.error.UpstreamUnavailableException
import kr.artel.orchestration.config.ApiExceptionHandler
import kr.artel.orchestration.settings.repository.PlatformSettingRepository
import kr.artel.orchestration.support.PrivateTestDatabase
import kr.artel.orchestration.support.testAppUser
import kr.artel.orchestration.telemetry.config.TelemetryProperties
import kr.artel.orchestration.telemetry.dto.OTHER_TOOL_NAME
import kr.artel.orchestration.telemetry.repository.TelemetryErrorEventRepository
import kr.artel.orchestration.telemetry.service.TelemetryConsentService
import kr.artel.orchestration.telemetry.service.TelemetryReceiverClient
import kr.artel.orchestration.telemetry.service.TelemetryReportBuilder
import kr.artel.orchestration.telemetry.service.TelemetryReportSender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 보내는 쪽 전부: report 집계, consent 우선순위, 밀린 날 보내기, 관리 화면 API, 5xx 기록.
 *
 * 다른 클래스의 `qa_try` 와 `llm_usage` 가 하루 집계에 섞이지 않도록 자기 database 에서 돈다([PrivateTestDatabase]).
 * receiver 는 이 클래스가 띄운 가짜 서버다. 받은 POST 본문과 DELETE 경로를 모으고, [receiverStatus] 로 답을 바꾼다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TelemetryReportIntegrationTest {

    companion object {
        /** 집계 테스트의 날. 다른 테스트가 넣는 지금 시각의 행과 겹치지 않는다. */
        private val FIXTURE_DAY: LocalDate = LocalDate.of(2001, 2, 3)

        private val receivedReports = ConcurrentLinkedQueue<String>()
        private val receivedDeletes = ConcurrentLinkedQueue<String>()
        private val receiverStatus = AtomicInteger(204)

        private val fakeReceiver: DisposableServer by lazy {
            HttpServer.create().port(0).route { routes ->
                routes.post("/reports") { request, response ->
                    request.receive().aggregate().asString().flatMap { body ->
                        receivedReports.add(body)
                        response.status(receiverStatus.get()).send().then()
                    }
                }
                routes.delete("/reports/{installId}") { request, response ->
                    receivedDeletes.add(request.param("installId")!!)
                    response.status(receiverStatus.get()).send()
                }
            }.bindNow()
        }

        @JvmStatic
        @DynamicPropertySource
        fun settings(registry: DynamicPropertyRegistry) {
            PrivateTestDatabase.register(registry, "telemetry_report")
            registry.add("artel.telemetry.endpoint") { "http://localhost:${fakeReceiver.port()}/reports" }
            registry.add("artel.version") { "1.4.2" }
        }
    }

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var authProperties: AuthProperties
    @Autowired private lateinit var reportBuilder: TelemetryReportBuilder
    @Autowired private lateinit var reportSender: TelemetryReportSender
    @Autowired private lateinit var consentService: TelemetryConsentService
    @Autowired private lateinit var telemetryProperties: TelemetryProperties
    @Autowired private lateinit var platformSettingRepository: PlatformSettingRepository
    @Autowired private lateinit var receiverClient: TelemetryReceiverClient
    @Autowired private lateinit var errorEventRepository: TelemetryErrorEventRepository
    @Autowired private lateinit var apiExceptionHandler: ApiExceptionHandler
    @Autowired private lateinit var clock: Clock

    private var adminId: Long = 0
    private lateinit var adminSession: String
    private lateinit var memberSession: String

    @BeforeEach
    fun clean(): Unit = runBlocking {
        for (statement in listOf(
            "DELETE FROM qa_log", "DELETE FROM qa_try", "DELETE FROM llm_usage",
            "DELETE FROM telemetry_error_event", "DELETE FROM platform_setting"
        )) {
            databaseClient.sql(statement).fetch().rowsUpdated().awaitSingle()
        }
        receivedReports.clear()
        receivedDeletes.clear()
        receiverStatus.set(204)
        val admin = appUserRepository.save(testAppUser("telemetry-admin").copy(platformRole = PlatformRole.ADMIN.name))
        adminId = admin.id!!
        adminSession = jwtService.issue(AuthenticatedUser(adminId.toString(), "password", "admin", "admin", null))
        val member = appUserRepository.save(testAppUser("telemetry-member"))
        memberSession = jwtService.issue(AuthenticatedUser(member.id!!.toString(), "password", "member", "member", null))
    }

    // ── report 집계 ──────────────────────────────────────────────

    @Test
    fun `groups finished tries by structure and keeps null summaries apart from zero`(): Unit = runBlocking {
        val tools = """["observe_scene","report_step"]"""
        insertTry("loop-v3", "fp1", "claude/opus", "high", "COMPLETED", startedAt = "2001-02-03 10:00:00", completedAt = "2001-02-03 10:05:00", steps = 4 to 3, cases = 2 to 1, tools = tools)
        insertTry("loop-v3", "fp1", "claude/opus", "high", "FAILED", startedAt = "2001-02-03 11:00:00", completedAt = "2001-02-03 11:01:40", steps = 2 to 0, cases = 1 to 0, tools = tools)
        // 요약을 못 받은 try 만 있는 묶음은 합계가 null 이다.
        insertTry("planner", null, "gpt/luna", null, "CANCELLED", startedAt = "2001-02-03 12:00:00", completedAt = "2001-02-03 12:00:30", steps = null, cases = null, tools = tools)

        val report = reportBuilder.build(FIXTURE_DAY, "11111111-2222-3333-4444-555555555555")

        assertThat(report.schemaVersion).isEqualTo(1)
        assertThat(report.install.orchestrationVersion).isEqualTo("1.4.2")
        assertThat(report.install.reportDate).isEqualTo(FIXTURE_DAY)
        assertThat(report.qaStructures).hasSize(2)
        val loop = report.qaStructures.single { it.agentArch == "loop-v3" }
        assertThat(loop.tries).isEqualTo(2)
        assertThat(loop.triesByStatus).isEqualTo(mapOf("COMPLETED" to 1L, "FAILED" to 1L))
        assertThat(loop.stepsTotal).isEqualTo(6)
        assertThat(loop.stepsPassed).isEqualTo(3)
        assertThat(loop.casesTotal).isEqualTo(3)
        assertThat(loop.casesPassed).isEqualTo(1)
        assertThat(loop.durationSecondsSum).isEqualTo(400)
        assertThat(loop.durationSecondsMax).isEqualTo(300)
        val planner = report.qaStructures.single { it.agentArch == "planner" }
        assertThat(planner.agentFingerprint).isNull()
        assertThat(planner.reasoningEffort).isNull()
        assertThat(planner.stepsTotal).isNull()
        assertThat(planner.casesPassed).isNull()
        assertThat(planner.triesByStatus).isEqualTo(mapOf("CANCELLED" to 1L))
    }

    @Test
    fun `counts a tool outside the try's built-in list as other`(): Unit = runBlocking {
        val tryId = insertTry("loop-v3", "fp1", "claude/opus", "high", "COMPLETED", startedAt = "2001-02-03 10:00:00", completedAt = "2001-02-03 10:05:00", tools = """["observe_scene","report_step"]""")
        insertLog(tryId, "TOOL", """{"message":"observe_scene","tool":"observe_scene"}""")
        insertLog(tryId, "TOOL", """{"message":"observe_scene","tool":"observe_scene"}""")
        insertLog(tryId, "TOOL", """{"message":"report_step","tool":"report_step"}""")
        // 게임이 등록한 함수 이름. 설치 밖으로 나가면 안 된다.
        insertLog(tryId, "TOOL", """{"message":"open_secret_level","tool":"open_secret_level"}""")
        insertLog(tryId, "TOOL_RESULT", """{"message":"observe_scene","tool":"observe_scene"}""")
        // run_config.tools 가 없는 옛 try 의 호출은 전부 other 다.
        val legacyTryId = insertTry("legacy", null, null, null, "COMPLETED", startedAt = "2001-02-03 13:00:00", completedAt = "2001-02-03 13:01:00", tools = null)
        insertLog(legacyTryId, "TOOL", """{"message":"observe_scene","tool":"observe_scene"}""")

        val report = reportBuilder.build(FIXTURE_DAY, null)

        assertThat(report.qaStructures.single { it.agentArch == "loop-v3" }.toolCalls)
            .isEqualTo(mapOf("observe_scene" to 2L, OTHER_TOOL_NAME to 1L, "report_step" to 1L))
        assertThat(report.qaStructures.single { it.agentArch == "legacy" }.toolCalls)
            .isEqualTo(mapOf(OTHER_TOOL_NAME to 1L))
        assertThat(objectMapper.writeValueAsString(report)).doesNotContain("open_secret_level")
    }

    @Test
    fun `takes only the rows of that UTC day`(): Unit = runBlocking {
        insertTry("before", null, null, null, "COMPLETED", startedAt = "2001-02-02 23:00:00", completedAt = "2001-02-02 23:59:59")
        insertTry("first-second", null, null, null, "COMPLETED", startedAt = "2001-02-02 23:59:00", completedAt = "2001-02-03 00:00:00")
        insertTry("last-second", null, null, null, "COMPLETED", startedAt = "2001-02-03 23:00:00", completedAt = "2001-02-03 23:59:59")
        insertTry("after", null, null, null, "COMPLETED", startedAt = "2001-02-03 23:59:00", completedAt = "2001-02-04 00:00:00")
        insertUsage("2001-02-02 23:59:59", tokens = 1)
        insertUsage("2001-02-03 00:00:00", tokens = 10)
        insertUsage("2001-02-03 23:59:59", tokens = 100)
        insertUsage("2001-02-04 00:00:00", tokens = 1000)

        val report = reportBuilder.build(FIXTURE_DAY, null)

        assertThat(report.qaStructures.map { it.agentArch }).containsExactly("first-second", "last-second")
        val usage = report.llmUsage.single()
        assertThat(usage.calls).isEqualTo(2)
        assertThat(usage.inputTokens).isEqualTo(110)
        assertThat(usage.costUsdSum).isEqualByComparingTo("0.22")
    }

    @Test
    fun `groups errors without messages and drops values that are not identifiers`(): Unit = runBlocking {
        val tryId = insertTry("loop-v3", null, null, null, "FAILED", startedAt = "2001-02-03 10:00:00", completedAt = "2001-02-03 10:05:00")
        insertLog(tryId, "ERROR", """{"message":"secret level 7 broke","code":"run_incomplete","exceptionType":"TimeoutError","frames":["app.qa.runner:run_with_deadline:120","/home/user/app.py:main:1"]}""")
        insertLog(tryId, "ERROR", """{"message":"again","code":"run_incomplete","exceptionType":"TimeoutError","frames":["app.qa.runner:later:9"]}""")
        // exceptionType 이 없는 옛 agent-server 와, 문장이 든 code.
        insertLog(tryId, "ERROR", """{"message":"x","code":"run_incomplete"}""")
        insertLog(tryId, "ERROR", """{"reason":"connection closed","code":"the player opened the secret door"}""")
        databaseClient.sql(
            "INSERT INTO telemetry_error_event (exception_type, code, frames, created_at) VALUES " +
                "('java.lang.IllegalStateException', 'internal_error', '[\"kr.artel.orchestration.qa.Foo:bar:12\"]'::jsonb, '2001-02-03 05:00:00+00')"
        ).fetch().rowsUpdated().awaitSingle()

        val report = reportBuilder.build(FIXTURE_DAY, null)

        val qaRunErrors = report.errors.filter { it.source == "qa_run" }
        assertThat(qaRunErrors.single { it.exceptionType == "TimeoutError" }.let { it.count to it.frames })
            .isEqualTo(2L to listOf("app.qa.runner:run_with_deadline:120"))
        assertThat(qaRunErrors.single { it.code == "run_incomplete" && it.exceptionType == null }.count).isEqualTo(1)
        assertThat(qaRunErrors.single { it.code == null }.count).isEqualTo(1)
        val orchestration = report.errors.single { it.source == "orchestration" }
        assertThat(orchestration.exceptionType).isEqualTo("java.lang.IllegalStateException")
        assertThat(orchestration.frames).containsExactly("kr.artel.orchestration.qa.Foo:bar:12")
        val json = objectMapper.writeValueAsString(report)
        assertThat(json).doesNotContain("secret").doesNotContain("connection closed").doesNotContain("/home/user")
    }

    // ── consent ──────────────────────────────────────────────────

    @Test
    fun `the environment decides until a setting is stored, then the setting wins`(): Unit = runBlocking {
        val environmentOn = TelemetryConsentService(platformSettingRepository, telemetryProperties.copy(consent = "on"), objectMapper, clock)
        val environmentOff = consentService

        assertThat(environmentOn.isEnabled()).isTrue()
        assertThat(environmentOn.status().source).isEqualTo("environment")
        assertThat(environmentOff.isEnabled()).isFalse()

        environmentOn.saveConsent(false, adminId)
        assertThat(environmentOn.isEnabled()).isFalse()
        assertThat(environmentOn.status().source).isEqualTo("setting")

        environmentOff.saveConsent(true, adminId)
        assertThat(environmentOff.isEnabled()).isTrue()
        assertThat(environmentOff.installId()).isNotNull()
        // 켜는 순간 그제까지 보낸 것으로 당긴다. 다음 tick 은 어제 하나만 보낸다.
        assertThat(environmentOff.lastSentDate()).isEqualTo(today().minusDays(2))
    }

    // ── 보내기 ───────────────────────────────────────────────────

    @Test
    fun `the sender does nothing with consent off or with a blank endpoint`(): Unit = runBlocking {
        assertThat(reportSender.sendDueReports()).isZero()

        consentService.saveConsent(true, adminId)
        val blankEndpoint = telemetryProperties.copy(endpoint = " ")
        val senderWithoutEndpoint = TelemetryReportSender(
            blankEndpoint, consentService, reportBuilder, TelemetryReceiverClient(blankEndpoint), errorEventRepository, objectMapper, clock
        )
        assertThat(senderWithoutEndpoint.sendDueReports()).isZero()
        assertThat(receivedReports).isEmpty()
    }

    @Test
    fun `sends missing days oldest first, at most seven days back, and stops on a failure`(): Unit = runBlocking {
        val environmentOn = telemetryProperties.copy(consent = "on")
        val consent = TelemetryConsentService(platformSettingRepository, environmentOn, objectMapper, clock)
        val sender = TelemetryReportSender(environmentOn, consent, reportBuilder, receiverClient, errorEventRepository, objectMapper, clock)
        val yesterday = today().minusDays(1)
        consent.markSent(yesterday.minusDays(30))

        assertThat(sender.sendDueReports()).isEqualTo(7)
        val dates = receivedReports.map { objectMapper.readTree(it)["install"]["reportDate"].asText() }
        assertThat(dates).isEqualTo((6L downTo 0L).map { yesterday.minusDays(it).toString() })
        assertThat(consent.lastSentDate()).isEqualTo(yesterday)
        // 환경 변수로 켠 설치의 install id 는 scheduler 가 만든다.
        val installId = consent.installId()
        assertThat(receivedReports.map { objectMapper.readTree(it)["install"]["installId"].asText() }.toSet()).containsExactly(installId)

        // 이미 어제까지 보냈으면 보낼 것이 없다.
        assertThat(sender.sendDueReports()).isZero()

        // receiver 가 실패하면 멈추고 날짜를 옮기지 않는다.
        consent.markSent(yesterday.minusDays(2))
        receiverStatus.set(503)
        assertThat(sender.sendDueReports()).isZero()
        assertThat(consent.lastSentDate()).isEqualTo(yesterday.minusDays(2))
    }

    @Test
    fun `the sender deletes error events older than fourteen days`(): Unit = runBlocking {
        errorEventRepository.insert("A", "internal_error", "[]", clock.instant().minus(Duration.ofDays(15)))
        errorEventRepository.insert("B", "internal_error", "[]", clock.instant().minus(Duration.ofDays(13)))

        reportSender.sendDueReports()

        val remaining = databaseClient.sql("SELECT exception_type FROM telemetry_error_event")
            .map { row, _ -> row.get("exception_type", String::class.java)!! }.all().collectList().awaitSingle()
        assertThat(remaining).containsExactly("B")
    }

    // ── 5xx 기록 ─────────────────────────────────────────────────

    @Test
    fun `a 5xx is recorded without its message only while consent is on`(): Unit = runBlocking {
        apiExceptionHandler.handleUnexpected(IllegalStateException("db password is hunter2"))
        Thread.sleep(300)
        assertThat(errorEventCount()).isZero()

        consentService.saveConsent(true, adminId)
        apiExceptionHandler.handleApi(UpstreamUnavailableException("storage at 10.0.0.3 is down", code = "storage_unavailable"))
        // 저장은 응답과 따로 구독해 돈다. 5초까지 기다린다.
        repeat(50) { if (errorEventCount() == 0L) kotlinx.coroutines.delay(100) }
        assertThat(errorEventCount()).isEqualTo(1)

        val row = databaseClient.sql("SELECT exception_type, code, frames::text AS frames FROM telemetry_error_event")
            .map { row, _ -> Triple(row.get("exception_type", String::class.java)!!, row.get("code", String::class.java)!!, row.get("frames", String::class.java)!!) }
            .one().awaitSingle()
        assertThat(row.first).isEqualTo(UpstreamUnavailableException::class.java.name)
        assertThat(row.second).isEqualTo("storage_unavailable")
        val frames = objectMapper.readTree(row.third)
        assertThat(frames.size()).isBetween(1, 10)
        assertThat(frames.all { it.asText().startsWith("kr.artel.") }).isTrue()
        assertThat(row.third).doesNotContain("10.0.0.3")
    }

    // ── 관리 화면 API ────────────────────────────────────────────

    @Test
    fun `admin reads, turns on, previews, and turns off with deletion`() {
        val initial = call(adminSession, HttpMethod.GET, "/api/admin/settings/telemetry")
        assertThat(initial.second.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("available", "enabled", "source", "installId", "lastSentDate")
        assertThat(initial.second["available"].asBoolean()).isTrue()
        assertThat(initial.second["enabled"].asBoolean()).isFalse()
        assertThat(initial.second["source"].asText()).isEqualTo("environment")
        assertThat(initial.second["installId"].isNull).isTrue()

        val turnedOn = call(adminSession, HttpMethod.PUT, "/api/admin/settings/telemetry", """{"enabled":true}""").second
        assertThat(turnedOn["enabled"].asBoolean()).isTrue()
        assertThat(turnedOn["source"].asText()).isEqualTo("setting")
        val installId = turnedOn["installId"].asText()
        assertThat(turnedOn["lastSentDate"].asText()).isEqualTo(today().minusDays(2).toString())

        val preview = call(adminSession, HttpMethod.GET, "/api/admin/settings/telemetry/preview").second
        assertThat(preview["schemaVersion"].asInt()).isEqualTo(1)
        assertThat(preview["install"]["installId"].asText()).isEqualTo(installId)
        assertThat(preview["install"]["reportDate"].asText()).isEqualTo(today().minusDays(1).toString())
        assertThat(receivedReports).isEmpty()

        // 미리보기는 보낼 report 와 같은 JSON 이다.
        runBlocking { reportSender.sendDueReports() }
        assertThat(objectMapper.readTree(receivedReports.single())).isEqualTo(preview)

        val turnedOff = call(adminSession, HttpMethod.PUT, "/api/admin/settings/telemetry", """{"enabled":false,"deleteSentData":true}""").second
        assertThat(receivedDeletes).containsExactly(installId)
        assertThat(turnedOff["enabled"].asBoolean()).isFalse()
        assertThat(turnedOff["installId"].isNull).isTrue()
        assertThat(turnedOff["lastSentDate"].isNull).isTrue()
    }

    @Test
    fun `a failed deletion changes nothing and answers 503`() {
        call(adminSession, HttpMethod.PUT, "/api/admin/settings/telemetry", """{"enabled":true}""")
        receiverStatus.set(500)

        val (status, body) = call(adminSession, HttpMethod.PUT, "/api/admin/settings/telemetry", """{"enabled":false,"deleteSentData":true}""")

        assertThat(status).isEqualTo(503)
        assertThat(body["code"].asText()).isEqualTo("telemetry_delete_failed")
        val after = call(adminSession, HttpMethod.GET, "/api/admin/settings/telemetry").second
        assertThat(after["enabled"].asBoolean()).isTrue()
        assertThat(after["installId"].isNull).isFalse()
    }

    @Test
    fun `a non-admin is refused on every telemetry settings path`() {
        assertThat(call(memberSession, HttpMethod.GET, "/api/admin/settings/telemetry").first).isEqualTo(403)
        assertThat(call(memberSession, HttpMethod.PUT, "/api/admin/settings/telemetry", """{"enabled":true}""").first).isEqualTo(403)
        assertThat(call(memberSession, HttpMethod.GET, "/api/admin/settings/telemetry/preview").first).isEqualTo(403)
    }

    @Test
    fun `settings report source environment until an admin decides`(): Unit = runBlocking {
        assertThat(call(adminSession, HttpMethod.GET, "/api/admin/settings/telemetry").second["source"].asText())
            .isEqualTo("environment")
        consentService.saveConsent(false, adminId)
        assertThat(call(adminSession, HttpMethod.GET, "/api/admin/settings/telemetry").second["source"].asText())
            .isEqualTo("setting")
    }

    // ── fixture ─────────────────────────────────────────────────

    private suspend fun insertTry(
        agentArch: String?,
        fingerprint: String?,
        model: String?,
        reasoningEffort: String?,
        status: String,
        startedAt: String,
        completedAt: String,
        steps: Pair<Int, Int>? = null,
        cases: Pair<Int, Int>? = null,
        tools: String? = null
    ): Long {
        val projectId = insertId("INSERT INTO project (name, genre) VALUES ('telemetry', 'ACTION') RETURNING id")
        val scenarioId = insertId("INSERT INTO test_scenario (project_id, title) VALUES ($projectId, 'telemetry') RETURNING id")
        val instanceId = insertId("INSERT INTO game_instance (project_id, name, platform) VALUES ($projectId, 'telemetry', 'Editor') RETURNING id")
        val runConfig = if (tools == null) "{}" else """{"tools":$tools,"image_tag":"agent-2.0.1"}"""
        return databaseClient.sql(
            """
            INSERT INTO qa_try (test_scenario_id, game_instance_id, started_by, status, agent_arch, agent_fingerprint, model,
                                reasoning_effort, steps_total, steps_passed, cases_total, cases_passed, run_config,
                                started_at, completed_at)
            VALUES (:scenarioId, :instanceId, :userId, :status, :agentArch, :fingerprint, :model, :reasoningEffort,
                    :stepsTotal, :stepsPassed, :casesTotal, :casesPassed, CAST(:runConfig AS jsonb),
                    CAST(:startedAt AS timestamptz), CAST(:completedAt AS timestamptz))
            RETURNING id
            """
        )
            .bind("scenarioId", scenarioId)
            .bind("instanceId", instanceId)
            .bind("userId", adminId)
            .bind("status", status)
            .bindNullable("agentArch", agentArch)
            .bindNullable("fingerprint", fingerprint)
            .bindNullable("model", model)
            .bindNullable("reasoningEffort", reasoningEffort)
            .bindNullableInt("stepsTotal", steps?.first)
            .bindNullableInt("stepsPassed", steps?.second)
            .bindNullableInt("casesTotal", cases?.first)
            .bindNullableInt("casesPassed", cases?.second)
            .bind("runConfig", runConfig)
            .bind("startedAt", "$startedAt+00")
            .bind("completedAt", "$completedAt+00")
            .map { row, _ -> (row.get(0) as Number).toLong() }
            .one().awaitSingle()
    }

    private suspend fun insertLog(tryId: Long, type: String, payload: String) {
        databaseClient.sql(
            "INSERT INTO qa_log (qa_try_id, direction, type, message, payload) VALUES (:tryId, 'AGENT_TO_ORCHE', :type, 'm', CAST(:payload AS jsonb))"
        ).bind("tryId", tryId).bind("type", type).bind("payload", payload).fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun insertUsage(calledAt: String, tokens: Int) {
        databaseClient.sql(
            """
            INSERT INTO llm_usage (service, provider, model, input_tokens, output_tokens, cost_usd, cost_estimated, latency_ms, called_at)
            VALUES ('QA_RUN', 'anthropic', 'claude/opus', :tokens, 0, 0.11, FALSE, 50, CAST(:calledAt AS timestamptz))
            """
        ).bind("tokens", tokens).bind("calledAt", "$calledAt+00").fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun insertId(sql: String): Long =
        databaseClient.sql(sql).map { row, _ -> (row.get(0) as Number).toLong() }.one().awaitSingle()

    private suspend fun errorEventCount(): Long =
        databaseClient.sql("SELECT COUNT(*) AS n FROM telemetry_error_event")
            .map { row, _ -> (row.get("n") as Number).toLong() }.one().awaitSingle()

    private fun DatabaseClient.GenericExecuteSpec.bindNullable(name: String, value: String?) =
        if (value == null) bindNull(name, String::class.java) else bind(name, value)

    private fun DatabaseClient.GenericExecuteSpec.bindNullableInt(name: String, value: Int?) =
        if (value == null) bindNull(name, Integer::class.java) else bind(name, value)

    private fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)

    private fun call(session: String, method: HttpMethod, path: String, json: String? = null): Pair<Int, JsonNode> {
        val spec = WebClient.create("http://localhost:$port").method(method).uri(path).cookie(authProperties.cookieName, session)
        val request = if (json != null) spec.contentType(MediaType.APPLICATION_JSON).bodyValue(json) else spec
        return request.exchangeToMono { response ->
            response.bodyToMono(String::class.java).defaultIfEmpty("null")
                .map { response.statusCode().value() to objectMapper.readTree(it) }
                .switchIfEmpty(Mono.just(response.statusCode().value() to objectMapper.nullNode()))
        }.block()!!
    }
}
