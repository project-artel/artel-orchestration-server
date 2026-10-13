package kr.artel.orchestration.testcase

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
 * 케이스 라이브러리의 버전 보기(ARTEL-1021).
 *
 * 같은 실측 문서를 두 build 에 적재해, 같은 케이스가 두 버전에 몫을 갖게 한다. 그다음 몫 하나를 지워 한쪽에만
 * 있는 케이스를 만든다. 여기서 지키는 것은 넷이다.
 * - 버전 목록은 최신 순이고 맨 앞이 `latest` 다. 수는 그 버전 표에 보일 케이스 수다.
 * - 버전 표는 그 버전의 몫으로 내용과 판정을 낸다.
 * - 비교하면 두 판정과 존재 여부(`BOTH` · `ONLY_THIS` · `ONLY_OTHER`)를 한 줄에 싣는다.
 * - 사람이 쓴 케이스는 모든 버전에 있다고 보고, 판정이 없으면 `DRAFT` 다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TestCaseVersionLibraryIntegrationTest {

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

    @Test
    fun `버전 목록과 버전 표, 그리고 이전 버전과의 비교`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val buildA = ingestedBuild(projectId, "1.0.0", Instant.now().minusSeconds(60))
        call(token, HttpMethod.POST, "/api/projects/$projectId/game-builds/${buildA.gameBuildId}/content-map/test-cases")
        val versionsA = versions.findByGameBuildId(buildA.gameBuildId).toList().sortedBy { it.testCaseId }
        val passedThenFailed = versionsA[0]
        val onlyInOld = versionsA[1]
        versions.save(passedThenFailed.copy(verificationStatus = "VERIFIED"))
        val buildB = ingestedBuild(projectId, "1.1.0", Instant.now())
        call(token, HttpMethod.POST, "/api/projects/$projectId/game-builds/${buildB.gameBuildId}/content-map/test-cases")
        val failedOnB = versions.findByTestCaseIdAndGameBuildId(passedThenFailed.testCaseId, buildB.gameBuildId)!!
        versions.save(failedOnB.copy(verificationStatus = "FAILED"))
        versions.delete(versions.findByTestCaseIdAndGameBuildId(onlyInOld.testCaseId, buildB.gameBuildId)!!)
        val manual = testCases.save(TestCaseEntity(projectId = projectId, scene = "S", step = "사람이 쓴 것", expectedValue = "x"))
        val mapCases = versionsA.size

        val listed = call(token, HttpMethod.GET, "/api/projects/$projectId/test-case-versions")
        assertThat(listed.status).isEqualTo(200)
        val items = listed.body["items"]
        assertThat(items.map { it["version"].asText() }).containsExactly("1.1.0", "1.0.0")
        assertThat(items.map { it["latest"].asBoolean() }).containsExactly(true, false)
        // 1.1 은 몫 하나를 지웠으니 지도 케이스가 하나 적고, 사람이 쓴 케이스 하나가 더해진다.
        assertThat(items[0]["total"].asInt()).isEqualTo(mapCases - 1 + 1)
        assertThat(items[0]["counts"]["FAILED"].asInt()).isEqualTo(1)
        assertThat(items[0]["counts"]["REGRESSION"].asInt()).isZero()
        assertThat(items[0]["counts"].fieldNames().asSequence().toSet())
            .containsExactlyInAnyOrder("DRAFT", "VERIFIED", "FAILED", "REGRESSION", "BROKEN")
        assertThat(items[1]["total"].asInt()).isEqualTo(mapCases + 1)
        assertThat(items[1]["counts"]["VERIFIED"].asInt()).isEqualTo(1)

        val plain = call(token, HttpMethod.GET, "/api/projects/$projectId/test-case-versions/${buildB.gameBuildId}/cases")
        assertThat(plain.status).isEqualTo(200)
        val plainRows = plain.body["items"].associateBy { it["id"].asLong() }
        assertThat(plainRows).hasSize(mapCases - 1 + 1)
        assertThat(plainRows).doesNotContainKey(onlyInOld.testCaseId)
        assertThat(plainRows.getValue(passedThenFailed.testCaseId)["verificationStatus"].asText()).isEqualTo("FAILED")
        assertThat(plainRows.getValue(manual.id!!)["origin"].asText()).isEqualTo("MANUAL")
        assertThat(plainRows.getValue(manual.id!!)["verificationStatus"].asText()).isEqualTo("DRAFT")
        assertThat(plainRows.values.map { it["presence"].asText() }.toSet()).containsExactly("BOTH")

        val compared = call(
            token, HttpMethod.GET,
            "/api/projects/$projectId/test-case-versions/${buildB.gameBuildId}/cases?compareTo=${buildA.gameBuildId}"
        )
        val rows = compared.body["items"].associateBy { it["id"].asLong() }
        assertThat(compared.body["compareToGameBuildId"].asText()).isEqualTo(buildA.gameBuildId.toString())
        assertThat(rows).hasSize(mapCases + 1)
        val regressed = rows.getValue(passedThenFailed.testCaseId)
        assertThat(regressed["presence"].asText()).isEqualTo("BOTH")
        assertThat(regressed["verificationStatus"].asText()).isEqualTo("FAILED")
        assertThat(regressed["compareVerificationStatus"].asText()).isEqualTo("VERIFIED")
        val gone = rows.getValue(onlyInOld.testCaseId)
        assertThat(gone["presence"].asText()).isEqualTo("ONLY_OTHER")
        assertThat(gone.path("verificationStatus").isNull).isTrue()
        assertThat(gone["step"].asText()).isEqualTo(onlyInOld.step)

        // 반대로 보면 같은 케이스가 "이 버전에만 있음" 이다.
        val reversed = call(
            token, HttpMethod.GET,
            "/api/projects/$projectId/test-case-versions/${buildA.gameBuildId}/cases?compareTo=${buildB.gameBuildId}"
        ).body["items"].associateBy { it["id"].asLong() }
        assertThat(reversed.getValue(onlyInOld.testCaseId)["presence"].asText()).isEqualTo("ONLY_THIS")
    }

    @Test
    fun `다른 프로젝트의 버전은 없는 것이다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val otherProject = newProject(userId)
        val now = Instant.now()
        val foreign = gameBuilds.save(GameBuildEntity(projectId = otherProject, version = "9.9", createdAt = now, updatedAt = now))

        val reply = call(token, HttpMethod.GET, "/api/projects/$projectId/test-case-versions/${foreign.id}/cases")

        assertThat(reply.status).isEqualTo(404)
    }

    private data class IngestedBuild(val gameBuildId: Long, val contentMapId: Long)

    private suspend fun ingestedBuild(projectId: Long, version: String, createdAt: Instant): IngestedBuild {
        val build = gameBuilds.save(
            GameBuildEntity(projectId = projectId, version = version, createdAt = createdAt, updatedAt = createdAt)
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
