package kr.artel.orchestration.gameidentity

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.config.InternalApiServer
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.UUID

/**
 * 게임 값 선언의 등록·목록·삭제와 내부 목록(V173).
 *
 * 표를 비우지 않는다. 테스트마다 새 프로젝트를 만들어 자기 행만 쓴다(`DeveloperMacroIntegrationTest` 와 같은 이유).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameIdentityIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUsers: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var members: ProjectMemberRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var internalApiServer: InternalApiServer

    @Test
    fun `등록하면 저장된 행을 돌려주고 선언한 칸이 바뀔 때만 version 이 오른다`() {
        val world = newWorld()
        val path = "${world.base}/game-identities"

        val first = call(world.ownerSession, HttpMethod.PUT, path, putBody(world.buildA))
        assertThat(first.status).isEqualTo(200)
        assertThat(first.body.fieldNames().asSequence().toList()).containsExactlyInAnyOrder(
            "id", "name", "kind", "base", "meaning", "changesWhen", "identifies", "role", "gameBuildId", "version", "updatedAt",
        )
        assertThat(first.body["id"].isTextual).describedAs("id 는 다른 응답처럼 문자열이다").isTrue()
        assertThat(first.body["name"].asText()).isEqualTo(NAME)
        assertThat(first.body["kind"].asText()).isEqualTo("ordinal")
        assertThat(first.body["base"].asInt()).isEqualTo(0)
        assertThat(first.body["meaning"].asText()).isEqualTo(MEANING)
        assertThat(first.body["changesWhen"].asText()).isEqualTo(CHANGES_WHEN)
        assertThat(first.body["identifies"].asText()).isEqualTo(IDENTIFIES)
        assertThat(first.body["role"].asText()).isEqualTo("current")
        assertThat(first.body["gameBuildId"].isTextual).isTrue()
        assertThat(first.body["gameBuildId"].asText()).isEqualTo(world.buildA.toString())
        assertThat(first.body["version"].asInt()).isEqualTo(1)

        val same = call(world.ownerSession, HttpMethod.PUT, path, putBody(world.buildA))
        assertThat(same.body["id"].asText()).isEqualTo(first.body["id"].asText())
        assertThat(same.body["version"].asInt()).describedAs("같은 선언은 버전을 올리지 않는다").isEqualTo(1)

        val movedBuild = call(world.ownerSession, HttpMethod.PUT, path, putBody(world.buildB))
        assertThat(movedBuild.body["version"].asInt()).describedAs("기준 build 만 옮긴 것은 선언 변경이 아니다").isEqualTo(1)
        assertThat(movedBuild.body["gameBuildId"].asText()).isEqualTo(world.buildB.toString())

        val changed = call(world.ownerSession, HttpMethod.PUT, path, putBody(world.buildB, base = "1"))
        assertThat(changed.body["version"].asInt()).isEqualTo(2)
        assertThat(changed.body["base"].asInt()).isEqualTo(1)

        val baseDropped = call(world.ownerSession, HttpMethod.PUT, path, putBody(world.buildB, base = null))
        assertThat(baseDropped.body["base"].isNull).isTrue()
        assertThat(baseDropped.body["version"].asInt()).describedAs("base 를 비운 것도 선언 변경이다").isEqualTo(3)

        val roleChanged = call(world.ownerSession, HttpMethod.PUT, path, putBody(world.buildB, base = null, role = "progress"))
        assertThat(roleChanged.body["role"].asText()).isEqualTo("progress")
        assertThat(roleChanged.body["version"].asInt()).describedAs("role 도 선언한 칸이다").isEqualTo(4)

        val identifiesChanged = call(
            world.ownerSession, HttpMethod.PUT, path,
            putBody(world.buildB, base = null, role = "progress", identifies = "map chapter"),
        )
        assertThat(identifiesChanged.body["identifies"].asText()).isEqualTo("map chapter")
        assertThat(identifiesChanged.body["version"].asInt()).describedAs("identifies 도 선언한 칸이다").isEqualTo(5)

        call(world.ownerSession, HttpMethod.PUT, path, putBody(world.buildB, name = "MapMove.StagePosition"))
        val list = call(world.ownerSession, HttpMethod.GET, path)
        assertThat(list.status).isEqualTo(200)
        assertThat(list.body["identities"].map { it["name"].asText() })
            .describedAs("이름 오름차순")
            .containsExactly("MapMove.StagePosition", NAME)
        assertThat(list.body["identities"][1]["version"].asInt()).isEqualTo(5)
    }

    @Test
    fun `이름·kind·base·identifies·role·문장 칸이 규칙에 맞지 않으면 400 이고 아무것도 쓰지 않는다`() {
        val world = newWorld()
        val path = "${world.base}/game-identities"

        val rejected = listOf(
            "name" to putBody(world.buildA, name = "stagePosition"),
            "name" to putBody(world.buildA, name = "Stage.1st"),
            "name" to putBody(world.buildA, name = "Stage Data.position"),
            "kind" to putBody(world.buildA, kind = "number"),
            "kind" to putBody(world.buildA, kind = null),
            "base" to putBody(world.buildA, base = "2"),
            "base" to putBody(world.buildA, base = "-1"),
            "meaning" to putBody(world.buildA, meaning = " "),
            "meaning" to putBody(world.buildA, meaning = "가".repeat(501)),
            "changesWhen" to putBody(world.buildA, changesWhen = null),
            "identifies" to putBody(world.buildA, identifies = null),
            "identifies" to putBody(world.buildA, identifies = "Stage"),
            "identifies" to putBody(world.buildA, identifies = "map  chapter"),
            "identifies" to putBody(world.buildA, identifies = "x".repeat(61)),
            "role" to putBody(world.buildA, role = null),
            "role" to putBody(world.buildA, role = "selected"),
            "gameBuildId" to putBody(world.buildA, gameBuildIdJson = "\"abc\""),
        )
        for ((field, body) in rejected) {
            val answer = call(world.ownerSession, HttpMethod.PUT, path, body)
            assertThat(answer.status).describedAs(body).isEqualTo(400)
            assertThat(answer.body["fields"].has(field)).describedAs(body).isTrue()
        }

        assertThat(call(world.ownerSession, HttpMethod.GET, path).body["identities"].size()).isZero()
    }

    @Test
    fun `멤버가 아니면 프로젝트가 없는 것처럼 404 이고, 다른 프로젝트의 build 는 404 다`() {
        val world = newWorld()
        val outsider = newWorld()
        val path = "${world.base}/game-identities"
        call(world.ownerSession, HttpMethod.PUT, path, putBody(world.buildA))

        val requests = listOf(
            Triple(HttpMethod.GET, path, null),
            Triple(HttpMethod.PUT, path, putBody(world.buildA)),
            Triple(HttpMethod.DELETE, "$path?name=$NAME", null),
        )
        for ((method, requestPath, body) in requests) {
            assertThat(call(outsider.ownerSession, method, requestPath, body).status)
                .describedAs("$method $requestPath").isEqualTo(404)
        }

        val foreignBuild = call(world.ownerSession, HttpMethod.PUT, path, putBody(outsider.buildA, name = "MapMove.StagePosition"))
        assertThat(foreignBuild.status).isEqualTo(404)
        assertThat(call(world.ownerSession, HttpMethod.GET, path).body["identities"].map { it["name"].asText() })
            .containsExactly(NAME)
    }

    @Test
    fun `삭제는 204, 없는 이름은 404 다`() {
        val world = newWorld()
        val path = "${world.base}/game-identities"
        call(world.ownerSession, HttpMethod.PUT, path, putBody(world.buildA))

        assertThat(call(world.ownerSession, HttpMethod.DELETE, "$path?name=$NAME").status).isEqualTo(204)
        assertThat(call(world.ownerSession, HttpMethod.DELETE, "$path?name=$NAME").status).isEqualTo(404)
        assertThat(call(world.ownerSession, HttpMethod.GET, path).body["identities"].size()).isZero()
    }

    @Test
    fun `내부 목록은 내부 포트에서 엔진이 읽는 칸을 싣고 공개 포트에는 없다`() {
        val world = newWorld()
        call(world.ownerSession, HttpMethod.PUT, "${world.base}/game-identities", putBody(world.buildA))
        val internalPath = "/internal/projects/${world.projectId}/game-identities"

        val internal = objectMapper.readTree(
            WebClient.create("http://localhost:${internalApiServer.port}").get().uri(internalPath)
                .retrieve().bodyToMono(String::class.java).block()
        )
        val item = internal["identities"].single()
        assertThat(item.fieldNames().asSequence().toList()).containsExactlyInAnyOrder(
            "name", "kind", "base", "meaning", "changesWhen", "identifies", "role", "gameBuildId", "version",
        )
        assertThat(item["name"].asText()).isEqualTo(NAME)
        assertThat(item["kind"].asText()).isEqualTo("ordinal")
        assertThat(item["base"].asInt()).isEqualTo(0)
        assertThat(item["identifies"].asText()).isEqualTo(IDENTIFIES)
        assertThat(item["role"].asText()).isEqualTo("current")
        assertThat(item["gameBuildId"].asText()).isEqualTo(world.buildA.toString())
        assertThat(item["version"].asInt()).isEqualTo(1)

        val missing = WebClient.create("http://localhost:${internalApiServer.port}").get()
            .uri("/internal/projects/${Long.MAX_VALUE}/game-identities")
            .exchangeToMono { Mono.just(it.statusCode().value()) }.block()
        assertThat(missing).isEqualTo(404)

        assertThat(call(world.ownerSession, HttpMethod.GET, internalPath).status)
            .describedAs("공개 포트에는 `/internal` 이 없다").isEqualTo(404)
    }

    // ---------- 픽스처 ----------

    private data class World(
        val ownerSession: String,
        val projectId: Long,
        val base: String,
        val buildA: Long,
        val buildB: Long,
    )

    private fun newWorld(): World = runBlocking {
        val now = Instant.now()
        val seed = UUID.randomUUID().toString().take(8)
        val owner = signIn(seed)
        val project = projects.save(
            ProjectEntity(name = "game-identity-$seed", genre = "ACTION", createdAt = now, updatedAt = now)
        )
        members.save(
            ProjectMemberEntity(projectId = project.id!!, appUserId = owner.userId.toLong(), role = "OWNER", createdAt = now)
        )
        val buildA = gameBuilds.save(
            GameBuildEntity(projectId = project.id, version = "1.0.$seed", createdAt = now, updatedAt = now)
        )
        val buildB = gameBuilds.save(
            GameBuildEntity(projectId = project.id, version = "1.1.$seed", createdAt = now, updatedAt = now)
        )
        World(
            ownerSession = jwtService.issue(owner),
            projectId = project.id,
            base = "/api/projects/${project.id}",
            buildA = buildA.id!!,
            buildB = buildB.id!!,
        )
    }

    private suspend fun signIn(seed: String): AuthenticatedUser = oauthUsers.upsert(
        OAuthIdentity(
            provider = "github",
            providerUserId = "gameidentity-$seed",
            login = "gi-$seed",
            displayName = "gi-$seed",
            avatarUrl = null,
            email = "gi-$seed@example.com",
        )
    )

    private data class Answer(val status: Int, val body: JsonNode)

    private fun call(session: String, method: HttpMethod, path: String, body: String? = null): Answer {
        val request = client().method(method).uri(path).cookie(COOKIE, session)
        val withBody = if (body == null) request else request.contentType(MediaType.APPLICATION_JSON).bodyValue(body)
        return withBody.exchangeToMono { response ->
            response.bodyToMono(String::class.java).defaultIfEmpty("null")
                .map { Answer(response.statusCode().value(), objectMapper.readTree(it)) }
        }.block()!!
    }

    private fun client() = WebClient.create("http://localhost:$port")

    /**
     * 기본값은 [NAME] 의 정상 선언이다. 문자열 칸에 null 을 주면 그 칸을 JSON null 로 보낸다. [base] 와
     * [gameBuildIdJson] 은 JSON 조각 그대로 싣는다 — 숫자가 아닌 값을 보내는 검사가 쓴다.
     */
    private fun putBody(
        gameBuildId: Long,
        name: String = NAME,
        kind: String? = "ordinal",
        base: String? = "0",
        meaning: String? = MEANING,
        changesWhen: String? = CHANGES_WHEN,
        identifies: String? = IDENTIFIES,
        role: String? = "current",
        gameBuildIdJson: String = "\"$gameBuildId\"",
    ): String =
        """{"name":${json(name)},"gameBuildId":$gameBuildIdJson,"kind":${json(kind)},"base":${base ?: "null"},""" +
            """"meaning":${json(meaning)},"changesWhen":${json(changesWhen)},""" +
            """"identifies":${json(identifies)},"role":${json(role)}}"""

    private fun json(value: String?) = objectMapper.writeValueAsString(value)
}

private const val COOKIE = "artel_access_token"

private const val NAME = "StageDataSingleton.stagePosition"
private const val MEANING = "방금 들어간 stage 의 번호"
private const val CHANGES_WHEN = "stage 에 들어갈 때"
private const val IDENTIFIES = "stage"
