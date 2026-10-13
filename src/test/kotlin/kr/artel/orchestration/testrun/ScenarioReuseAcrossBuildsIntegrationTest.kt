package kr.artel.orchestration.testrun

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapDocumentEntity
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.ingest.ContentMapIngestService
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.FakeDocumentStorage
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.repository.TestCaseVersionRepository
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
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * 버전을 넘어 TS 를 재사용하는 흐름(ARTEL-994).
 *
 * 같은 실측 문서를 두 build 에 적재한다. 같은 케이스는 버전을 넘어 한 `test_case` 이고 버전마다 몫
 * (`test_case_version`)이 하나씩 생긴다(ARTEL-1018). 여기서 지키는 것은 셋이다.
 * - 새 버전 몫은 직전 버전에서 판정이 난 케이스면 `REGRESSION` 으로 시작하고, 앞 버전 몫은 그대로다.
 * - TS 를 새 버전 TR 로 가져오면 복사본은 같은 케이스를 가리키고, 이 버전에 없는 케이스를 알린다.
 * - 트리와 이력이 버전으로 묶인다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScenarioReuseAcrossBuildsIntegrationTest {

    @TestConfiguration
    class FakeStorageConfig {
        @Bean
        @Primary
        fun fakeDocumentStorage(): DocumentStorage = FakeDocumentStorage()
    }

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var documents: ContentMapDocumentRepository
    @Autowired private lateinit var storage: DocumentStorage
    @Autowired private lateinit var ingest: ContentMapIngestService
    @Autowired private lateinit var testCases: TestCaseRepository
    @Autowired private lateinit var versions: TestCaseVersionRepository
    @Autowired private lateinit var scenarios: TestScenarioRepository
    @Autowired private lateinit var runScenarios: TestRunScenarioRepository

    @Test
    fun `같은 케이스는 버전을 넘어 한 행이고, 직전 버전에서 판정이 난 것만 새 버전에서 REGRESSION 으로 시작한다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val buildA = ingestedBuild(projectId, "1.0.0", Instant.now().minusSeconds(60))
        val generatedA = generate(token, projectId, buildA.gameBuildId).body
        val versionsA = versions.findByGameBuildId(buildA.gameBuildId).toList().sortedBy { it.testCaseId }
        val verified = versionsA[0]
        val failed = versionsA[1]
        versions.save(verified.copy(verificationStatus = "VERIFIED"))
        versions.save(failed.copy(verificationStatus = "FAILED"))
        val identitiesBefore = testCases.countByProjectId(projectId)

        val buildB = ingestedBuild(projectId, "2.0.0", Instant.now())
        val generated = generate(token, projectId, buildB.gameBuildId)

        assertThat(generated.status).isEqualTo(200)
        assertThat(generated.body["created"].asInt()).isEqualTo(generatedA["created"].asInt())
        assertThat(generated.body["regression"].asInt()).isEqualTo(2)
        // 정체는 늘지 않는다 — 같은 케이스에 버전 몫만 하나씩 더 생긴다.
        assertThat(testCases.countByProjectId(projectId)).isEqualTo(identitiesBefore)
        val versionsB = versions.findByGameBuildId(buildB.gameBuildId).toList().associateBy { it.testCaseId }
        assertThat(versionsB.getValue(verified.testCaseId).verificationStatus).isEqualTo("REGRESSION")
        assertThat(versionsB.getValue(failed.testCaseId).verificationStatus).isEqualTo("REGRESSION")
        assertThat(versionsB.values.count { it.verificationStatus == "DRAFT" }).isEqualTo(versionsB.size - 2)
        // 앞 버전 몫이 그 버전의 이력이다. 정체는 가장 최근 버전(B)을 비춘다.
        assertThat(versions.findById(verified.id!!)!!.verificationStatus).isEqualTo("VERIFIED")
        assertThat(testCases.findById(verified.testCaseId)!!.verificationStatus).isEqualTo("REGRESSION")

        val again = generate(token, projectId, buildB.gameBuildId)
        assertThat(again.body["regression"].asInt()).isZero()
        assertThat(again.body["created"].asInt()).isZero()

        val history = call(token, HttpMethod.GET, "/api/projects/$projectId/test-cases/${verified.testCaseId}/verdict-history")
        assertThat(history.status).isEqualTo(200)
        assertThat(history.body["items"].map { it["version"].asText() to it["verificationStatus"].asText() })
            .containsExactly("1.0.0" to "VERIFIED", "2.0.0" to "REGRESSION")
    }

    @Test
    fun `TR 은 만들 때의 최신 build 를 버전으로 갖는다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val none = createRun(token, projectId, "빌드 없음")
        assertThat(none.path("gameBuild").isNullish()).isTrue()

        val now = Instant.now()
        gameBuilds.save(GameBuildEntity(projectId = projectId, version = "1.0.0", createdAt = now.minusSeconds(5), updatedAt = now))
        val latest = gameBuilds.save(GameBuildEntity(projectId = projectId, version = "1.1.0", createdAt = now, updatedAt = now))
        val run = createRun(token, projectId, "새 런")

        assertThat(run["gameBuild"]["gameBuildId"].asText()).isEqualTo(latest.id.toString())
        assertThat(run["gameBuild"]["version"].asText()).isEqualTo("1.1.0")
        val listed = call(token, HttpMethod.GET, "/api/projects/$projectId/test-runs").body["items"]
        assertThat(listed.map { it["gameBuild"]?.get("version")?.asText() }).containsExactly("1.1.0", null)
    }

    /** editor 행은 게임 버전이 아니다(ARTEL-1014). 나중에 만들어졌어도 TR 의 버전과 트리에 들지 않는다. */
    @Test
    fun `TR 의 버전과 가져오기 트리는 editor build 를 건너뛴다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val now = Instant.now()
        gameBuilds.save(
            GameBuildEntity(projectId = projectId, version = "1.0.0", isEditor = true, createdAt = now.minusSeconds(10), updatedAt = now)
        )
        val editorOnly = createRun(token, projectId, "editor 만 있음")
        assertThat(editorOnly.path("gameBuild").isNullish()).isTrue()

        gameBuilds.save(GameBuildEntity(projectId = projectId, version = "1.0.0", createdAt = now.minusSeconds(5), updatedAt = now))
        val editorNewest = gameBuilds.findEditor(projectId)!!
        gameBuilds.save(editorNewest.copy(createdAt = now, updatedAt = now))
        val run = createRun(token, projectId, "새 런")

        assertThat(run["gameBuild"]["version"].asText()).isEqualTo("1.0.0")
        assertThat(run["gameBuild"]["gameBuildId"].asText()).isNotEqualTo(editorNewest.id.toString())
        val library = call(token, HttpMethod.GET, "/api/projects/$projectId/test-scenario-library").body["versions"]
        assertThat(library.map { it["gameBuild"]?.get("gameBuildId")?.asText() })
            .containsExactly(run["gameBuild"]["gameBuildId"].asText(), null)
    }

    /**
     * editor 판정은 버전 이력이 아니다(ARTEL-1014). editor 에서 만든 TC 는 REGRESSION 으로 시작하지 않고, 다음
     * build 의 직전 build 는 editor 가 아니라 그 앞의 development build 다.
     */
    @Test
    fun `REGRESSION 과 판정 이력은 editor build 를 버전으로 세지 않는다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val buildA = ingestedBuild(projectId, "1.0.0", Instant.now().minusSeconds(120))
        generate(token, projectId, buildA.gameBuildId)
        val rowsA = rowsOf(projectId, buildA.contentMapId)
        val verified = rowsA[0]
        val untouched = rowsA[1]
        testCases.save(verified.copy(verificationStatus = "VERIFIED", lastVerifiedBuildId = buildA.gameBuildId))

        val editor = ingestedBuild(projectId, "1.0.0", Instant.now().minusSeconds(60), isEditor = true)
        val editorGenerated = generate(token, projectId, editor.gameBuildId)
        assertThat(editorGenerated.body["regression"].asInt()).isZero()
        val rowsEditor = rowsOf(projectId, editor.contentMapId).associateBy(::caseKeyOf)
        testCases.save(rowsEditor.getValue(caseKeyOf(untouched)).copy(verificationStatus = "FAILED"))

        val buildB = ingestedBuild(projectId, "2.0.0", Instant.now())
        val generated = generate(token, projectId, buildB.gameBuildId)

        // editor 의 FAILED 를 직전으로 봤다면 2건이다.
        assertThat(generated.body["regression"].asInt()).isEqualTo(1)
        val rowsB = rowsOf(projectId, buildB.contentMapId).associateBy(::caseKeyOf)
        assertThat(rowsB.getValue(caseKeyOf(verified)).verificationStatus).isEqualTo("REGRESSION")
        assertThat(rowsB.getValue(caseKeyOf(untouched)).verificationStatus).isEqualTo("DRAFT")

        val history = call(token, HttpMethod.GET, "/api/projects/$projectId/test-cases/${verified.id}/verdict-history")
        assertThat(history.body["items"].map { it["gameBuildId"].asLong() })
            .containsExactly(buildA.gameBuildId, buildB.gameBuildId)
    }

    @Test
    fun `앞 버전 TR 의 TS 를 가져오면 복사본이 같은 케이스를 가리키고, 이 버전에 없는 케이스를 알린다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val buildA = ingestedBuild(projectId, "1.0.0", Instant.now().minusSeconds(60))
        generate(token, projectId, buildA.gameBuildId)
        val runA = createRun(token, projectId, "1.0 런")["id"].asLong()
        val caseIds = versions.findByGameBuildId(buildA.gameBuildId).toList().map { it.testCaseId }.sorted()
        val manual = testCases.save(TestCaseEntity(projectId = projectId, scene = "S", step = "사람이 쓴 것", expectedValue = "x"))
        val source = scenarios.save(
            TestScenarioEntity(projectId = projectId).withDraft(
                ScenarioDraft(
                    title = "보스전",
                    description = "설명",
                    steps = listOf(
                        ScenarioStep(action = "a", caseId = caseIds[0]),
                        ScenarioStep(action = "b", caseId = caseIds[1]),
                        ScenarioStep(action = "c", caseId = manual.id),
                        ScenarioStep(action = "d"),
                    )
                ),
                objectMapper,
            )
        )
        runScenarios.save(TestRunScenarioEntity(testRunId = runA, testScenarioId = source.id!!, position = 0))
        val loose = scenarios.save(
            TestScenarioEntity(projectId = projectId).withDraft(ScenarioDraft("어디에도 없음", "", emptyList()), objectMapper)
        )

        val buildB = ingestedBuild(projectId, "2.0.0", Instant.now())
        generate(token, projectId, buildB.gameBuildId)
        // 두 번째 케이스는 2.0 에서 사라진 것으로 만든다.
        versions.delete(versions.findByTestCaseIdAndGameBuildId(caseIds[1], buildB.gameBuildId)!!)
        val runB = createRun(token, projectId, "2.0 런")["id"].asLong()
        val existing = scenarios.save(TestScenarioEntity(projectId = projectId, title = "이미 있던 것"))
        runScenarios.save(TestRunScenarioEntity(testRunId = runB, testScenarioId = existing.id!!, position = 0))

        val imported = call(
            token, HttpMethod.POST, "/api/projects/$projectId/test-runs/$runB/scenarios/imports",
            """{"testScenarioIds":["${source.id}"]}"""
        )

        assertThat(imported.status).isEqualTo(201)
        assertThat(imported.body["unmatchedCaseIds"].map { it.asText() }).containsExactly(caseIds[1].toString())
        val copyId = imported.body["importedScenarios"][0]["testScenarioId"].asLong()
        assertThat(copyId).isNotEqualTo(source.id)
        val copySteps = scenarios.findById(copyId)!!.toDraft(objectMapper).steps
        // 정체가 버전을 넘어 하나라 case_id 는 그대로다.
        assertThat(copySteps.map { it.caseId }).containsExactly(caseIds[0], caseIds[1], manual.id, null)
        assertThat(runScenarios.findByTestRunIdOrderByPosition(runB).toList().map { it.testScenarioId to it.position })
            .containsExactly(existing.id!! to 0, copyId to 1)

        val library = call(token, HttpMethod.GET, "/api/projects/$projectId/test-scenario-library").body["versions"]
        assertThat(library.map { it["gameBuild"]?.get("version")?.asText() }).containsExactly("2.0.0", "1.0.0", null)
        assertThat(library[0]["testRuns"].map { it["testRunId"].asLong() }).containsExactly(runB)
        assertThat(library[0]["testRuns"][0]["testScenarios"].map { it["title"].asText() })
            .containsExactly("이미 있던 것", "보스전")
        assertThat(library[1]["testRuns"][0]["testScenarios"][0]["stepCount"].asInt()).isEqualTo(4)
        // 수동 케이스까지 서로 다른 TC 셋, case_id 없는 스텝은 세지 않는다.
        assertThat(library[1]["testRuns"][0]["testScenarios"][0]["caseCount"].asInt()).isEqualTo(3)
        assertThat(library[2]["testRuns"].single().path("testRunId").isNullish()).isTrue()
        assertThat(library[2]["testRuns"].single()["testScenarios"].map { it["testScenarioId"].asLong() })
            .containsExactly(loose.id)
    }

    @Test
    fun `다른 프로젝트의 TS 는 가져올 수 없고 다른 프로젝트 경로의 TR 은 없는 것이다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val otherProjectId = newProject(userId)
        val run = createRun(token, projectId, "런")["id"].asLong()
        val foreign = scenarios.save(TestScenarioEntity(projectId = otherProjectId, title = "남의 것"))

        val crossScenario = call(
            token, HttpMethod.POST, "/api/projects/$projectId/test-runs/$run/scenarios/imports",
            """{"testScenarioIds":["${foreign.id}"]}"""
        )
        val crossPath = call(
            token, HttpMethod.POST, "/api/projects/$otherProjectId/test-runs/$run/scenarios/imports",
            """{"testScenarioIds":["${foreign.id}"]}"""
        )
        val empty = call(
            token, HttpMethod.POST, "/api/projects/$projectId/test-runs/$run/scenarios/imports",
            """{"testScenarioIds":[]}"""
        )

        assertThat(crossScenario.status).isEqualTo(400)
        assertThat(crossPath.status).isEqualTo(404)
        assertThat(empty.status).isEqualTo(400)
        assertThat(runScenarios.findByTestRunIdOrderByPosition(run).toList()).isEmpty()
    }

    private data class IngestedBuild(val gameBuildId: Long, val contentMapId: Long)

    private suspend fun ingestedBuild(
        projectId: Long,
        version: String,
        createdAt: Instant,
        isEditor: Boolean = false,
    ): IngestedBuild {
        val build = gameBuilds.save(
            GameBuildEntity(
                projectId = projectId, version = version, isEditor = isEditor, createdAt = createdAt, updatedAt = createdAt
            )
        )
        val map = contentMaps.save(
            ContentMapEntity(
                gameBuildId = build.id!!, schemaVersion = 7, capture = Capture.EDITOR_PLAY.wire,
                evidencePromises = Json.of(
                    """["build-info-v1","selector-v1","visual-roles-v1","persistent-objects-v1"]"""
                ),
                evidenceDigest = "d4b31e4da9504b7d",
                unity = "2022.3.62f3", backend = "mono", development = true, sdkVersion = "0.1.0",
            )
        )
        val bytes = File(DOCUMENT).readBytes()
        val objectKey = "content-map/${map.id}/wv-play-2026-09-01.json"
        (storage as FakeDocumentStorage).put(objectKey, bytes)
        ingest.ingest(
            documents.save(
                ContentMapDocumentEntity(
                    contentMapId = map.id!!, objectKey = objectKey,
                    contentHash = MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it) },
                    byteSize = bytes.size.toLong(),
                )
            )
        )
        return IngestedBuild(build.id!!, map.id!!)
    }

    private suspend fun signIn(): Pair<Long, String> {
        val providerUserId = UUID.randomUUID().toString()
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = "reuse-$providerUserId",
                displayName = "Reuse",
                avatarUrl = null,
                email = null,
            )
        )!!
        return user.userId.toLong() to jwtService.issue(user)
    }

    private suspend fun newProject(memberId: Long): Long {
        val now = Instant.now()
        val projectId = projectRepository.save(
            ProjectEntity(name = "scenario-reuse", genre = "RPG", createdAt = now, updatedAt = now)
        ).id!!
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = projectId, appUserId = memberId, role = "OWNER", createdAt = now)
        )
        return projectId
    }

    private fun createRun(token: String, projectId: Long, name: String): JsonNode =
        call(token, HttpMethod.POST, "/api/projects/$projectId/test-runs", """{"name":"$name"}""")
            .also { assertThat(it.status).isEqualTo(201) }.body

    private fun generate(token: String, projectId: Long, gameBuildId: Long): Reply =
        call(token, HttpMethod.POST, "/api/projects/$projectId/game-builds/$gameBuildId/content-map/test-cases")

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

    /** null 칸을 응답에서 빼든 null 로 싣든 같은 뜻으로 읽는다. */
    private fun JsonNode.isNullish() = isMissingNode || isNull

    companion object {
        private const val DOCUMENT = "src/test/resources/contentmap/wv-play-2026-09-01.json"
    }
}
