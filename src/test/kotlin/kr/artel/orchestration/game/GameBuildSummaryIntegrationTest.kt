package kr.artel.orchestration.game

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaRunEntity
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.socket.WebSocketSession
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 빌드 요약 `GET /api/projects/{id}/game-builds/summary` 와 프로젝트 run 목록 `GET /api/projects/{id}/qa-runs`
 * (ARTEL-1104).
 *
 * 인스턴스 0 · 1 · 2 개인 빌드 셋과 editor 행을 한 프로젝트에 세우고, 인스턴스 수, 연결 수, 목록 개수, run 수와
 * 가장 최근 run 의 step 합을 한 응답에서 읽는다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameBuildSummaryIntegrationTest {

    @LocalServerPort private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var sessionManager: SessionManager
    @Autowired private lateinit var buildRepository: GameBuildRepository
    @Autowired private lateinit var instanceRepository: GameInstanceRepository
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var qaRunRepository: QaRunRepository
    @Autowired private lateinit var testRunRepository: TestRunRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var memberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository

    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    private var projectId = 0L
    private var userId = 0L
    private var scenarioId = 0L
    private lateinit var token: String
    private val registeredSockets = mutableMapOf<Long, WebSocketSession>()

    @BeforeEach
    fun seedProject(): Unit = runBlocking {
        wipe()
        val user = oauthUserService.upsert(identity("42", "octocat"))
        userId = user.userId.toLong()
        token = jwtService.issue(user)
        projectId = projectRepository.save(
            ProjectEntity(name = "build-summary", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!.id!!
        memberRepository.save(
            ProjectMemberEntity(projectId = projectId, appUserId = userId, role = ProjectRole.OWNER.name, createdAt = now)
        )
        scenarioId = testScenarioRepository.save(TestScenarioEntity(projectId = projectId))!!.id!!
    }

    @AfterEach
    fun cleanUp(): Unit = runBlocking {
        registeredSockets.forEach { (id, socket) -> sessionManager.removeSession(id.toString(), socket) }
        registeredSockets.clear()
        wipe()
    }

    private suspend fun wipe() {
        qaTryRepository.deleteAll()
        qaRunRepository.deleteAll()
        testRunRepository.deleteAll()
        instanceRepository.deleteAll()
        testScenarioRepository.deleteAll()
        buildRepository.deleteAll()
        memberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `counts instances, connections, functions and runs per build in the order of the build list`(): Unit = runBlocking {
        val empty = seedBuild("1.0.0", createdAt = now.minusSeconds(300))
        val middle = seedBuild("1.3.0", createdAt = now.minusSeconds(200))
        val newest = seedBuild(
            "1.4.0",
            createdAt = now.minusSeconds(100),
            catalog = """{"cheats":[{"name":"a"},{"name":"b"},{"name":"c"},{"name":"d"},{"name":"e"}],"behaviors":[{"name":"x"},{"name":"y"}]}"""
        )
        val editor = seedBuild("1.4.0", isEditor = true, createdAt = now.minusSeconds(10))
        seedInstance(middle, lastConnectedAt = now.minusSeconds(50), connected = true)
        seedInstance(newest, lastConnectedAt = now.minusSeconds(40), connected = true)
        seedInstance(newest, lastConnectedAt = now.minusSeconds(20), connected = false)
        val testRunId = testRunRepository.save(TestRunEntity(projectId = projectId, name = "TR"))!!.id!!
        val instanceId = instanceRepository.findAll().toList().first().id!!
        val oldRun = seedRun(testRunId, instanceId, newest, "FAILED", now.minusSeconds(3000))
        seedTry(oldRun, passed = 0, total = 5)
        val midRun = seedRun(testRunId, instanceId, newest, "COMPLETED", now.minusSeconds(2000))
        seedTry(midRun, passed = 1, total = 1)
        val latest = seedRun(testRunId, instanceId, newest, "COMPLETED", now.minusSeconds(1000))
        seedTry(latest, passed = 1, total = 1)
        seedTry(latest, passed = 1, total = 1)
        seedTry(latest, passed = null, total = null)

        val body = get("/api/projects/$projectId/game-builds/summary")
        println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(body))

        val items = body["items"].toList()
        assertThat(items.map { it["gameBuildId"].asText() })
            .containsExactly(newest.toString(), middle.toString(), empty.toString(), editor.toString())
        val top = items[0]
        assertThat(top["version"].asText()).isEqualTo("1.4.0")
        assertThat(top["isEditor"].asBoolean()).isFalse()
        assertThat(top["instanceCount"].asInt()).isEqualTo(2)
        assertThat(top["connectedCount"].asInt()).isEqualTo(1)
        assertThat(top["cheatCount"].asInt()).isEqualTo(5)
        assertThat(top["behaviorCount"].asInt()).isEqualTo(2)
        assertThat(top["qaRunCount"].asInt()).isEqualTo(3)
        assertThat(Instant.parse(top["lastReportedAt"].asText())).isEqualTo(now.minusSeconds(20))
        assertThat(top["latestQaRun"]["qaRunId"].asText()).isEqualTo(latest.toString())
        assertThat(top["latestQaRun"]["status"].asText()).isEqualTo("COMPLETED")
        assertThat(top["latestQaRun"]["stepsPassed"].asInt()).isEqualTo(2)
        assertThat(top["latestQaRun"]["stepsTotal"].asInt()).isEqualTo(2)

        assertThat(items[1]["instanceCount"].asInt()).isEqualTo(1)
        assertThat(items[1]["connectedCount"].asInt()).isEqualTo(1)
        assertThat(items[1]["cheatCount"].asInt()).isZero()
        assertThat(items[1]["catalogReceivedAt"].isNull).isTrue()
        assertThat(items[1]["latestQaRun"].isNull).isTrue()

        assertThat(items[2]["instanceCount"].asInt()).isZero()
        assertThat(items[2]["connectedCount"].asInt()).isZero()
        assertThat(items[2]["lastReportedAt"].isNull).isTrue()
        assertThat(items[2]["qaRunCount"].asInt()).isZero()
        assertThat(items[3]["isEditor"].asBoolean()).isTrue()
    }

    @Test
    fun `leaves the step sums null while no try of the latest run has finished`(): Unit = runBlocking {
        val build = seedBuild("2.0.0")
        val instance = seedInstance(build, lastConnectedAt = null, connected = false)
        val testRunId = testRunRepository.save(TestRunEntity(projectId = projectId, name = "TR"))!!.id!!
        val run = seedRun(testRunId, instance, build, "RUNNING", now, completedAt = null)
        seedTry(run, passed = null, total = null)

        val item = get("/api/projects/$projectId/game-builds/summary")["items"][0]

        assertThat(item["latestQaRun"]["status"].asText()).isEqualTo("RUNNING")
        assertThat(item["latestQaRun"]["completedAt"].isNull).isTrue()
        assertThat(item["latestQaRun"]["stepsPassed"].isNull).isTrue()
        assertThat(item["latestQaRun"]["stepsTotal"].isNull).isTrue()
    }

    @Test
    fun `the literal summary path does not run the build id handlers`(): Unit = runBlocking {
        seedBuild("1.0.0")

        assertThat(get("/api/projects/$projectId/game-builds/summary")["items"]).hasSize(1)
        assertThat(get("/api/projects/$projectId/game-builds")["items"]).hasSize(1)
    }

    @Test
    fun `answers 404 for a stranger and for an unknown project`(): Unit = runBlocking {
        seedBuild("1.0.0")
        val stranger = jwtService.issue(oauthUserService.upsert(identity("77", "stranger")))

        assertThat(errorOf { get("/api/projects/$projectId/game-builds/summary", stranger) }.statusCode)
            .isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(errorOf { get("/api/projects/${projectId + 9999}/game-builds/summary") }.statusCode)
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `lists the project's runs newest first, filters by build and carries gameBuildId`(): Unit = runBlocking {
        val buildA = seedBuild("1.0.0", createdAt = now.minusSeconds(20))
        val buildB = seedBuild("1.1.0", createdAt = now.minusSeconds(10))
        val instance = seedInstance(buildB, lastConnectedAt = now, connected = false)
        val testRunId = testRunRepository.save(TestRunEntity(projectId = projectId, name = "TR"))!!.id!!
        val first = seedRun(testRunId, instance, buildA, "COMPLETED", now.minusSeconds(300))
        val second = seedRun(testRunId, instance, buildB, "COMPLETED", now.minusSeconds(200))
        val third = seedRun(testRunId, instance, buildB, "FAILED", now.minusSeconds(100))
        val withoutBuild = seedRun(testRunId, instance, null, "CANCELLED", now.minusSeconds(400))
        seedTry(third, passed = 1, total = 3)

        val all = get("/api/projects/$projectId/qa-runs")["items"].toList()
        assertThat(all.map { it["id"].asText() })
            .containsExactly(third.toString(), second.toString(), first.toString(), withoutBuild.toString())
        assertThat(all[0]["gameBuildId"].asText()).isEqualTo(buildB.toString())
        assertThat(all[0]["tries"]).hasSize(1)
        assertThat(all[3]["gameBuildId"].isNull).isTrue()
        println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(all[0]))

        val onlyB = get("/api/projects/$projectId/qa-runs?gameBuildId=$buildB")["items"].toList()
        assertThat(onlyB.map { it["id"].asText() }).containsExactly(third.toString(), second.toString())

        val limited = get("/api/projects/$projectId/qa-runs?size=1")["items"].toList()
        assertThat(limited.map { it["id"].asText() }).containsExactly(third.toString())

        assertThat(get("/api/qa-runs/$third")["gameBuildId"].asText()).isEqualTo(buildB.toString())
    }

    @Test
    fun `serves the QA screen's limit and testRunId on the same list, with both shapes in one row`(): Unit = runBlocking {
        // ARTEL-1161: QA 실행 · 히스토리(ARTEL-1083)와 빌드 상세(ARTEL-1104)가 이 경로를 따로 만들었다. 한 핸들러가 둘 다 받는다.
        val build = seedBuild("1.0.0")
        val instance = seedInstance(build, lastConnectedAt = now, connected = false)
        val runA = testRunRepository.save(TestRunEntity(projectId = projectId, name = "A"))!!.id!!
        val runB = testRunRepository.save(TestRunEntity(projectId = projectId, name = "B"))!!.id!!
        val older = seedRun(runA, instance, build, "COMPLETED", now.minusSeconds(200))
        val newer = seedRun(runB, instance, build, "COMPLETED", now.minusSeconds(100))
        seedTry(older, passed = 2, total = 3)

        val onlyA = get("/api/projects/$projectId/qa-runs?limit=10&testRunId=$runA")["items"].toList()
        assertThat(onlyA.map { it["id"].asText() }).containsExactly(older.toString())
        val row = onlyA.single()
        assertThat(row["testRunName"].asText()).isEqualTo("A")
        assertThat(row["casesPassed"].isNull).isTrue() // 케이스 판정이 없으면 0 이 아니라 null 이다(ARTEL-1083 규칙)
        assertThat(row["issueCount"].asInt()).isZero()
        assertThat(row["gameBuildId"].asText()).isEqualTo(build.toString())
        assertThat(row["tries"]).hasSize(1)

        assertThat(get("/api/projects/$projectId/qa-runs?limit=1")["items"].map { it["id"].asText() })
            .containsExactly(newer.toString())
        listOf("0", "51").forEach {
            assertThat(errorOf { get("/api/projects/$projectId/qa-runs?limit=$it") }.statusCode)
                .isEqualTo(HttpStatus.BAD_REQUEST)
        }
    }

    @Test
    fun `rejects a size outside 1 to 100 and a malformed build id`(): Unit = runBlocking {
        listOf("0", "101", "-1").forEach {
            assertThat(errorOf { get("/api/projects/$projectId/qa-runs?size=$it") }.statusCode)
                .isEqualTo(HttpStatus.BAD_REQUEST)
        }
        assertThat(errorOf { get("/api/projects/$projectId/qa-runs?gameBuildId=abc") }.statusCode)
            .isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(get("/api/projects/$projectId/qa-runs?size=100")["items"]).isEmpty()
    }

    @Test
    fun `hides the run list from a stranger`(): Unit = runBlocking {
        val stranger = jwtService.issue(oauthUserService.upsert(identity("77", "stranger")))

        assertThat(errorOf { get("/api/projects/$projectId/qa-runs", stranger) }.statusCode)
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    // --- helpers ---

    private suspend fun seedBuild(
        version: String,
        isEditor: Boolean = false,
        createdAt: Instant = now,
        catalog: String? = null
    ): Long = buildRepository.save(
        GameBuildEntity(
            projectId = projectId,
            version = version,
            isEditor = isEditor,
            gameFunctionCatalog = catalog?.let { Json.of(it) },
            gameFunctionCatalogRevision = catalog?.let { 1 },
            gameFunctionCatalogReceivedAt = catalog?.let { now },
            createdAt = createdAt,
            updatedAt = createdAt
        )
    )!!.id!!

    private suspend fun seedInstance(buildId: Long, lastConnectedAt: Instant?, connected: Boolean): Long {
        val id = instanceRepository.save(
            GameInstanceEntity(
                projectId = projectId,
                name = "instance",
                platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(),
                lastConnectedAt = lastConnectedAt,
                lastGameBuildId = buildId,
                createdAt = now,
                updatedAt = now
            )
        )!!.id!!
        if (connected) {
            val socket = Mockito.mock(WebSocketSession::class.java)
            sessionManager.register(id.toString(), socket)
            registeredSockets[id] = socket
        }
        return id
    }

    private suspend fun seedRun(
        testRunId: Long,
        instanceId: Long,
        buildId: Long?,
        status: String,
        startedAt: Instant,
        completedAt: Instant? = startedAt.plusSeconds(10)
    ): Long = qaRunRepository.save(
        QaRunEntity(
            testRunId = testRunId,
            gameInstanceId = instanceId,
            gameBuildId = buildId,
            startedBy = userId,
            status = status,
            startedAt = startedAt,
            completedAt = completedAt
        )
    )!!.id!!

    private suspend fun seedTry(runId: Long, passed: Int?, total: Int?) {
        val instanceId = qaRunRepository.findById(runId)!!.gameInstanceId
        qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenarioId,
                gameInstanceId = instanceId,
                qaRunId = runId,
                startedBy = userId,
                status = "COMPLETED",
                stepsPassed = passed,
                stepsTotal = total,
                startedAt = now,
                completedAt = now
            )
        )
    }

    private fun identity(providerUserId: String, login: String) = OAuthIdentity(
        provider = "github",
        providerUserId = providerUserId,
        login = login,
        displayName = login,
        avatarUrl = null,
        email = "$login@example.com"
    )

    private fun get(uri: String, withToken: String = token): JsonNode = objectMapper.readTree(
        WebClient.create("http://localhost:$port").get().uri(uri).cookie("artel_access_token", withToken)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun errorOf(call: () -> Any?): WebClientResponseException =
        try {
            call()
            error("요청이 실패해야 하는데 성공했다")
        } catch (error: WebClientResponseException) {
            error
        }
}
