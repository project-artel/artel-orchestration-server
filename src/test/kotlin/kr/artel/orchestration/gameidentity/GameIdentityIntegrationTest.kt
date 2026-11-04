package kr.artel.orchestration.gameidentity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.config.InternalApiServer
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.gameidentity.dto.*
import kr.artel.orchestration.gameidentity.service.GameIdentityService
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameIdentityIntegrationTest {
    @LocalServerPort private val port: Int = 0
    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUsers: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var members: ProjectMemberRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var instances: GameInstanceRepository
    @Autowired private lateinit var identities: GameIdentityService
    @Autowired private lateinit var internalApiServer: InternalApiServer

    @Test
    fun `build별 revision과 tombstone을 보존하고 stale version은 409다`() {
        val world = newWorld()
        val first = put(world, world.buildA)
        assertThat(first.status).isEqualTo(200)
        assertThat(first.body["source"].asText()).isEqualTo("WEB")
        assertThat(first.body["revisionId"].isTextual).isTrue()
        assertThat(first.body["createdBy"].asText()).isEqualTo(world.userId.toString())
        assertThat(first.body["deleted"].asBoolean()).isFalse()
        assertThat(put(world, world.buildA, 1).body).isEqualTo(first.body)
        assertThat(put(world, world.buildB, meaning = "다른 build").body["version"].asInt()).isEqualTo(2)
        assertThat(list(world, world.buildA).single()).isEqualTo(first.body)
        assertThat(put(world, world.buildA).status).isEqualTo(409)
        assertThat(put(world, world.buildA, 1, "수정").body["version"].asInt()).isEqualTo(3)
        assertThat(delete(world, world.buildA, 3).status).isEqualTo(204)
        assertThat(list(world, world.buildA).size()).isZero()
        val tombstone = list(world, world.buildA, true).single()
        assertThat(tombstone["deleted"].asBoolean()).isTrue()
        assertThat(tombstone["version"].asInt()).isEqualTo(4)
        put(world, world.buildB, 2)
        assertThat(put(world, world.buildA, 4).body["version"].asInt()).isEqualTo(6)
        val history = call(world, HttpMethod.GET, "/history?name=$NAME").body["identities"]
        assertThat(history.size()).isEqualTo(6)
        assertThat(history.last()).isEqualTo(first.body)
        assertThat(history[2]).isEqualTo(tombstone)
    }

    @Test
    fun `SDK 재전송은 revision을 늘리지 않고 수동 override와 다른 build를 보존한다`(): Unit = runBlocking {
        val world = newWorld()
        val now = Instant.now()
        val instance = instances.save(GameInstanceEntity(projectId = world.projectId, name = "identity-sdk",
            platform = "UNITY", lastGameBuildId = world.buildA, createdAt = now, updatedAt = now))
        val instanceId = requireNotNull(instance.id)
        val declaration = SdkGameIdentityDeclaration(NAME, "ordinal", 0, MEANING, "stage 진입", "stage", "current")
        val catalog = SdkGameIdentityCatalog("GAME_IDENTITY_CATALOG", listOf(declaration))
        identities.recordSdkCatalog(instanceId, catalog)
        identities.recordSdkCatalog(instanceId, catalog)
        assertThat(call(world, HttpMethod.GET, "/history?name=$NAME").body["identities"].size()).isEqualTo(1)
        val first = list(world, world.buildA).single()
        assertThat(first["source"].asText()).isEqualTo("SDK")
        assertThat(first["createdBy"].isNull).isTrue()
        val invalid = catalog.copy(identities = listOf(declaration.copy(name = "invalid")))
        assertThatThrownBy { runBlocking { identities.recordSdkCatalog(instanceId, invalid) } }
            .isInstanceOf(kr.artel.orchestration.common.error.BadRequestException::class.java)
        val duplicate = catalog.copy(identities = listOf(declaration, declaration))
        assertThatThrownBy { runBlocking { identities.recordSdkCatalog(instanceId, duplicate) } }
            .isInstanceOf(kr.artel.orchestration.common.error.BadRequestException::class.java)
        assertThat(list(world, world.buildA).single()).isEqualTo(first)
        identities.recordSdkCatalog(instanceId, catalog.copy(identities = emptyList()))
        assertThat(list(world, world.buildA).size()).isZero()
        identities.recordSdkCatalog(instanceId, catalog)
        assertThat(put(world, world.buildA, 3, "웹 수정").status).isEqualTo(200)
        identities.recordSdkCatalog(instanceId, catalog)
        identities.recordSdkCatalog(instanceId, catalog.copy(identities = emptyList()))
        val overridden = list(world, world.buildA).single()
        assertThat(overridden["meaning"].asText()).isEqualTo("웹 수정")
        assertThat(overridden["source"].asText()).isEqualTo("WEB")
        assertThat(list(world, world.buildB).size()).isZero()
        val manual = objectMapper.readValue(body(world.buildA, 4), PutGameIdentityRequest::class.java)
        val mcp = identities.put(world.userId, world.projectId, manual, "MCP")
        assertThat(mcp.version).isEqualTo(5)
        identities.recordSdkCatalog(instanceId, catalog)
        assertThat(list(world, world.buildA).single()["source"].asText()).isEqualTo("MCP")
        identities.delete(world.userId, world.projectId, NAME, world.buildA, 5, "MCP")
        identities.recordSdkCatalog(instanceId, catalog)
        assertThat(list(world, world.buildA).size()).isZero()
        assertThat(list(world, world.buildA, true).single()["source"].asText()).isEqualTo("MCP")
    }

    @Test
    fun `같은 expectedVersion 동시 요청은 하나만 반영된다`(): Unit = runBlocking {
        val world = newWorld()
        val request = objectMapper.readValue(body(world.buildA), PutGameIdentityRequest::class.java)
        val results = coroutineScope {
            listOf(async { runCatching { identities.put(world.userId, world.projectId, request) } },
                async { runCatching { identities.put(world.userId, world.projectId, request) } }).map { it.await() }
        }
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.single { it.isFailure }.exceptionOrNull())
            .isInstanceOf(kr.artel.orchestration.common.error.ConflictException::class.java)
    }

    @Test
    fun `멤버와 build 소속을 확인하고 내부 목록도 build별로 읽는다`() {
        val world = newWorld()
        val outsider = newWorld()
        put(world, world.buildA)
        for ((method, suffix, payload) in listOf(
            Triple(HttpMethod.GET, "?gameBuildId=${world.buildA}", null),
            Triple(HttpMethod.GET, "/history?name=$NAME", null),
            Triple(HttpMethod.PUT, "", body(world.buildA)),
            Triple(HttpMethod.DELETE, "?name=$NAME&gameBuildId=${world.buildA}&expectedVersion=1", null))) {
            assertThat(call(world.copy(session = outsider.session), method, suffix, payload).status).isEqualTo(404)
        }
        assertThat(put(world, outsider.buildA).status).isEqualTo(404)
        assertThat(call(world, HttpMethod.GET, "?gameBuildId=${outsider.buildA}").status).isEqualTo(404)
        val internal = WebClient.create("http://localhost:${internalApiServer.port}").get()
            .uri("/internal/projects/${world.projectId}/game-identities?gameBuildId=${world.buildA}")
            .retrieve().bodyToMono(String::class.java).block()!!
        val snapshot = objectMapper.readTree(internal)["identities"].single()
        assertThat(snapshot["revisionId"]).isEqualTo(list(world, world.buildA).single()["revisionId"])
        assertThat(snapshot["source"].asText()).isEqualTo("WEB")
        val noBuild = WebClient.create("http://localhost:${internalApiServer.port}").get()
            .uri("/internal/projects/${world.projectId}/game-identities")
            .retrieve().bodyToMono(String::class.java).block()!!
        assertThat(objectMapper.readTree(noBuild)["identities"].size()).isZero()
    }

    @Test
    fun `잘못된 선언과 expectedVersion 누락은 400이고 source를 위조할 수 없다`() {
        val world = newWorld()
        for (payload in listOf(body(world.buildA).replace(NAME, "invalid"),
            body(world.buildA).replace("\"base\":0", "\"base\":2"),
            body(world.buildA).replace("\"identifies\":\"stage\"", "\"identifies\":\"Stage\""),
            body(world.buildA).replace("\"role\":\"current\"", "\"role\":\"unknown\""),
            body(world.buildA).replace("\"meaning\":\"$MEANING\"", "\"meaning\":\" \""),
            body(world.buildA).replace("ordinal", "number"),
            body(world.buildA).replace("\"expectedVersion\":0,", ""))) {
            assertThat(call(world, HttpMethod.PUT, "", payload).status).isEqualTo(400)
        }
        val spoofed = body(world.buildA).replace("\"kind\":", "\"source\":\"SDK\",\"kind\":")
        val answer = call(world, HttpMethod.PUT, "", spoofed)
        assertThat(answer.status).isEqualTo(200)
        assertThat(answer.body["source"].asText()).isEqualTo("WEB")
        assertThat(call(world, HttpMethod.DELETE, "?name=$NAME").status).isEqualTo(400)
    }

    private data class World(val session: String, val userId: Long, val projectId: Long, val buildA: Long, val buildB: Long)
    private fun newWorld(): World = runBlocking {
        val now = Instant.now()
        val seed = UUID.randomUUID().toString()
        val owner = oauthUsers.upsert(OAuthIdentity(provider = "github", providerUserId = "identity-$seed",
            login = "gi-$seed", displayName = "gi", avatarUrl = null, email = "gi-$seed@example.com"))
        val project = projects.save(ProjectEntity(name = "identity-$seed", genre = "ACTION", createdAt = now, updatedAt = now))
        val projectId = requireNotNull(project.id)
        members.save(ProjectMemberEntity(projectId = projectId, appUserId = owner.userId.toLong(), role = "OWNER", createdAt = now))
        val buildA = gameBuilds.save(GameBuildEntity(projectId = projectId, version = "1.0", createdAt = now, updatedAt = now))
        val buildB = gameBuilds.save(GameBuildEntity(projectId = projectId, version = "2.0", createdAt = now, updatedAt = now))
        World(jwtService.issue(owner), owner.userId.toLong(), projectId, buildA.id!!, buildB.id!!)
    }
    private data class Answer(val status: Int, val body: JsonNode)
    private fun call(world: World, method: HttpMethod, suffix: String = "", payload: String? = null): Answer {
        val request = WebClient.create("http://localhost:$port").method(method)
            .uri("/api/projects/${world.projectId}/game-identities$suffix").cookie("artel_access_token", world.session)
        val withBody = if (payload == null) request else request.contentType(MediaType.APPLICATION_JSON).bodyValue(payload)
        return withBody.exchangeToMono { response -> response.bodyToMono(String::class.java).defaultIfEmpty("null")
            .map { Answer(response.statusCode().value(), objectMapper.readTree(it)) } }.block()!!
    }
    private fun put(world: World, buildId: Long, expectedVersion: Int = 0, meaning: String = MEANING) =
        call(world, HttpMethod.PUT, "", body(buildId, expectedVersion, meaning))
    private fun delete(world: World, buildId: Long, expectedVersion: Int) =
        call(world, HttpMethod.DELETE, "?name=$NAME&gameBuildId=$buildId&expectedVersion=$expectedVersion")
    private fun list(world: World, buildId: Long, includeDeleted: Boolean = false) =
        call(world, HttpMethod.GET, "?gameBuildId=$buildId&includeDeleted=$includeDeleted").body["identities"]
    private fun body(buildId: Long, expectedVersion: Int = 0, meaning: String = MEANING) =
        """{"name":"$NAME","gameBuildId":"$buildId","expectedVersion":$expectedVersion,"kind":"ordinal","base":0,"meaning":"$meaning","changesWhen":"stage 진입","identifies":"stage","role":"current"}"""
}
private const val NAME = "StageDataSingleton.stagePosition"
private const val MEANING = "방금 들어간 stage 번호"
