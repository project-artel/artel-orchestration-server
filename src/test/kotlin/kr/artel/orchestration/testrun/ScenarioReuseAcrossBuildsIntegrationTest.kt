package kr.artel.orchestration.testrun

import org.springframework.r2dbc.core.DatabaseClient
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kr.artel.orchestration.assistant.ResourceQueryService
import kr.artel.orchestration.assistant.ResourceRequest
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
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.FakeDocumentStorage
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.qa.entity.QaLogEntity
import kr.artel.orchestration.qa.entity.QaRunEntity
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.support.deleteQaRuns
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.entity.TestCaseVersionEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.repository.TestCaseVersionRepository
import kr.artel.orchestration.testcase.service.CaseScope
import kr.artel.orchestration.testcase.service.TestCaseService
import kr.artel.orchestration.testrun.entity.TestRunScenarioEntity
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ScenarioExecutionMode
import kr.artel.orchestration.testscenario.dto.ScenarioStep
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.entity.toDraft
import kr.artel.orchestration.testscenario.entity.withDraft
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import kr.artel.orchestration.testscenario.service.AuthoringCases
import kr.artel.orchestration.testscenario.service.AuthoringVersion
import kr.artel.orchestration.testscenario.service.AuthoringVersionResolver
import kr.artel.orchestration.testscenario.service.ScenarioCaseFactService
import kr.artel.orchestration.testscenario.service.ScenarioCompositionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
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
    @Autowired private lateinit var gameInstances: GameInstanceRepository
    @Autowired private lateinit var qaRuns: QaRunRepository
    @Autowired private lateinit var qaTries: QaTryRepository
    @Autowired private lateinit var caseFactService: ScenarioCaseFactService
    @Autowired private lateinit var authoringVersions: AuthoringVersionResolver
    @Autowired private lateinit var testCaseService: TestCaseService
    @Autowired private lateinit var authoringCases: AuthoringCases
    @Autowired private lateinit var compositionService: ScenarioCompositionService
    @Autowired private lateinit var resources: ResourceQueryService
    @Autowired private lateinit var qaLogs: QaLogRepository
    @Autowired private lateinit var db: DatabaseClient

    /**
     * 이 클래스가 만든 `qa_run` 을 스위트의 다음 클래스에 넘기지 않는다(ARTEL-1163).
     * 리액티브 트랜잭션은 롤백되지 않고 스위트가 DB 하나를 공유하므로, 남은 `qa_run` 은 뒤에 오는
     * 클래스의 `DELETE FROM game_instance` · `test_run` · `app_user` 를
     * `qa_run_game_instance_id_fkey` · `qa_run_test_run_id_fkey` · `qa_run_started_by_fkey` 로 막는다.
     */
    @AfterEach
    fun removeQaRuns() {
        db.deleteQaRuns()
    }

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

        // 같은 build 는 다시 만들지 않는다(ARTEL-1040).
        assertThat(generate(token, projectId, buildB.gameBuildId).status).isEqualTo(409)

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

    /** TR 은 만들 때 TC 버전을 고르고 나중에 바꿀 수 있다(ARTEL-1048). editor 와 남의 빌드는 고를 수 없다. */
    @Test
    fun `TR 은 고른 TC 버전을 따르고 나중에 바꿀 수 있다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val now = Instant.now()
        val older = gameBuilds.save(GameBuildEntity(projectId = projectId, version = "1.0.0", createdAt = now.minusSeconds(5), updatedAt = now))
        val newer = gameBuilds.save(GameBuildEntity(projectId = projectId, version = "1.1.0", createdAt = now, updatedAt = now))
        val editor = gameBuilds.save(GameBuildEntity(projectId = projectId, version = "1.1.0", isEditor = true, createdAt = now, updatedAt = now))
        val foreign = gameBuilds.save(
            GameBuildEntity(projectId = newProject(userId), version = "9.0.0", createdAt = now, updatedAt = now)
        )
        val runs = "/api/projects/$projectId/test-runs"

        val picked = call(token, HttpMethod.POST, runs, """{"name":"앞 버전","gameBuildId":"${older.id}"}""")
        assertThat(picked.body["gameBuild"]["version"].asText()).isEqualTo("1.0.0")
        val unversioned = call(token, HttpMethod.POST, runs, """{"name":"버전 없음","unversioned":true}""")
        assertThat(unversioned.body.path("gameBuild").isNullish()).isTrue()
        // editor 버전도 TR 의 버전으로 고를 수 있다(ARTEL-1180).
        assertThat(call(token, HttpMethod.POST, runs, """{"name":"x","gameBuildId":"${editor.id}"}""").body["gameBuild"]["isEditor"].asBoolean())
            .isTrue()
        assertThat(call(token, HttpMethod.POST, runs, """{"name":"x","gameBuildId":"${foreign.id}"}""").status).isEqualTo(404)

        val runId = picked.body["id"].asText()
        val moved = call(token, HttpMethod.PUT, "$runs/$runId/game-build", """{"gameBuildId":"${newer.id}"}""")
        assertThat(moved.status).isEqualTo(200)
        assertThat(moved.body["gameBuild"]["gameBuildId"].asText()).isEqualTo(newer.id.toString())
        val cleared = call(token, HttpMethod.PUT, "$runs/$runId/game-build", """{"gameBuildId":null}""")
        assertThat(cleared.body.path("gameBuild").isNullish()).isTrue()
        assertThat(call(token, HttpMethod.PUT, "$runs/$runId/game-build", """{"gameBuildId":"${editor.id}"}""").body["gameBuild"]["gameBuildId"].asText())
            .isEqualTo(editor.id.toString())
    }

    /**
     * 테스트 런 폴더(ARTEL-1049)는 한 빌드 버전 안에만 있다. 폴더 안에 만든 런은 그 버전을 따르고, 다른 버전의 폴더로
     * 옮기면 버전도 바뀐다. 버전만 바꾸면 폴더에서 나온다. 폴더를 지우면 안의 런은 버전 바로 아래로 나온다.
     */
    @Test
    fun `테스트 런 폴더는 빌드 버전 안에만 있고 런을 담는다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val now = Instant.now()
        val older = gameBuilds.save(GameBuildEntity(projectId = projectId, version = "1.0.0", createdAt = now.minusSeconds(5), updatedAt = now))
        val newer = gameBuilds.save(GameBuildEntity(projectId = projectId, version = "1.1.0", createdAt = now, updatedAt = now))
        val folders = "/api/projects/$projectId/test-run-folders"
        val runs = "/api/projects/$projectId/test-runs"

        val regression = call(token, HttpMethod.POST, folders, """{"name":"회귀","gameBuildId":"${older.id}"}""")
        assertThat(regression.status).isEqualTo(201)
        val regressionId = regression.body["id"].asText()
        val nested = call(token, HttpMethod.POST, folders, """{"name":"상점","parentId":"$regressionId"}""").body
        assertThat(nested["gameBuildId"].asLong()).isEqualTo(older.id)
        assertThat(call(token, HttpMethod.POST, folders, """{"name":"회귀","gameBuildId":"${older.id}"}""").status).isEqualTo(409)
        assertThat(call(token, HttpMethod.POST, folders, """{"name":"회귀","gameBuildId":"${newer.id}"}""").status).isEqualTo(201)
        val newerFolder = call(token, HttpMethod.POST, folders, """{"name":"새 버전 폴더","gameBuildId":"${newer.id}"}""").body["id"].asText()

        val inFolder = call(token, HttpMethod.POST, runs, """{"name":"폴더 안","folderId":"$regressionId"}""").body
        assertThat(inFolder["gameBuild"]["gameBuildId"].asLong()).isEqualTo(older.id)
        assertThat(inFolder["folderId"].asText()).isEqualTo(regressionId)
        val runId = inFolder["id"].asText()

        val moved = call(token, HttpMethod.PUT, "$runs/$runId/game-build", """{"folderId":"$newerFolder"}""").body
        assertThat(moved["gameBuild"]["gameBuildId"].asLong()).isEqualTo(newer.id)
        assertThat(moved["folderId"].asText()).isEqualTo(newerFolder)
        val out = call(token, HttpMethod.PUT, "$runs/$runId/game-build", """{"gameBuildId":"${newer.id}"}""").body
        assertThat(out.path("folderId").isNullish()).isTrue()

        call(token, HttpMethod.PUT, "$runs/$runId/game-build", """{"folderId":"$regressionId"}""")
        assertThat(call(token, HttpMethod.PUT, "$folders/$regressionId", """{"name":"회귀 묶음"}""").body["name"].asText()).isEqualTo("회귀 묶음")
        assertThat(call(token, HttpMethod.DELETE, "$folders/$regressionId").status).isEqualTo(204)
        val after = call(token, HttpMethod.GET, runs).body["items"].single { it["id"].asText() == runId }
        assertThat(after.path("folderId").isNullish()).isTrue()
        assertThat(after["gameBuild"]["gameBuildId"].asLong()).isEqualTo(older.id)
        val left = call(token, HttpMethod.GET, folders).body["items"].map { it["name"].asText() }
        assertThat(left).containsExactlyInAnyOrder("회귀", "새 버전 폴더")
        assertThat(call(signIn().second, HttpMethod.GET, folders).status).isEqualTo(404)
    }

    /** 테스트 런 화면의 표가 쓰는 요약(ARTEL-1048): 담은 시나리오 수와 마지막 QA 실행의 결과 수. */
    @Test
    fun `TR 목록은 시나리오 수와 마지막 QA 결과를 싣는다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val runId = createRun(token, projectId, "요약")["id"].asLong()
        val idle = createRun(token, projectId, "빈 런")["id"].asLong()
        val draft = ScenarioDraft(title = "s", description = "", steps = listOf(ScenarioStep(action = "a")))
        repeat(2) { position ->
            val scenarioId = scenarios.save(TestScenarioEntity(projectId = projectId).withDraft(draft, objectMapper)).id!!
            runScenarios.save(TestRunScenarioEntity(testRunId = runId, testScenarioId = scenarioId, position = position))
        }
        val now = Instant.now()
        val instance = gameInstances.save(
            GameInstanceEntity(projectId = projectId, name = "i", platform = "UNITY", sdkUuid = UUID.randomUUID().toString(), createdAt = now, updatedAt = now)
        )
        val scenarioIds = runScenarios.findByTestRunIdOrderByPosition(runId).toList().map { it.testScenarioId }
        qaRuns.save(QaRunEntity(testRunId = runId, gameInstanceId = instance.id!!, startedBy = userId, status = "FAILED", startedAt = now.minusSeconds(60), completedAt = now.minusSeconds(30)))
        val last = qaRuns.save(QaRunEntity(testRunId = runId, gameInstanceId = instance.id, startedBy = userId, status = "FAILED", startedAt = now, completedAt = now))
        listOf("COMPLETED", "FAILED").zip(scenarioIds).forEach { (status, scenarioId) ->
            qaTries.save(
                QaTryEntity(
                    testScenarioId = scenarioId, gameInstanceId = instance.id, qaRunId = last.id, startedBy = userId,
                    status = status, runConfig = Json.of("{}"), startedAt = now, completedAt = now,
                )
            )
        }

        val items = call(token, HttpMethod.GET, "/api/projects/$projectId/test-runs").body["items"]
            .associateBy { it["id"].asLong() }

        assertThat(items[runId]!!["scenarioCount"].asInt()).isEqualTo(2)
        val lastQa = items[runId]!!["lastQaRun"]
        assertThat(lastQa["qaRunId"].asLong()).isEqualTo(last.id)
        assertThat(listOf(lastQa["passed"].asInt(), lastQa["failed"].asInt(), lastQa["total"].asInt())).containsExactly(1, 1, 2)
        assertThat(items[idle]!!["scenarioCount"].asInt()).isZero()
        assertThat(items[idle]!!.path("lastQaRun").isNullish()).isTrue()
    }

    /**
     * 저작은 TR 이 고른 TC 버전의 지도와 TC 만 본다(ARTEL-1048). 최신 빌드에 지도가 없어도, 맥락이 앞 버전을 가리키면 그
     * 지도로 케이스를 비춘다. 저작에 보내는 목록도 그 버전에 든 것만이다.
     */
    @Test
    fun `저작은 TR 이 고른 버전의 지도와 TC 만 본다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val buildA = ingestedBuild(projectId, "1.0.0", Instant.now().minusSeconds(60))
        generate(token, projectId, buildA.gameBuildId)
        val now = Instant.now()
        gameBuilds.save(GameBuildEntity(projectId = projectId, version = "1.1.0", createdAt = now, updatedAt = now))
        val caseId = versions.findByGameBuildId(buildA.gameBuildId).toList().first().testCaseId
        val manual = testCases.save(TestCaseEntity(projectId = projectId, scene = "S", step = "다른 버전에만", expectedValue = "x"))

        val latest = caseFactService.explain(projectId, userId, caseId)
        assertThat(latest.note).contains("씬 명세가 아직 없어")
        val inVersion = withContext(AuthoringVersion(buildA.gameBuildId)) { caseFactService.explain(projectId, userId, caseId) }
        assertThat(inVersion.note).doesNotContain("씬 명세가 아직 없어")

        val runId = call(token, HttpMethod.POST, "/api/projects/$projectId/test-runs", """{"name":"앞 버전","gameBuildId":"${buildA.gameBuildId}"}""")
            .body["id"].asLong()
        val version = authoringVersions.of(runId)
        assertThat(version.gameBuildId).isEqualTo(buildA.gameBuildId)
        val sent = testCaseService.getAuthoringCases(projectId, userId, authoringVersions.caseIds(buildA.gameBuildId)).map { it.id }
        assertThat(sent).contains(caseId).doesNotContain(manual.id)
        assertThat(testCaseService.getAuthoringCases(projectId, userId).map { it.id }).contains(manual.id)
    }

    /**
     * 같은 지도를 적재한 버전이면 저작에 보내는 목록이 맥락이 없을 때와 **똑같다**(ARTEL-1064). 버전 몫을 읽게 바꾼
     * 것이 같은 지도 프로젝트의 저작을 조금도 바꾸지 않는다는 전후 비교다.
     */
    @Test
    fun `같은 지도 버전이면 저작 목록 · 화면 간선 · 시작값이 맥락 없을 때와 같다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val build = ingestedBuild(projectId, "1.0.0", Instant.now())
        generate(token, projectId, build.gameBuildId)
        val inVersion = authoringVersions.caseIds(build.gameBuildId)

        val before = testCaseService.getAuthoringCases(projectId, userId).filter { it.id in inVersion }
        val after = withContext(AuthoringVersion(build.gameBuildId)) { testCaseService.getAuthoringCases(projectId, userId, inVersion) }

        assertThat(after).isNotEmpty().isEqualTo(before)
        assertThat(withContext(AuthoringVersion(build.gameBuildId)) { testCaseService.sceneEdges(projectId) })
            .isEqualTo(testCaseService.sceneEdges(projectId))
        assertThat(withContext(AuthoringVersion(build.gameBuildId)) { testCaseService.startingValues(projectId) })
            .isEqualTo(testCaseService.startingValues(projectId))
    }

    /**
     * 버전마다 내용이 다르면 각 런이 자기 버전의 내용과 능력을 쓴다(ARTEL-1064). B 의 몫만 스텝을 바꾸고 B 지도에 없는
     * 능력을 가리키게 하면, B 를 따르는 저작은 바뀐 스텝과 "누를 것 없음" 을 받고 A 를 따르는 저작은 예전 것을 받는다.
     * 그 버전에서 BROKEN 인 몫은 보내지 않는다.
     */
    @Test
    fun `버전마다 내용이 다르면 저작은 자기 버전의 내용과 능력을 쓴다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val buildA = ingestedBuild(projectId, "1.0.0", Instant.now().minusSeconds(60))
        generate(token, projectId, buildA.gameBuildId)
        val buildB = ingestedBuild(projectId, "1.1.0", Instant.now())
        generate(token, projectId, buildB.gameBuildId)
        val listA = withContext(AuthoringVersion(buildA.gameBuildId)) {
            testCaseService.getAuthoringCases(projectId, userId, authoringVersions.caseIds(buildA.gameBuildId))
        }
        val target = listA.first { it.input.isNotEmpty() }
        val original = target.step
        val rowB = versions.findByTestCaseIdAndGameBuildId(target.id, buildB.gameBuildId)!!
        versions.save(rowB.copy(step = "B 에서 바뀐 스텝", capabilityKey = "no-such-capability"))

        val inA = withContext(AuthoringVersion(buildA.gameBuildId)) {
            testCaseService.getAuthoringCases(projectId, userId, authoringVersions.caseIds(buildA.gameBuildId))
        }.single { it.id == target.id }
        val inB = withContext(AuthoringVersion(buildB.gameBuildId)) {
            testCaseService.getAuthoringCases(projectId, userId, authoringVersions.caseIds(buildB.gameBuildId))
        }.single { it.id == target.id }
        assertThat(inA.step).isEqualTo(original)
        assertThat(inA.input).isNotEmpty()
        assertThat(inB.step).isEqualTo("B 에서 바뀐 스텝")
        assertThat(inB.input).isEmpty()
        assertThat(withContext(AuthoringVersion(buildB.gameBuildId)) { authoringCases.byId(target.id) }!!.step).isEqualTo("B 에서 바뀐 스텝")
        // 맥락이 없으면 대표 행 그대로다.
        assertThat(authoringCases.byId(target.id)!!.step).isEqualTo(testCases.findById(target.id)!!.step)

        versions.save(versions.findByTestCaseIdAndGameBuildId(target.id, buildB.gameBuildId)!!.copy(verificationStatus = "BROKEN"))
        assertThat(authoringVersions.caseIds(buildB.gameBuildId)).doesNotContain(target.id)
        val sentB = withContext(AuthoringVersion(buildB.gameBuildId)) {
            testCaseService.getAuthoringCases(projectId, userId, authoringVersions.caseIds(buildB.gameBuildId))
        }
        assertThat(sentB.map { it.id }).doesNotContain(target.id)
    }

    /**
     * 판정 기준은 TR 버전의 내용이고 기록은 켜진 게임 버전이다(ARTEL-1069). 게임 버전에 없는 케이스는 기록할 자리가
     * 없으므로 `case_id` 를 비워 판정 없이 넘긴다. 사람이 쓴 케이스는 버전 몫이 스냅샷일 뿐이라 사람이 고친 정체의
     * 내용을 보낸다.
     */
    @Test
    fun `QA 는 TR 버전의 내용으로 판정하고 게임 버전에 없는 케이스는 판정 없이 넘긴다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val buildA = ingestedBuild(projectId, "1.0.0", Instant.now().minusSeconds(60))
        generate(token, projectId, buildA.gameBuildId)
        val buildB = ingestedBuild(projectId, "1.1.0", Instant.now())
        generate(token, projectId, buildB.gameBuildId)
        val caseIds = versions.findByGameBuildId(buildA.gameBuildId).toList().map { it.testCaseId }.sorted()
        val changed = caseIds[0]
        val gone = caseIds[1]
        val expectedInA = versions.findByTestCaseIdAndGameBuildId(changed, buildA.gameBuildId)!!.expectedValue
        versions.save(versions.findByTestCaseIdAndGameBuildId(changed, buildB.gameBuildId)!!.copy(expectedValue = "B 의 기대 결과"))
        versions.delete(versions.findByTestCaseIdAndGameBuildId(gone, buildB.gameBuildId)!!)
        val manual = testCases.save(TestCaseEntity(projectId = projectId, scene = "S", step = "사람이 고친 것", expectedValue = "x"))
        versions.save(TestCaseVersionEntity.snapshotOf(manual, buildB.gameBuildId).copy(step = "판정 받던 때의 스냅샷"))
        val draft = ScenarioDraft(
            title = "t",
            description = "d",
            steps = listOf(
                ScenarioStep(action = "a", caseId = changed),
                ScenarioStep(action = "b", caseId = gone),
                ScenarioStep(action = "c", caseId = manual.id),
                ScenarioStep(action = "d"),
            ),
        )

        // TR 은 1.0, 게임은 1.1.
        val sent = compositionService.agentScenario(draft, CaseScope.Version(buildA.gameBuildId), buildB.gameBuildId).steps

        assertThat(sent[0].case!!.expected).isEqualTo(expectedInA)
        assertThat(sent[1].caseId).isNull()
        assertThat(sent[1].case).isNull()
        assertThat(sent[1].action).isEqualTo("b")
        // 1.0 에 몫이 없는 사람이 쓴 케이스도 1.1 에 적을 자리가 있으면 판정한다. 내용은 사람이 고친 것이다.
        assertThat(sent[2].caseId).isEqualTo(manual.id)
        assertThat(sent[2].case!!.testStep).isEqualTo("사람이 고친 것")
        // 같은 TR 이 1.1 게임으로 돌아도 판정 기준은 그대로 1.0 의 내용이다.
        assertThat(compositionService.agentScenario(draft, CaseScope.Version(buildB.gameBuildId), buildB.gameBuildId).steps[0].case!!.expected)
            .isEqualTo("B 의 기대 결과")
        // 게임 버전을 모르면 거르지 않고, 버전 없는 TR 은 정체(최신) 내용이다.
        val unknown = compositionService.agentScenario(draft).steps
        assertThat(unknown.map { it.caseId }).containsExactly(changed, gone, manual.id, null)
        // FREE 는 스텝이 곧 케이스다. 게임 버전에 없는 케이스는 비우지 않고 뺀다.
        val free = draft.copy(
            steps = draft.steps.filter { it.caseId != null },
            executionMode = ScenarioExecutionMode.FREE,
        )
        val freeSent = compositionService.agentScenario(free, CaseScope.Version(buildA.gameBuildId), buildB.gameBuildId).steps
        assertThat(freeSent.map { it.caseId }).containsExactly(changed, manual.id)
    }

    /**
     * TR 화면이 읽는 케이스 · 커버리지는 TR 의 버전을 따르고, QA 전에 켜진 게임 버전에 없는 케이스를 알린다(ARTEL-1069).
     * editor 로 켠 게임은 같은 버전의 게임 빌드로 판정한다.
     */
    @Test
    fun `TR 화면의 케이스와 커버리지는 TR 버전을 따르고 게임 버전에 없는 케이스를 알린다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val buildA = ingestedBuild(projectId, "1.0.0", Instant.now().minusSeconds(60))
        generate(token, projectId, buildA.gameBuildId)
        val buildB = ingestedBuild(projectId, "1.1.0", Instant.now())
        generate(token, projectId, buildB.gameBuildId)
        val inA = authoringVersions.caseIds(buildA.gameBuildId)
        val caseIds = inA.sorted()
        versions.save(versions.findByTestCaseIdAndGameBuildId(caseIds[0], buildB.gameBuildId)!!.copy(step = "B 의 스텝"))
        versions.delete(versions.findByTestCaseIdAndGameBuildId(caseIds[1], buildB.gameBuildId)!!)
        val manual = testCases.save(TestCaseEntity(projectId = projectId, scene = "S", step = "버전 없음", expectedValue = "x"))
        val runId = call(token, HttpMethod.POST, "/api/projects/$projectId/test-runs", """{"name":"1.0 런","gameBuildId":"${buildA.gameBuildId}"}""")
            .body["id"].asLong()
        val scenario = scenarios.save(
            TestScenarioEntity(projectId = projectId).withDraft(
                ScenarioDraft(
                    title = "s",
                    description = "",
                    steps = listOf(
                        ScenarioStep(action = "a", caseId = caseIds[0]),
                        ScenarioStep(action = "b", caseId = caseIds[1]),
                        ScenarioStep(action = "c", caseId = manual.id),
                    ),
                ),
                objectMapper,
            )
        )
        runScenarios.save(TestRunScenarioEntity(testRunId = runId, testScenarioId = scenario.id!!, position = 0))

        val coverage = call(token, HttpMethod.GET, "/api/projects/$projectId/test-runs/$runId/coverage").body
        assertThat(coverage["total"].asInt()).isEqualTo(inA.size)
        assertThat(coverage["covered"].asInt()).isEqualTo(2)

        val listA = call(token, HttpMethod.GET, "/api/projects/$projectId/test-cases?gameBuildId=${buildA.gameBuildId}").body["items"]
        assertThat(listA.map { it["id"].asLong() }).containsExactlyInAnyOrderElementsOf(inA)
        val listB = call(token, HttpMethod.GET, "/api/projects/$projectId/test-cases?gameBuildId=${buildB.gameBuildId}").body["items"]
        assertThat(listB.map { it["id"].asLong() }).doesNotContain(caseIds[1], manual.id)
        fun detail(build: Long) = call(token, HttpMethod.GET, "/api/projects/$projectId/test-cases/${caseIds[0]}?gameBuildId=$build").body
        assertThat(detail(buildB.gameBuildId)["step"].asText()).isEqualTo("B 의 스텝")
        assertThat(detail(buildA.gameBuildId)["step"].asText()).isNotEqualTo("B 의 스텝")

        val now = Instant.now()
        val editor = gameBuilds.save(
            GameBuildEntity(projectId = projectId, version = "1.1.0", isEditor = true, createdAt = now, updatedAt = now)
        )
        suspend fun outside(build: Long?): JsonNode {
            val instance = gameInstances.save(
                GameInstanceEntity(
                    projectId = projectId, name = "i", platform = "UNITY", sdkUuid = UUID.randomUUID().toString(),
                    lastGameBuildId = build, createdAt = now, updatedAt = now,
                )
            )
            return call(token, HttpMethod.GET, "/api/projects/$projectId/test-runs/$runId/cases-outside-build?gameInstanceId=${instance.id}").body
        }
        val onB = outside(buildB.gameBuildId)
        assertThat(onB["gameBuildId"].asLong()).isEqualTo(buildB.gameBuildId)
        assertThat(onB["testCaseIds"].map { it.asLong() }).containsExactly(caseIds[1], manual.id)
        assertThat(outside(editor.id)["testCaseIds"].map { it.asLong() }).containsExactly(caseIds[1], manual.id)
        val onUnknown = outside(null)
        assertThat(onUnknown.path("gameBuildId").isNullish()).isTrue()
        assertThat(onUnknown["testCaseIds"]).isEmpty()
    }

    /**
     * 범용 질문 Agent 의 리소스 조회(ARTEL-1085, POC). 옛 버전에만 있는 케이스를 찾고, 버전 사이 차이와 실패 이유를
     * 따라간다. 다른 프로젝트의 id 는 없는 것과 같다.
     */
    @Test
    fun `리소스 조회는 버전을 넘어 케이스를 찾고 버전 차이와 실패 이유를 따라간다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val buildA = ingestedBuild(projectId, "1.0.0", Instant.now().minusSeconds(60))
        generate(token, projectId, buildA.gameBuildId)
        val buildB = ingestedBuild(projectId, "1.1.0", Instant.now())
        generate(token, projectId, buildB.gameBuildId)
        val caseIds = versions.findByGameBuildId(buildA.gameBuildId).toList().map { it.testCaseId }.sorted()
        val changed = caseIds[0]
        val oldOnly = caseIds[1]
        versions.save(versions.findByTestCaseIdAndGameBuildId(changed, buildB.gameBuildId)!!.copy(expectedValue = "B 의 기대 결과"))
        versions.delete(versions.findByTestCaseIdAndGameBuildId(oldOnly, buildB.gameBuildId)!!)
        val scenario = scenarios.save(
            TestScenarioEntity(projectId = projectId).withDraft(
                ScenarioDraft("보스전", "", listOf(ScenarioStep(action = "보스를 잡는다", caseId = changed))), objectMapper,
            )
        )
        val runId = call(token, HttpMethod.POST, "/api/projects/$projectId/test-runs", """{"name":"1.1 런","gameBuildId":"${buildB.gameBuildId}"}""")
            .body["id"].asLong()
        runScenarios.save(TestRunScenarioEntity(testRunId = runId, testScenarioId = scenario.id!!, position = 0))
        val now = Instant.now()
        val instance = gameInstances.save(
            GameInstanceEntity(projectId = projectId, name = "i", platform = "UNITY", sdkUuid = UUID.randomUUID().toString(), createdAt = now, updatedAt = now)
        )
        val qaRun = qaRuns.save(QaRunEntity(testRunId = runId, gameInstanceId = instance.id!!, startedBy = userId, status = "FAILED", startedAt = now, completedAt = now, gameBuildId = buildB.gameBuildId))
        val qaTry = qaTries.save(
            QaTryEntity(testScenarioId = scenario.id, gameInstanceId = instance.id, qaRunId = qaRun.id, startedBy = userId,
                status = "FAILED", runConfig = Json.of("{}"), startedAt = now, completedAt = now)
        )
        listOf(
            """{"step":1,"status":"FAILED","case_id":null,"message":"전투 화면에 들어가지 못했다","is_verification":false}""",
            """{"step":2,"status":"FAILED","case_id":$changed,"message":"보스 HP 가 그대로 남았다","is_verification":true}""",
        ).forEach { payload ->
            qaLogs.save(QaLogEntity(qaTryId = qaTry.id!!, direction = "AGENT_TO_ORCHE", type = "STATUS", message = "step", payload = Json.of(payload)))
        }
        val trScope = ResourceQueryService.Scope(projectId, buildB.gameBuildId)
        db.sql("INSERT INTO issue (qa_try_id, severity, title, detail, status, reported_at) VALUES (:t, 'MAJOR', '조합 후 공격카드가 생기지 않음', CAST(:d AS jsonb), 'OPEN', now())")
            .bind("t", qaTry.id!!).bind("d", "{\"note\":\"카드만 소비됨\"}").fetch().rowsUpdated().awaitSingle()
        // 이슈를 낱말로 찾는다(detail 은 jsonb 라 글자로 바꿔 견준다).
        val issue = resources.query(trScope, ResourceRequest("find", "issue", text = "조합 버튼 관련 공격카드 이슈")).items.single()
        assertThat(issue.fields["status"]).isEqualTo("OPEN")
        assertThat(issue.fields["detail"] as String).contains("카드만 소비됨")

        // 기본은 이 런의 버전 안에서만 찾는다. 옛 버전에만 있는 케이스는 다른 버전을 물을 때(all_versions)만 나온다.
        val oldCase = versions.findByTestCaseIdAndGameBuildId(oldOnly, buildA.gameBuildId)!!
        assertThat(resources.query(trScope, ResourceRequest("find", "case", text = oldCase.step.take(12))).items.map { it.id })
            .doesNotContain(oldOnly)
        val found = resources.query(trScope, ResourceRequest("find", "case", text = oldCase.step.take(12), allVersions = true))
        val hit = found.items.first { it.id == oldOnly }
        assertThat(hit.ref).isEqualTo("[[tc:$oldOnly]]")
        assertThat(hit.fields["in_this_test_run_version"]).isEqualTo(false)
        @Suppress("UNCHECKED_CAST")
        assertThat((hit.fields["versions"] as List<Map<String, Any?>>).map { it["version"] }).containsExactly("1.0.0")

        // 버전 사이 차이 — 1.0 의 기대 결과가 이 런의 버전(1.1)과 다르다.
        val versionsOf = resources.query(trScope, ResourceRequest("related", "case", id = changed, relation = "versions"))
        val v10 = versionsOf.items.single { it.fields["version"] == "1.0.0" }
        assertThat(v10.fields["differs_from_this_test_run_version"] as List<*>).containsExactly("expected")
        assertThat(versionsOf.items.single { it.fields["version"] == "1.1.0" }.fields["is_this_test_run_version"]).isEqualTo(true)

        // 실패 이유 — 검증 스텝의 이유와, 앞서 막힌 스텝.
        val verdicts = resources.query(trScope, ResourceRequest("related", "case", id = changed, relation = "verdicts"))
        val failed = verdicts.items.single()
        assertThat(failed.fields["result"]).isEqualTo("FAILED")
        assertThat(failed.fields["reason"]).isEqualTo("보스 HP 가 그대로 남았다")
        assertThat(failed.fields["game_version"]).isEqualTo("1.1.0")
        @Suppress("UNCHECKED_CAST")
        assertThat((failed.fields["earlier_failed_steps"] as List<Map<String, Any?>>).map { it["message"] })
            .containsExactly("전투 화면에 들어가지 못했다")

        // 관계 따라가기 — 케이스 → 시나리오, QA 시도 요약, 런 → QA 실행.
        assertThat(resources.query(trScope, ResourceRequest("related", "case", id = changed, relation = "scenarios")).items.map { it.ref })
            .containsExactly("[[ts:${scenario.id}]]")
        // 시나리오를 읽으면 스텝마다 화면의 TC 순번과 케이스 표식이 있다(jsonb 를 내용으로 읽는다).
        @Suppress("UNCHECKED_CAST")
        val steps = resources.query(trScope, ResourceRequest("get", "scenario", id = scenario.id)).items.single().fields["steps"] as List<Map<String, Any?>>
        assertThat(steps.single()).containsEntry("tc_no", 1)
        assertThat((steps.single()["case"] as Map<*, *>)["ref"]).isEqualTo("[[tc:$changed]]")
        // 버전 없는 사용자 케이스는 그렇다고 적는다.
        val manual = testCases.save(TestCaseEntity(projectId = projectId, scene = "S", step = "설정 창을 연다", expectedValue = "x"))
        assertThat(resources.query(trScope, ResourceRequest("get", "case", id = manual.id)).items.single().fields).containsKey("version_note")
        // editor 행도 버전이다(ARTEL-1180). 가장 나중에 생긴 editor 버전이 최신이다.
        val editorLatest = gameBuilds.save(
            GameBuildEntity(projectId = projectId, version = "1.1.0", isEditor = true, createdAt = Instant.now().plusSeconds(60), updatedAt = Instant.now())
        )
        val builds = resources.query(trScope, ResourceRequest("find", "build")).items
        assertThat(builds.single { it.fields["latest_game_version"] == true }.id).isEqualTo(editorLatest.id)
        val tryItem = resources.query(trScope, ResourceRequest("get", "qa_try", id = qaTry.id)).items.single()
        assertThat((tryItem.fields["failed_steps"] as List<*>)).hasSize(2)
        assertThat(resources.query(trScope, ResourceRequest("related", "test_run", id = runId, relation = "qa_runs")).items.map { it.id })
            .containsExactly(qaRun.id)

        // 다른 프로젝트 범위에서는 없는 것이다. 잘못 부르면 쓸 수 있는 것을 말로 돌려준다.
        // 멤버가 아니면 이 프로젝트의 무엇도 없다 — 세션을 연 뒤라도 조회마다 다시 본다.
        val (stranger, _) = signIn()
        val asStranger = ResourceQueryService.Scope(projectId, buildB.gameBuildId, userId = stranger)
        assertThat(resources.query(asStranger, ResourceRequest("get", "case", id = changed)).items).isEmpty()
        assertThat(resources.query(asStranger, ResourceRequest("find", "issue", text = "공격카드")).items).isEmpty()
        val other = ResourceQueryService.Scope(newProject(userId), null)
        assertThat(resources.query(other, ResourceRequest("get", "case", id = changed)).items).isEmpty()
        assertThat(resources.query(trScope, ResourceRequest("related", "case", id = changed, relation = "nope")).note).contains("versions")
    }

    /** editor 행도 그 version 의 버전이다(ARTEL-1180). 가장 최근에 생긴 버전이 TR 의 버전이 되고 트리에도 든다. */
    @Test
    fun `TR 의 버전과 가져오기 트리에 editor 버전도 든다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val now = Instant.now()
        val editor = gameBuilds.save(
            GameBuildEntity(projectId = projectId, version = "1.0.0", isEditor = true, createdAt = now.minusSeconds(10), updatedAt = now)
        )
        val editorOnly = createRun(token, projectId, "editor 만 있음")
        assertThat(editorOnly["gameBuild"]["gameBuildId"].asText()).isEqualTo(editor.id.toString())

        val build = gameBuilds.save(GameBuildEntity(projectId = projectId, version = "1.0.0", createdAt = now.minusSeconds(5), updatedAt = now))
        val onBuild = createRun(token, projectId, "빌드 런")
        assertThat(onBuild["gameBuild"]["gameBuildId"].asText()).isEqualTo(build.id.toString())

        val bumped = gameBuilds.save(
            GameBuildEntity(projectId = projectId, version = "1.1.0", isEditor = true, createdAt = now, updatedAt = now)
        )
        val onBumped = createRun(token, projectId, "editor 1.1.0 런")
        assertThat(onBumped["gameBuild"]["gameBuildId"].asText()).isEqualTo(bumped.id.toString())

        val library = call(token, HttpMethod.GET, "/api/projects/$projectId/test-scenario-library").body["versions"]
        assertThat(library.mapNotNull { it["gameBuild"]?.get("gameBuildId")?.asText() })
            .containsExactly(bumped.id.toString(), build.id.toString(), editor.id.toString())
    }

    /**
     * editor 버전도 TC 를 만들고 버전 이력에 든다(ARTEL-1180). 앞 버전에서 판정이 난 케이스는 editor 버전에서도
     * REGRESSION 이고, 그 뒤 build 의 앞 버전은 editor 다.
     */
    @Test
    fun `editor 버전도 TC 를 만들고 REGRESSION 과 판정 이력에 든다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val buildA = ingestedBuild(projectId, "1.0.0", Instant.now().minusSeconds(120))
        generate(token, projectId, buildA.gameBuildId)
        val versionsA = versions.findByGameBuildId(buildA.gameBuildId).toList().sortedBy { it.testCaseId }
        val verified = versionsA[0]
        val untouched = versionsA[1]
        versions.save(verified.copy(verificationStatus = "VERIFIED"))

        val editor = ingestedBuild(projectId, "1.1.0", Instant.now().minusSeconds(60), isEditor = true)
        val editorGenerated = generate(token, projectId, editor.gameBuildId)
        assertThat(editorGenerated.status).isEqualTo(200)
        assertThat(editorGenerated.body["regression"].asInt()).isEqualTo(1)
        assertThat(versions.findByTestCaseIdAndGameBuildId(verified.testCaseId, editor.gameBuildId)!!.verificationStatus)
            .isEqualTo("REGRESSION")

        val buildB = ingestedBuild(projectId, "1.1.0", Instant.now())
        val generated = generate(token, projectId, buildB.gameBuildId)

        assertThat(generated.body["regression"].asInt()).isEqualTo(1)
        assertThat(versions.findByTestCaseIdAndGameBuildId(untouched.testCaseId, buildB.gameBuildId)!!.verificationStatus)
            .isEqualTo("DRAFT")

        val history = call(token, HttpMethod.GET, "/api/projects/$projectId/test-cases/${verified.testCaseId}/verdict-history")
        assertThat(history.body["items"].map { it["gameBuildId"].asLong() to it["isEditor"].asBoolean() })
            .containsExactly(buildA.gameBuildId to false, editor.gameBuildId to true, buildB.gameBuildId to false)
    }

    /** 새 버전에서 내용이 같은 TC 는 앞 버전 판정을 이어받고, 출처를 남긴다(ARTEL-1180). */
    @Test
    fun `새 버전 생성에서 SAME_CONTENT 는 앞 버전 판정을 이어받고 다음 버전도 처음 본 build 를 출처로 든다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val buildA = ingestedBuild(projectId, "1.0.0", Instant.now().minusSeconds(120))
        generate(token, projectId, buildA.gameBuildId)
        val (passed, failed, untouched) = versions.findByGameBuildId(buildA.gameBuildId).toList().sortedBy { it.testCaseId }
        versions.save(passed.copy(verificationStatus = "VERIFIED"))
        versions.save(failed.copy(verificationStatus = "FAILED"))

        val buildB = ingestedBuild(projectId, "1.0.1", Instant.now().minusSeconds(60), isEditor = true)
        val generated = generateWith(token, projectId, buildB.gameBuildId, "verdicts=SAME_CONTENT")

        assertThat(generated.status).isEqualTo(200)
        assertThat(generated.body["regression"].asInt()).isEqualTo(2)
        assertThat(generated.body["verdictsCarried"].asInt()).isEqualTo(2)
        val inB = versions.findByGameBuildId(buildB.gameBuildId).toList().associateBy { it.testCaseId }
        assertThat(inB.getValue(passed.testCaseId).verificationStatus).isEqualTo("VERIFIED")
        assertThat(inB.getValue(failed.testCaseId).verificationStatus).isEqualTo("FAILED")
        assertThat(inB.getValue(untouched.testCaseId).verificationStatus).isEqualTo("DRAFT")
        assertThat(inB.getValue(passed.testCaseId).verdictCarriedFromBuildId).isEqualTo(buildA.gameBuildId)
        assertThat(inB.getValue(untouched.testCaseId).verdictCarriedFromBuildId).isNull()

        // 이어받은 판정을 다시 이어받아도 출처는 실제로 본 build 다.
        val buildC = ingestedBuild(projectId, "1.0.2", Instant.now())
        generateWith(token, projectId, buildC.gameBuildId, "verdicts=SAME_CONTENT")
        assertThat(versions.findByTestCaseIdAndGameBuildId(passed.testCaseId, buildC.gameBuildId)!!.verdictCarriedFromBuildId)
            .isEqualTo(buildA.gameBuildId)
        val history = call(token, HttpMethod.GET, "/api/projects/$projectId/test-cases/${passed.testCaseId}/verdict-history")
        assertThat(history.body["items"].map { it["verdictCarriedFromBuildId"].asText(null) })
            .containsExactly(null, buildA.gameBuildId.toString(), buildA.gameBuildId.toString())
    }

    /** SELECTED 는 고른 TC 만 이어받는다. 고르지 않은 TC 는 다시 검증한다(ARTEL-1180). */
    @Test
    fun `새 버전 생성에서 SELECTED 는 고른 TC 만 이어받고 ids 없이는 400 이다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val buildA = ingestedBuild(projectId, "1.0.0", Instant.now().minusSeconds(60))
        generate(token, projectId, buildA.gameBuildId)
        val (chosen, other) = versions.findByGameBuildId(buildA.gameBuildId).toList().sortedBy { it.testCaseId }
        versions.save(chosen.copy(verificationStatus = "VERIFIED"))
        versions.save(other.copy(verificationStatus = "VERIFIED"))

        val buildB = ingestedBuild(projectId, "1.0.1", Instant.now())
        assertThat(generateWith(token, projectId, buildB.gameBuildId, "verdicts=SELECTED").status).isEqualTo(400)
        val generated = generateWith(token, projectId, buildB.gameBuildId, "verdicts=SELECTED&testCaseIds=${chosen.testCaseId}")

        assertThat(generated.body["verdictsCarried"].asInt()).isEqualTo(1)
        assertThat(versions.findByTestCaseIdAndGameBuildId(chosen.testCaseId, buildB.gameBuildId)!!.verificationStatus)
            .isEqualTo("VERIFIED")
        assertThat(versions.findByTestCaseIdAndGameBuildId(other.testCaseId, buildB.gameBuildId)!!.verificationStatus)
            .isEqualTo("REGRESSION")
    }

    /** 같은 버전을 덮어써 다시 만들 때 NONE 은 남아 있던 판정까지 다시 검증한다. 고르지 않으면 판정이 남는다(ARTEL-1180). */
    @Test
    fun `덮어쓰기 생성에서 NONE 은 이 버전의 판정을 REGRESSION 으로 돌리고, 고르지 않으면 판정을 지킨다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val build = ingestedBuild(projectId, "1.0.0", Instant.now(), isEditor = true)
        generate(token, projectId, build.gameBuildId)
        val (judged, _) = versions.findByGameBuildId(build.gameBuildId).toList().sortedBy { it.testCaseId }
        versions.save(judged.copy(verificationStatus = "VERIFIED"))

        val kept = generateWith(token, projectId, build.gameBuildId, "overwrite=true")
        assertThat(kept.status).isEqualTo(200)
        assertThat(versions.findById(judged.id!!)!!.verificationStatus).isEqualTo("VERIFIED")

        val reset = generateWith(token, projectId, build.gameBuildId, "overwrite=true&verdicts=NONE")
        assertThat(reset.body["verdictsReset"].asInt()).isEqualTo(1)
        assertThat(versions.findById(judged.id!!)!!.verificationStatus).isEqualTo("REGRESSION")
    }

    /** 생성 뒤 고른 TC 만 이어받는다. 못 받는 TC 는 까닭과 함께 돌려준다(ARTEL-1180). */
    @Test
    fun `생성 뒤 고른 TC 에 앞 버전 판정을 이어받고, 못 받는 것은 까닭을 준다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val buildA = ingestedBuild(projectId, "1.0.0", Instant.now().minusSeconds(60))
        generate(token, projectId, buildA.gameBuildId)
        val (verified, judgedHere, untouched) = versions.findByGameBuildId(buildA.gameBuildId).toList().sortedBy { it.testCaseId }
        versions.save(verified.copy(verificationStatus = "VERIFIED"))
        versions.save(judgedHere.copy(verificationStatus = "FAILED"))
        val manual = testCases.save(TestCaseEntity(projectId = projectId, scene = "S", step = "사람이 쓴 것", expectedValue = "x"))
        versions.save(
            TestCaseVersionEntity.snapshotOf(manual, buildA.gameBuildId).copy(verificationStatus = "VERIFIED")
        )

        val buildB = ingestedBuild(projectId, "1.0.1", Instant.now())
        generate(token, projectId, buildB.gameBuildId)
        // 이 버전에서 직접 판정이 난 몫, 내용이 바뀐 사용자 생성 몫.
        val hereB = versions.findByTestCaseIdAndGameBuildId(judgedHere.testCaseId, buildB.gameBuildId)!!
        versions.save(hereB.copy(verificationStatus = "VERIFIED"))
        val manualB = versions.findByTestCaseIdAndGameBuildId(manual.id!!, buildB.gameBuildId)!!
        versions.save(manualB.copy(expectedValue = "y"))

        val ids = listOf(verified.testCaseId, judgedHere.testCaseId, untouched.testCaseId, manual.id!!, 999_999L)
        val reply = call(
            token, HttpMethod.POST, "/api/projects/$projectId/game-builds/${buildB.gameBuildId}/test-cases/verdict-carry-over",
            """{"testCaseIds":[${ids.joinToString(",") { "\"$it\"" }}]}""",
        )

        assertThat(reply.status).isEqualTo(200)
        assertThat(reply.body["carried"].map { it.asLong() }).containsExactly(verified.testCaseId)
        assertThat(reply.body["skipped"].associate { it["testCaseId"].asLong() to it["reason"].asText() }).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                judgedHere.testCaseId to "JUDGED_IN_THIS_VERSION",
                untouched.testCaseId to "NO_PREVIOUS_VERDICT",
                manual.id!! to "CONTENT_CHANGED",
                999_999L to "NOT_IN_VERSION",
            )
        )
        val carried = versions.findByTestCaseIdAndGameBuildId(verified.testCaseId, buildB.gameBuildId)!!
        assertThat(carried.verificationStatus).isEqualTo("VERIFIED")
        assertThat(carried.verdictCarriedFromBuildId).isEqualTo(buildA.gameBuildId)
        assertThat(testCases.findById(verified.testCaseId)!!.verificationStatus).isEqualTo("VERIFIED")

        val empty = call(
            token, HttpMethod.POST, "/api/projects/$projectId/game-builds/${buildB.gameBuildId}/test-cases/verdict-carry-over",
            """{"testCaseIds":[]}""",
        )
        assertThat(empty.status).isEqualTo(400)
    }

    /** 라이브러리 버전 메뉴: 고른 버전에서 이 버전의 모든 TC 판정을 가져온다. 바로 앞이 아닌 버전도 된다(ARTEL-1180). */
    @Test
    fun `고른 버전에서 이 버전의 모든 TC 판정을 가져온다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val buildA = ingestedBuild(projectId, "1.0.0", Instant.now().minusSeconds(120))
        generate(token, projectId, buildA.gameBuildId)
        val (passed, failed) = versions.findByGameBuildId(buildA.gameBuildId).toList().sortedBy { it.testCaseId }
        versions.save(passed.copy(verificationStatus = "VERIFIED"))
        versions.save(failed.copy(verificationStatus = "FAILED"))
        val buildB = ingestedBuild(projectId, "1.1.0", Instant.now().minusSeconds(60))
        generate(token, projectId, buildB.gameBuildId)
        // 바로 앞 버전(B)에서는 다시 검증 전이라 판정이 없다.
        val buildC = ingestedBuild(projectId, "1.2.0", Instant.now(), isEditor = true)
        generate(token, projectId, buildC.gameBuildId)
        val carryUri = "/api/projects/$projectId/game-builds/${buildC.gameBuildId}/test-cases/verdict-carry-over"

        assertThat(call(token, HttpMethod.POST, carryUri, "{}").status).isEqualTo(400)
        assertThat(call(token, HttpMethod.POST, carryUri, """{"fromGameBuildId":"${buildC.gameBuildId}"}""").status).isEqualTo(400)
        // 미리 보기(ARTEL-1181): A 에서 판정 난 두 몫만, 아무것도 바꾸지 않고 가져올 수 있는지를 준다.
        val preview = call(token, HttpMethod.GET, "$carryUri/preview?fromGameBuildId=${buildA.gameBuildId}")
        assertThat(preview.status).isEqualTo(200)
        assertThat(preview.body["items"].map { it["testCaseId"].asLong() to it["sourceStatus"].asText() })
            .containsExactly(passed.testCaseId to "VERIFIED", failed.testCaseId to "FAILED")
        assertThat(preview.body["items"].map { it["carryable"].asBoolean() }).containsOnly(true)
        assertThat(preview.body["items"][0]["targetStatus"].asText()).isEqualTo("REGRESSION")
        assertThat(versions.findByTestCaseIdAndGameBuildId(passed.testCaseId, buildC.gameBuildId)!!.verificationStatus).isEqualTo("REGRESSION")
        val reply = call(token, HttpMethod.POST, carryUri, """{"fromGameBuildId":"${buildA.gameBuildId}"}""")

        assertThat(reply.status).isEqualTo(200)
        assertThat(reply.body["carried"].map { it.asLong() }).containsExactlyInAnyOrder(passed.testCaseId, failed.testCaseId)
        assertThat(reply.body["skipped"].map { it["reason"].asText() }.toSet()).containsOnly("NO_PREVIOUS_VERDICT")
        val inC = versions.findByGameBuildId(buildC.gameBuildId).toList().associateBy { it.testCaseId }
        assertThat(inC.getValue(passed.testCaseId).verificationStatus).isEqualTo("VERIFIED")
        assertThat(inC.getValue(failed.testCaseId).verificationStatus).isEqualTo("FAILED")
        assertThat(inC.getValue(failed.testCaseId).verdictCarriedFromBuildId).isEqualTo(buildA.gameBuildId)
        // 가져온 뒤에는 같은 출처의 같은 판정이라 다시 가져올 것이 없다(ARTEL-1213). 미리 보기와 가져오기가 같은 까닭을 준다.
        val again = call(token, HttpMethod.GET, "$carryUri/preview?fromGameBuildId=${buildA.gameBuildId}").body["items"]
        assertThat(again.map { it["carryable"].asBoolean() }).containsOnly(false)
        assertThat(again.map { it["reason"].asText() }).containsOnly("ALREADY_CARRIED")
        val repeat = call(token, HttpMethod.POST, carryUri, """{"fromGameBuildId":"${buildA.gameBuildId}"}""")
        assertThat(repeat.body["carried"]).isEmpty()
        assertThat(repeat.body["skipped"].filter { it["testCaseId"].asLong() in setOf(passed.testCaseId, failed.testCaseId) }
            .map { it["reason"].asText() }).containsOnly("ALREADY_CARRIED")

        // "모두 다시 검증": 가져오지 않고 이 버전의 판정을 REGRESSION 으로 돌린다.
        val reset = call(token, HttpMethod.POST, carryUri, """{"resetAll":true}""")
        assertThat(reset.body["reset"].map { it.asLong() }).containsExactlyInAnyOrder(passed.testCaseId, failed.testCaseId)
        val afterReset = versions.findByTestCaseIdAndGameBuildId(passed.testCaseId, buildC.gameBuildId)!!
        assertThat(afterReset.verificationStatus).isEqualTo("REGRESSION")
        assertThat(afterReset.verdictCarriedFromBuildId).isNull()
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
            .containsExactly(existing.id to 0, copyId to 1)

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
        return IngestedBuild(build.id, map.id)
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
        )
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

    private fun generateWith(token: String, projectId: Long, gameBuildId: Long, query: String): Reply =
        call(token, HttpMethod.POST, "/api/projects/$projectId/game-builds/$gameBuildId/content-map/test-cases?$query")

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
