package kr.artel.orchestration.testrun

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testrun.entity.TestRunScenarioEntity
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ScenarioStep
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.entity.toDraft
import kr.artel.orchestration.testscenario.entity.withDraft
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import java.time.Instant
import java.util.UUID

/** `POST /api/projects/{projectId}/test-runs/{runId}/copies`(ARTEL-1377). */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TestRunCopyIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var scenarios: TestScenarioRepository
    @Autowired private lateinit var runScenarios: TestRunScenarioRepository

    @Test
    fun `복제는 같은 버전 · 폴더에 사본을 만들고 시나리오를 순서대로 복사한다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val build = gameBuilds.save(GameBuildEntity(projectId = projectId, version = "1.0.0", createdAt = Instant.now(), updatedAt = Instant.now()))
        val folder = call(token, HttpMethod.POST, "/api/projects/$projectId/test-run-folders", """{"name":"회귀","gameBuildId":"${build.id}"}""").body["id"].asText()
        val run = call(token, HttpMethod.POST, "/api/projects/$projectId/test-runs", """{"name":"광장 회귀","description":"설명","folderId":"$folder"}""").body
        val runId = run["id"].asLong()
        val first = scenario(projectId, "의뢰판 열기", listOf("광장으로 간다", "의뢰판을 연다"))
        val second = scenario(projectId, "의뢰 받기", listOf("의뢰를 받는다"))
        runScenarios.save(TestRunScenarioEntity(testRunId = runId, testScenarioId = first.id!!, position = 0))
        runScenarios.save(TestRunScenarioEntity(testRunId = runId, testScenarioId = second.id!!, position = 1))

        val copied = call(token, HttpMethod.POST, "/api/projects/$projectId/test-runs/$runId/copies")

        assertThat(copied.status).isEqualTo(201)
        assertThat(copied.body["name"].asText()).isEqualTo("광장 회귀 사본")
        assertThat(copied.body["description"].asText()).isEqualTo("설명")
        assertThat(copied.body["folderId"].asText()).isEqualTo(folder)
        assertThat(copied.body["gameBuild"]["gameBuildId"].asLong()).isEqualTo(build.id)
        assertThat(copied.body["scenarioCount"].asInt()).isEqualTo(2)
        val copyRows = runScenarios.findByTestRunIdOrderByPosition(copied.body["id"].asLong()).toList()
        val copies = copyRows.map { scenarios.findById(it.testScenarioId)!! }
        assertThat(copies.map { it.title }).containsExactly("의뢰판 열기", "의뢰 받기")
        assertThat(copies.map { it.id }).doesNotContain(first.id, second.id)
        assertThat(copies[0].toDraft(objectMapper).steps.map { it.action }).containsExactly("광장으로 간다", "의뢰판을 연다")
        // 원본은 그대로다.
        assertThat(runScenarios.findByTestRunIdOrderByPosition(runId).toList().map { it.testScenarioId })
            .containsExactly(first.id, second.id)
    }

    @Test
    fun `복제할 자리로 다른 버전이나 버전 없음을 고른다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val now = Instant.now()
        val older = gameBuilds.save(GameBuildEntity(projectId = projectId, version = "1.0.0", createdAt = now.minusSeconds(5), updatedAt = now))
        val newer = gameBuilds.save(GameBuildEntity(projectId = projectId, version = "1.1.0", createdAt = now, updatedAt = now))
        val runId = call(token, HttpMethod.POST, "/api/projects/$projectId/test-runs", """{"name":"빈 런","gameBuildId":"${older.id}"}""").body["id"].asLong()

        val toNewer = call(token, HttpMethod.POST, "/api/projects/$projectId/test-runs/$runId/copies", """{"gameBuildId":"${newer.id}"}""")
        val loose = call(token, HttpMethod.POST, "/api/projects/$projectId/test-runs/$runId/copies", """{"unversioned":true}""")

        assertThat(toNewer.status).isEqualTo(201)
        assertThat(toNewer.body["gameBuild"]["gameBuildId"].asLong()).isEqualTo(newer.id)
        assertThat(toNewer.body["scenarioCount"].asInt()).isEqualTo(0)
        assertThat(loose.status).isEqualTo(201)
        assertThat(loose.body.path("gameBuild").isNullish()).isTrue()
    }

    @Test
    fun `다른 프로젝트 경로나 멤버가 아닌 사람에게 TR 은 없는 것이다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val otherProjectId = newProject(userId)
        val runId = call(token, HttpMethod.POST, "/api/projects/$projectId/test-runs", """{"name":"런"}""").body["id"].asLong()

        assertThat(call(token, HttpMethod.POST, "/api/projects/$otherProjectId/test-runs/$runId/copies").status).isEqualTo(404)
        assertThat(call(signIn().second, HttpMethod.POST, "/api/projects/$projectId/test-runs/$runId/copies").status).isEqualTo(404)
        val names = call(token, HttpMethod.GET, "/api/projects/$projectId/test-runs").body["items"].map { it["name"].asText() }
        assertThat(names).containsExactly("런")
    }

    private suspend fun scenario(projectId: Long, title: String, actions: List<String>): TestScenarioEntity =
        scenarios.save(
            TestScenarioEntity(projectId = projectId).withDraft(
                ScenarioDraft(title = title, description = "", steps = actions.map { ScenarioStep(action = it) }),
                objectMapper,
            )
        )

    private suspend fun signIn(): Pair<Long, String> {
        val providerUserId = UUID.randomUUID().toString()
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = "copy-$providerUserId",
                displayName = "Copy",
                avatarUrl = null,
                email = null,
            )
        )
        return user.userId.toLong() to jwtService.issue(user)
    }

    private suspend fun newProject(memberId: Long): Long {
        val now = Instant.now()
        val projectId = projectRepository.save(ProjectEntity(name = "run-copy", genre = "RPG", createdAt = now, updatedAt = now)).id!!
        projectMemberRepository.save(ProjectMemberEntity(projectId = projectId, appUserId = memberId, role = "OWNER", createdAt = now))
        return projectId
    }

    private fun call(token: String, method: HttpMethod, uri: String, body: String? = null): Reply {
        val request = WebClient.create("http://localhost:$port").method(method).uri(uri)
            .cookie("artel_access_token", token)
        val withBody = if (body == null) request else request.contentType(MediaType.APPLICATION_JSON).bodyValue(body)
        return withBody.exchangeToMono { response ->
            response.bodyToMono(String::class.java).defaultIfEmpty("null")
                .map { text -> Reply(response.statusCode().value(), objectMapper.readTree(text)) }
        }.block()!!
    }

    private data class Reply(val status: Int, val body: JsonNode)

    private fun JsonNode.isNullish() = isMissingNode || isNull
}
