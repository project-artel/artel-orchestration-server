package kr.artel.orchestration.gamenote

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.gamenote.entity.GenreModule
import kr.artel.orchestration.gamenote.repository.ProjectGameNoteRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.service.QaAgentEnvelope
import kr.artel.orchestration.qa.service.QaAgentPort
import kr.artel.orchestration.qa.service.QaAgentSession
import kr.artel.orchestration.qa.service.QaAgentSessionContext
import kr.artel.orchestration.qa.service.QaSessionGameNotes
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.support.deleteQaRuns
import kr.artel.orchestration.support.testAppUser
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testrun.service.TestRunService
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 프로젝트별 게임 노트(ARTEL-1061): `GET` · `PUT /api/projects/{projectId}/game-notes` 와 런 시작 때 agent-server
 * 세션 개설로 가는 `game_notes`.
 *
 * agent-server 는 세션 개설 컨텍스트를 받아 두는 [RecordingAgentPort] 다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameNoteIntegrationTest {

    class RecordingAgentPort : QaAgentPort {
        val contexts: MutableList<QaAgentSessionContext> = CopyOnWriteArrayList()

        override suspend fun createSession(
            context: QaAgentSessionContext,
            onMessage: suspend (QaAgentEnvelope) -> Unit,
            onDisconnect: suspend () -> Unit
        ): QaAgentSession {
            contexts += context
            return QaAgentSession("game-note-session-${contexts.size}")
        }

        override suspend fun send(sessionId: String, envelope: QaAgentEnvelope) = Unit

        override suspend fun close(sessionId: String) = Unit
    }

    @TestConfiguration
    class Fakes {
        @Bean @Primary fun recordingAgentPort() = RecordingAgentPort()
    }

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var authProperties: AuthProperties
    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var agentPort: RecordingAgentPort
    @Autowired private lateinit var sessionManager: SessionManager
    @Autowired private lateinit var testRunService: TestRunService
    @Autowired private lateinit var gameNoteRepository: ProjectGameNoteRepository
    @Autowired private lateinit var testRunRepository: TestRunRepository
    @Autowired private lateinit var runScenarioRepository: TestRunScenarioRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository

    private val userIds = mutableListOf<Long>()
    private var ownerId: Long = 0
    private lateinit var ownerSession: String
    private var projectId: Long = 0

    @BeforeEach
    fun seed(): Unit = runBlocking {
        agentPort.contexts.clear()
        val (id, session) = signUp("game-note-owner")
        ownerId = id
        ownerSession = session
        val now = Instant.now()
        projectId = projectRepository.save(ProjectEntity(name = "game-note-p", genre = "CASUAL", createdAt = now, updatedAt = now)).id!!
        join(ownerId, ProjectRole.OWNER)
    }

    @AfterEach
    fun clean(): Unit = runBlocking {
        databaseClient.deleteQaRuns()
        runScenarioRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testRunRepository.deleteAll()
        testScenarioRepository.deleteAll()
        projectMemberRepository.deleteAll()
        // `project_game_note` 는 프로젝트를 따라 지워진다(ON DELETE CASCADE).
        projectRepository.deleteAll()
        userIds.forEach { appUserRepository.deleteById(it) }
        userIds.clear()
    }

    // ------------------------------------------------------------ GET · PUT

    @Test
    fun `a project without notes answers version 0 with an empty body`() {
        val notes = call(HttpMethod.GET, notesPath())

        assertThat(notes["version"].asInt()).isZero()
        assertThat(notes["body"].asText()).isEmpty()
        assertThat(notes["genres"].isArray && notes["genres"].isEmpty).isTrue()
        assertThat(notes["updatedAt"].isNull).isTrue()
        assertThat(notes["updatedBy"].isNull).isTrue()
    }

    @Test
    fun `put saves version 1 and get reads it back`() {
        val saved = call(
            HttpMethod.PUT, notesPath(),
            """{"body":"  카드는 덱에서 뽑는다.\r\n턴은 세 번이다.  ","genres":["turn-based","ui-flow"],"baseVersion":0}"""
        )

        assertThat(saved["version"].asInt()).isEqualTo(1)
        // 줄바꿈은 LF 로, 앞뒤 공백은 지운다.
        assertThat(saved["body"].asText()).isEqualTo("카드는 덱에서 뽑는다.\n턴은 세 번이다.")
        // `GenreModule` 선언 순서로 맞춘다.
        assertThat(saved["genres"].map { it.asText() }).containsExactly("ui-flow", "turn-based")
        assertThat(saved["updatedAt"].isNull).isFalse()
        assertThat(saved["updatedBy"]["id"].asText()).isEqualTo(ownerId.toString())
        assertThat(saved["updatedBy"]["displayName"].asText()).isEqualTo("game-note-owner")

        assertThat(call(HttpMethod.GET, notesPath())).isEqualTo(saved)
    }

    @Test
    fun `put with the same body and genre set keeps the version`(): Unit = runBlocking {
        val first = call(HttpMethod.PUT, notesPath(), """{"body":"노트","genres":["ui-flow","turn-based"],"baseVersion":0}""")

        val again = call(HttpMethod.PUT, notesPath(), """{"body":"노트\n","genres":["turn-based","ui-flow"],"baseVersion":1}""")

        assertThat(again).isEqualTo(first)
        assertThat(countNotes()).isEqualTo(1)
    }

    @Test
    fun `put of an empty note on a project without notes saves nothing`(): Unit = runBlocking {
        val notes = call(HttpMethod.PUT, notesPath(), """{"body":"  ","genres":[],"baseVersion":0}""")

        assertThat(notes["version"].asInt()).isZero()
        assertThat(countNotes()).isZero()
    }

    @Test
    fun `every change is a new version and the latest wins`(): Unit = runBlocking {
        call(HttpMethod.PUT, notesPath(), """{"body":"첫 노트","genres":["ui-flow"],"baseVersion":0}""")
        call(HttpMethod.PUT, notesPath(), """{"body":"첫 노트","genres":[],"baseVersion":1}""")
        val third = call(HttpMethod.PUT, notesPath(), """{"body":"","genres":[],"baseVersion":2}""")

        assertThat(third["version"].asInt()).isEqualTo(3)
        assertThat(third["body"].asText()).isEmpty()
        assertThat(countNotes()).isEqualTo(3)
    }

    @Test
    fun `put on a stale baseVersion is 409 game_notes_conflict`(): Unit = runBlocking {
        call(HttpMethod.PUT, notesPath(), """{"body":"먼저 저장한 노트","genres":[],"baseVersion":0}""")

        val (status, body) = exchange(HttpMethod.PUT, notesPath(), """{"body":"늦게 저장한 노트","genres":[],"baseVersion":0}""")

        assertThat(status).isEqualTo(409)
        assertThat(objectMapper.readTree(body)["code"].asText()).isEqualTo("game_notes_conflict")
        assertThat(countNotes()).isEqualTo(1)

        val (aheadStatus, _) = exchange(HttpMethod.PUT, notesPath(), """{"body":"앞선 버전","genres":[],"baseVersion":5}""")
        assertThat(aheadStatus).isEqualTo(409)
    }

    @Test
    fun `two puts on the same baseVersion save one version and the other is 409`(): Unit = runBlocking {
        val outcomes = (1..2).map { index ->
            async(Dispatchers.IO) {
                exchange(HttpMethod.PUT, notesPath(), """{"body":"동시에 저장한 노트 $index","genres":[],"baseVersion":0}""")
            }
        }.awaitAll()

        assertThat(outcomes.map { it.first }).containsExactlyInAnyOrder(200, 409)
        assertThat(countNotes()).isEqualTo(1)
    }

    @Test
    fun `the primary key refuses a version that is already taken`(): Unit = runBlocking {
        // 두 저장이 같은 최신을 읽고 지나간 경우다. 늦은 쪽의 insert 는 행을 넣지 않고 null 이 되어 409 로 끝난다.
        val first = gameNoteRepository.insertIfAbsent(projectId, 1, "먼저", arrayOf("ui-flow"), ownerId, Instant.now())
        val second = gameNoteRepository.insertIfAbsent(projectId, 1, "나중", emptyArray(), ownerId, Instant.now())

        assertThat(first?.body).isEqualTo("먼저")
        assertThat(first?.genreModules()).containsExactly(GenreModule.UI_FLOW)
        assertThat(second).isNull()
        assertThat(gameNoteRepository.findLatest(projectId)?.body).isEqualTo("먼저")
    }

    @Test
    fun `a body over 20000 characters is 400 with fields body`(): Unit = runBlocking {
        val tooLong = "가".repeat(20001)

        val (status, body) = exchange(HttpMethod.PUT, notesPath(), """{"body":"$tooLong","genres":[],"baseVersion":0}""")

        assertThat(status).isEqualTo(400)
        val error = objectMapper.readTree(body)
        assertThat(error["code"].asText()).isEqualTo("invalid_request")
        assertThat(error["fields"].has("body")).isTrue()
        assertThat(countNotes()).isZero()

        val atLimit = call(HttpMethod.PUT, notesPath(), """{"body":"${"가".repeat(20000)}","genres":[],"baseVersion":0}""")
        assertThat(atLimit["version"].asInt()).isEqualTo(1)
    }

    @Test
    fun `an unknown or repeated genre is 400 with fields genres`(): Unit = runBlocking {
        for (genres in listOf("""["real-time"]""", """["UI_FLOW"]""", """["ui-flow","ui-flow"]""")) {
            val (status, body) = exchange(HttpMethod.PUT, notesPath(), """{"body":"노트","genres":$genres,"baseVersion":0}""")

            assertThat(status).describedAs(genres).isEqualTo(400)
            val error = objectMapper.readTree(body)
            assertThat(error["code"].asText()).isEqualTo("invalid_request")
            assertThat(error["fields"].fieldNames().asSequence().toList()).describedAs(genres).containsExactly("genres")
        }
        assertThat(countNotes()).isZero()
    }

    @Test
    fun `a missing field is 400 naming the field`() {
        val (status, body) = exchange(HttpMethod.PUT, notesPath(), """{"body":"노트","genres":[]}""")

        assertThat(status).isEqualTo(400)
        assertThat(objectMapper.readTree(body)["fields"].has("baseVersion")).isTrue()
    }

    @Test
    fun `a member who is not the owner reads and saves, as with updating the project`(): Unit = runBlocking {
        val (memberId, memberSession) = signUp("game-note-member")
        join(memberId, ProjectRole.MEMBER)

        val saved = call(HttpMethod.PUT, notesPath(), """{"body":"멤버가 쓴 노트","genres":[],"baseVersion":0}""", memberSession)

        assertThat(saved["updatedBy"]["id"].asText()).isEqualTo(memberId.toString())
        assertThat(call(HttpMethod.GET, notesPath())["body"].asText()).isEqualTo("멤버가 쓴 노트")
    }

    @Test
    fun `someone outside the project gets 404 for both reading and saving`(): Unit = runBlocking {
        val (_, strangerSession) = signUp("game-note-stranger")

        assertThat(exchange(HttpMethod.GET, notesPath(), session = strangerSession).first).isEqualTo(404)
        assertThat(
            exchange(HttpMethod.PUT, notesPath(), """{"body":"남의 노트","genres":[],"baseVersion":0}""", strangerSession).first
        ).isEqualTo(404)
        assertThat(countNotes()).isZero()
    }

    @Test
    fun `a deleted project is 404`(): Unit = runBlocking {
        val project = projectRepository.findById(projectId)!!
        projectRepository.save(project.copy(deletedAt = Instant.now()))

        assertThat(exchange(HttpMethod.GET, notesPath()).first).isEqualTo(404)
        assertThat(exchange(HttpMethod.PUT, notesPath(), """{"body":"노트","genres":[],"baseVersion":0}""").first).isEqualTo(404)
    }

    // ------------------------------------------------------------ 런 시작

    @Test
    fun `a run on a project without notes sends no game notes`(): Unit = runBlocking {
        val seed = seedRunnableInstance()

        val (status, body) = exchange(
            HttpMethod.POST, "/api/qa-runs", """{"testRunId":"${seed.testRunId}","gameInstanceId":"${seed.instanceId}"}"""
        )

        assertThat(status).describedAs(body).isEqualTo(201)
        assertThat(agentPort.contexts.single().gameNotes).isNull()
    }

    @Test
    fun `a run carries the latest notes at the moment it starts`(): Unit = runBlocking {
        val seed = seedRunnableInstance()
        call(HttpMethod.PUT, notesPath(), """{"body":"첫 노트","genres":["turn-based"],"baseVersion":0}""")
        call(HttpMethod.PUT, notesPath(), """{"body":"고친 노트","genres":["ui-flow","turn-based"],"baseVersion":1}""")

        val (status, body) = exchange(
            HttpMethod.POST, "/api/qa-runs", """{"testRunId":"${seed.testRunId}","gameInstanceId":"${seed.instanceId}"}"""
        )

        assertThat(status).describedAs(body).isEqualTo(201)
        assertThat(agentPort.contexts.single().gameNotes).isEqualTo(
            QaSessionGameNotes(version = 2, body = "고친 노트", genres = listOf(GenreModule.UI_FLOW, GenreModule.TURN_BASED))
        )
    }

    @Test
    fun `a single scenario try carries the notes too`(): Unit = runBlocking {
        val seed = seedRunnableInstance()
        call(HttpMethod.PUT, notesPath(), """{"body":"노트","genres":["ui-flow"],"baseVersion":0}""")

        val (status, body) = exchange(
            HttpMethod.POST, "/api/qa-tries", """{"testScenarioId":"${seed.scenarioId}","gameInstanceId":"${seed.instanceId}"}"""
        )

        assertThat(status).describedAs(body).isEqualTo(201)
        assertThat(agentPort.contexts.single().gameNotes)
            .isEqualTo(QaSessionGameNotes(version = 1, body = "노트", genres = listOf(GenreModule.UI_FLOW)))
    }

    // ------------------------------------------------------------ helpers

    private fun notesPath() = "/api/projects/$projectId/game-notes"

    private suspend fun signUp(name: String): Pair<Long, String> {
        val user = appUserRepository.save(testAppUser(name))
        val id = user.id!!
        userIds += id
        return id to jwtService.issue(AuthenticatedUser(id.toString(), "password", name, name, null))
    }

    private suspend fun join(userId: Long, role: ProjectRole) {
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = projectId, appUserId = userId, role = role.name, createdAt = Instant.now())
        )
    }

    private data class RunnableSeed(val testRunId: String, val scenarioId: String, val instanceId: String)

    /** SDK 가 붙은 인스턴스와 시나리오 하나짜리 테스트 런. */
    private suspend fun seedRunnableInstance(): RunnableSeed {
        val now = Instant.now()
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = projectId, name = "inst", platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(), createdAt = now, updatedAt = now
            )
        )
        sessionManager.register(instance.id!!.toString(), Mockito.mock(WebSocketSession::class.java))
        val testRun = testRunRepository.save(TestRunEntity(projectId = projectId, name = "런"))
        val scenario = testScenarioRepository.save(TestScenarioEntity(projectId = projectId))
        testRunService.setScenarios(testRun.id!!, ownerId, listOf(scenario.id!!))
        return RunnableSeed(testRun.id.toString(), scenario.id.toString(), instance.id.toString())
    }

    private suspend fun countNotes(): Long =
        databaseClient.sql("SELECT COUNT(*) AS n FROM project_game_note WHERE project_id = :projectId")
            .bind("projectId", projectId)
            .map { row, _ -> row.get("n", Long::class.javaObjectType)!! }
            .one().awaitSingle()

    private fun call(method: HttpMethod, path: String, json: String? = null, session: String = ownerSession): JsonNode {
        val (status, body) = exchange(method, path, json, session)
        assertThat(status).describedAs("$method $path: $body").isEqualTo(200)
        return objectMapper.readTree(body)
    }

    /** (상태 코드, 응답 본문) 을 돌려준다. */
    private fun exchange(
        method: HttpMethod,
        path: String,
        json: String? = null,
        session: String = ownerSession
    ): Pair<Int, String> {
        val spec = WebClient.create("http://localhost:$port").method(method).uri(path).cookie(authProperties.cookieName, session)
        val request = if (json != null) spec.contentType(MediaType.APPLICATION_JSON).bodyValue(json.toByteArray(Charsets.UTF_8)) else spec
        return request.exchangeToMono { response ->
            response.bodyToMono(ByteArray::class.java).map { response.statusCode().value() to String(it, Charsets.UTF_8) }
                .switchIfEmpty(Mono.just(response.statusCode().value() to ""))
        }.block()!!
    }
}
