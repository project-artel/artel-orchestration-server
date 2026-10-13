package kr.artel.orchestration.testcase

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import kr.artel.orchestration.testcase.repository.TestCaseFolderRepository
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.service.ContentMapTestCaseService
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
 * build 별 content map test case 생성 API 통합 테스트(ARTEL-971).
 *
 * 실측 문서(`wv-play-2026-09-01.json`)를 build 마다 적재한다. 같은 문서라 두 build 의 content map 이 같은
 * test case 를 낸다 — 예전 `rewrite` 가 프로젝트 전체를 비교하던 때 서로를 지우던 바로 그 조건이다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContentMapTestCaseGenerationIntegrationTest {

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
    @Autowired private lateinit var folders: TestCaseFolderRepository
    @Autowired private lateinit var contentMapTestCases: ContentMapTestCaseService

    @Test
    fun `적재만으로는 test case 가 생기지 않고 생성하면 build 이름의 folder 에 들어간다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val build = ingestedBuild(projectId, version = "1.0.0", label = "알파")

        assertThat(testCases.countByProjectId(projectId)).isZero()

        val response = generate(token, projectId, build.gameBuildId)

        assertThat(response.status).isEqualTo(200)
        val body = response.body
        assertThat(body.fieldNames().asSequence().toSet())
            .containsExactlyInAnyOrder("folderId", "created", "updated", "deleted", "broken")
        assertThat(body["folderId"].isTextual).isTrue()
        assertThat(body["created"].asInt()).isPositive()
        val folder = folders.findById(body["folderId"].asText().toLong())!!
        assertThat(folder.name).isEqualTo("알파 content map")
        assertThat(folder.parentId).isNull()
        assertThat(folder.contentMapId).isEqualTo(build.contentMapId)
        val rows = rowsOf(projectId, build.contentMapId)
        assertThat(rows).hasSize(body["created"].asInt())
        assertThat(rows).allSatisfy { assertThat(it.folderId).isEqualTo(folder.id) }
    }

    @Test
    fun `두 build 의 content map 은 서로의 test case 를 지우지 않는다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val buildA = ingestedBuild(projectId, version = "1.0.0", label = "알파")
        val buildB = ingestedBuild(projectId, version = "2.0.0", label = null)

        val generatedA = generate(token, projectId, buildA.gameBuildId).body
        val generatedB = generate(token, projectId, buildB.gameBuildId).body
        val rowsB = rowsOf(projectId, buildB.contentMapId).map { it.id }

        // 같은 문서라 B 도 A 와 같은 수를 새로 만든다 — 프로젝트 전체를 비교했다면 0 이었다.
        assertThat(generatedB["created"].asInt()).isEqualTo(generatedA["created"].asInt())
        assertThat(generatedB["folderId"].asText()).isNotEqualTo(generatedA["folderId"].asText())
        assertThat(folders.findById(generatedB["folderId"].asText().toLong())!!.name).isEqualTo("2.0.0 content map")

        val againA = generate(token, projectId, buildA.gameBuildId).body
        assertThat(againA["created"].asInt()).isZero()
        assertThat(againA["deleted"].asInt()).isZero()
        assertThat(againA["broken"].asInt()).isZero()
        assertThat(againA["folderId"].asText()).isEqualTo(generatedA["folderId"].asText())
        assertThat(rowsOf(projectId, buildB.contentMapId).map { it.id }).isEqualTo(rowsB)
    }

    @Test
    fun `사용자가 옮긴 test case 의 folder 는 다시 생성해도 그대로다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val build = ingestedBuild(projectId, version = "1.0.0", label = "알파")
        generate(token, projectId, build.gameBuildId)
        val userFolderId = call(
            token, HttpMethod.POST, "/api/projects/$projectId/test-case-folders", """{"name":"내 folder"}"""
        ).body["id"].asText()
        val moved = rowsOf(projectId, build.contentMapId).first()
        call(
            token, HttpMethod.POST, "/api/projects/$projectId/test-cases/move",
            """{"caseIds":["${moved.id}"],"folderId":"$userFolderId"}""",
        )

        generate(token, projectId, build.gameBuildId)

        assertThat(testCases.findById(moved.id!!)!!.folderId).isEqualTo(userFolderId.toLong())
    }

    @Test
    fun `content map folder 를 지우면 다음 생성이 새로 만들고 기존 행은 folder 없음으로 남는다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val build = ingestedBuild(projectId, version = "1.0.0", label = "알파")
        val firstFolderId = generate(token, projectId, build.gameBuildId).body["folderId"].asText()
        call(token, HttpMethod.DELETE, "/api/projects/$projectId/test-case-folders/$firstFolderId")

        val again = generate(token, projectId, build.gameBuildId).body

        assertThat(again["folderId"].asText()).isNotEqualTo(firstFolderId)
        assertThat(again["created"].asInt()).isZero()
        assertThat(rowsOf(projectId, build.contentMapId)).allSatisfy { assertThat(it.folderId).isNull() }
    }

    @Test
    fun `최상위에 같은 이름이 있으면 번호를 붙인다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val build = ingestedBuild(projectId, version = "1.0.0", label = "알파")
        call(token, HttpMethod.POST, "/api/projects/$projectId/test-case-folders", """{"name":"알파 content map"}""")
        call(token, HttpMethod.POST, "/api/projects/$projectId/test-case-folders", """{"name":"알파 content map (2)"}""")

        val folderId = generate(token, projectId, build.gameBuildId).body["folderId"].asText()

        assertThat(folders.findById(folderId.toLong())!!.name).isEqualTo("알파 content map (3)")
    }

    @Test
    fun `같은 build 를 동시에 생성해도 test case 가 두 벌 생기지 않는다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val build = ingestedBuild(projectId, version = "1.0.0", label = "알파")

        val responses = (1..2).map {
            async(Dispatchers.IO) { generate(token, projectId, build.gameBuildId) }
        }.awaitAll()

        assertThat(responses.map { it.status }).containsOnly(200)
        val createdTotal = responses.sumOf { it.body["created"].asInt() }
        assertThat(responses.map { it.body["created"].asInt() }).contains(0)
        assertThat(rowsOf(projectId, build.contentMapId)).hasSize(createdTotal)
        assertThat(responses.map { it.body["folderId"].asText() }.distinct()).hasSize(1)
    }

    @Test
    fun `internal 재생성도 같은 folder 와 범위를 쓴다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val build = ingestedBuild(projectId, version = "1.0.0", label = "알파")
        val folderId = generate(token, projectId, build.gameBuildId).body["folderId"].asText()

        val result = contentMapTestCases.regenerate(build.contentMapId)

        assertThat(result.folderId).isEqualTo(folderId.toLong())
        assertThat(result.created).isZero()
        assertThat(contentMapTestCases.regenerate(999_999_999).folderId).isNull()
    }

    @Test
    fun `build 가 없거나 비멤버이거나 다른 프로젝트 build 면 404 다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val build = ingestedBuild(projectId, version = "1.0.0", label = "알파")
        val otherProjectId = newProject(userId)
        val (_, outsiderToken) = signIn()

        assertThat(generate(token, projectId, 999_999_999).status).isEqualTo(404)
        assertThat(generate(outsiderToken, projectId, build.gameBuildId).status).isEqualTo(404)
        assertThat(generate(token, otherProjectId, build.gameBuildId).status).isEqualTo(404)
        assertThat(testCases.countByProjectId(projectId)).isZero()
    }

    @Test
    fun `적재된 content map 이 없으면 409 다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val now = Instant.now()
        val withoutMap = gameBuilds.save(GameBuildEntity(projectId = projectId, version = "0.1.0", createdAt = now, updatedAt = now))
        val withEmptyMap = gameBuilds.save(GameBuildEntity(projectId = projectId, version = "0.2.0", createdAt = now, updatedAt = now))
        contentMaps.save(ContentMapEntity(gameBuildId = withEmptyMap.id!!))

        assertThat(generate(token, projectId, withoutMap.id!!).status).isEqualTo(409)
        assertThat(generate(token, projectId, withEmptyMap.id!!).status).isEqualTo(409)
    }

    private data class IngestedBuild(val gameBuildId: Long, val contentMapId: Long)

    private suspend fun ingestedBuild(projectId: Long, version: String, label: String?): IngestedBuild {
        val now = Instant.now()
        val build = gameBuilds.save(
            GameBuildEntity(projectId = projectId, version = version, label = label, createdAt = now, updatedAt = now)
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

    /** 이 content map 에서 나온 행. `metadata.content_map_id` 가 출처다. */
    private suspend fun rowsOf(projectId: Long, contentMapId: Long): List<TestCaseEntity> =
        testCases.findByProjectIdOrderByIdAsc(projectId).toList().filter { row ->
            objectMapper.readTree(row.metadata.asString()).path("content_map_id").asLong() == contentMapId
        }

    private suspend fun signIn(): Pair<Long, String> {
        val providerUserId = UUID.randomUUID().toString()
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = "generate-$providerUserId",
                displayName = "Generate",
                avatarUrl = null,
                email = null,
            )
        )!!
        return user.userId.toLong() to jwtService.issue(user)
    }

    private suspend fun newProject(memberId: Long): Long {
        val now = Instant.now()
        val projectId = projectRepository.save(
            ProjectEntity(name = "content-map-cases", genre = "RPG", createdAt = now, updatedAt = now)
        ).id!!
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = projectId, appUserId = memberId, role = "OWNER", createdAt = now)
        )
        return projectId
    }

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

    companion object {
        private const val DOCUMENT = "src/test/resources/contentmap/wv-play-2026-09-01.json"
    }
}
