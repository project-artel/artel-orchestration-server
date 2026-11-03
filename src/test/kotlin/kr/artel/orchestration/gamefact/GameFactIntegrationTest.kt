package kr.artel.orchestration.gamefact

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
import java.time.Instant
import java.util.UUID

/**
 * fact·rule 의 등록·목록·삭제와 내부 목록(V174).
 *
 * 표를 비우지 않는다. 테스트마다 새 프로젝트를 만들어 자기 행만 쓴다(`GameIdentityIntegrationTest` 와 같은 이유).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameFactIntegrationTest {

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
    fun `fact 를 등록하면 저장된 행을 돌려주고 선언한 칸이 바뀔 때만 version 이 오른다`() {
        val world = newWorld()
        val path = "${world.base}/game-facts"

        val first = call(world.session, HttpMethod.PUT, path, factBody(world.build))
        assertThat(first.status).isEqualTo(200)
        assertThat(first.body.fieldNames().asSequence().toList()).containsExactlyInAnyOrder(
            "id", "name", "scope", "member", "type", "unit", "means", "gameBuildId", "version", "updatedAt",
        )
        assertThat(first.body["name"].asText()).isEqualTo(FACT)
        assertThat(first.body["member"].asText()).isEqualTo(MEMBER)
        assertThat(first.body["unit"].asText()).isEqualTo("cards")
        assertThat(first.body["version"].asInt()).isEqualTo(1)

        val same = call(world.session, HttpMethod.PUT, path, factBody(world.build))
        assertThat(same.body["version"].asInt()).describedAs("같은 선언은 버전을 올리지 않는다").isEqualTo(1)

        val changed = call(world.session, HttpMethod.PUT, path, factBody(world.build, means = "손에 든 카드 수(조합창 포함)"))
        assertThat(changed.body["version"].asInt()).isEqualTo(2)
    }

    @Test
    fun `이름·scope·member·type 이 규칙에 맞지 않으면 400 이고 아무것도 쓰지 않는다`() {
        val world = newWorld()
        val path = "${world.base}/game-facts"

        assertThat(call(world.session, HttpMethod.PUT, path, factBody(world.build, name = "CardsInHand")).status).isEqualTo(400)
        assertThat(call(world.session, HttpMethod.PUT, path, factBody(world.build, scope = "scene")).status).isEqualTo(400)
        assertThat(call(world.session, HttpMethod.PUT, path, factBody(world.build, member = "myCards")).status).isEqualTo(400)
        assertThat(call(world.session, HttpMethod.PUT, path, factBody(world.build, type = "list")).status).isEqualTo(400)
        assertThat(call(world.session, HttpMethod.PUT, path, factBody(world.build, means = "x".repeat(121))).status).isEqualTo(400)

        assertThat(call(world.session, HttpMethod.GET, path).body["facts"].size()).isEqualTo(0)
    }

    @Test
    fun `rule 은 프로젝트에 있는 fact 만 가리킬 수 있고 목록은 fact 와 rule 을 함께 싣는다`() {
        val world = newWorld()
        call(world.session, HttpMethod.PUT, "${world.base}/game-facts", factBody(world.build))

        val unknown = call(world.session, HttpMethod.PUT, "${world.base}/game-rules", ruleBody(world.build, facts = listOf(FACT, "word_hp")))
        assertThat(unknown.status).isEqualTo(400)
        assertThat(unknown.body["code"].asText()).isEqualTo("game_rule_unknown_fact")
        assertThat(unknown.body["message"].asText()).contains("word_hp").doesNotContain(FACT)

        val saved = call(world.session, HttpMethod.PUT, "${world.base}/game-rules", ruleBody(world.build))
        assertThat(saved.status).isEqualTo(200)
        assertThat(saved.body["facts"].map { it.asText() }).containsExactly(FACT)

        val listed = call(world.session, HttpMethod.GET, "${world.base}/game-facts")
        assertThat(listed.body["facts"].map { it["name"].asText() }).containsExactly(FACT)
        assertThat(listed.body["rules"].map { it["name"].asText() }).containsExactly(RULE)
    }

    @Test
    fun `rule 이 쓰는 fact 는 지울 수 없고, rule 을 지운 뒤에는 지워진다`() {
        val world = newWorld()
        call(world.session, HttpMethod.PUT, "${world.base}/game-facts", factBody(world.build))
        call(world.session, HttpMethod.PUT, "${world.base}/game-rules", ruleBody(world.build))

        val inUse = call(world.session, HttpMethod.DELETE, "${world.base}/game-facts?name=$FACT")
        assertThat(inUse.status).isEqualTo(409)
        assertThat(inUse.body["code"].asText()).isEqualTo("game_fact_in_use")
        assertThat(inUse.body["message"].asText()).contains(RULE)

        assertThat(call(world.session, HttpMethod.DELETE, "${world.base}/game-rules?name=$RULE").status).isEqualTo(204)
        assertThat(call(world.session, HttpMethod.DELETE, "${world.base}/game-facts?name=$FACT").status).isEqualTo(204)
        assertThat(call(world.session, HttpMethod.DELETE, "${world.base}/game-facts?name=$FACT").status).isEqualTo(404)
        assertThat(call(world.session, HttpMethod.DELETE, "${world.base}/game-rules?name=$RULE").status).isEqualTo(404)
    }

    @Test
    fun `멤버가 아니면 프로젝트가 없는 것처럼 404 다`() {
        val world = newWorld()
        val outsider = newWorld()

        assertThat(call(outsider.session, HttpMethod.GET, "${world.base}/game-facts").status).isEqualTo(404)
        assertThat(call(outsider.session, HttpMethod.PUT, "${world.base}/game-facts", factBody(world.build)).status).isEqualTo(404)
    }

    @Test
    fun `내부 목록은 내부 포트에서 엔진이 읽는 칸을 싣는다`() {
        val world = newWorld()
        call(world.session, HttpMethod.PUT, "${world.base}/game-facts", factBody(world.build))
        call(world.session, HttpMethod.PUT, "${world.base}/game-rules", ruleBody(world.build))

        val body = WebClient.create("http://localhost:${internalApiServer.port}").get()
            .uri("/internal/projects/${world.projectId}/game-facts")
            .retrieve().bodyToMono(String::class.java).block()!!
        val node = objectMapper.readTree(body)
        assertThat(node["facts"][0].fieldNames().asSequence().toList()).containsExactlyInAnyOrder(
            "name", "scope", "member", "type", "unit", "means", "version",
        )
        assertThat(node["facts"][0]["member"].asText()).isEqualTo(MEMBER)
        assertThat(node["rules"][0].fieldNames().asSequence().toList()).containsExactlyInAnyOrder("name", "says", "facts", "version")
        assertThat(node["rules"][0]["facts"].map { it.asText() }).containsExactly(FACT)
    }

    private data class World(val session: String, val projectId: Long, val base: String, val build: Long)

    private fun newWorld(): World = runBlocking {
        val now = Instant.now()
        val seed = UUID.randomUUID().toString().take(8)
        val owner = signIn(seed)
        val project = projects.save(ProjectEntity(name = "game-fact-$seed", genre = "ACTION", createdAt = now, updatedAt = now))
        members.save(
            ProjectMemberEntity(projectId = project.id!!, appUserId = owner.userId.toLong(), role = "OWNER", createdAt = now)
        )
        val build = gameBuilds.save(GameBuildEntity(projectId = project.id, version = "1.0.$seed", createdAt = now, updatedAt = now))
        World(jwtService.issue(owner), project.id, "/api/projects/${project.id}", build.id!!)
    }

    private suspend fun signIn(seed: String): AuthenticatedUser = oauthUsers.upsert(
        OAuthIdentity(
            provider = "github",
            providerUserId = "gamefact-$seed",
            login = "gf-$seed",
            displayName = "gf-$seed",
            avatarUrl = null,
            email = "gf-$seed@example.com",
        )
    )

    private data class Answer(val status: Int, val body: JsonNode)

    private fun call(session: String, method: HttpMethod, path: String, body: String? = null): Answer {
        val request = WebClient.create("http://localhost:$port").method(method).uri(path).cookie(COOKIE, session)
        val withBody = if (body == null) request else request.contentType(MediaType.APPLICATION_JSON).bodyValue(body)
        return withBody.exchangeToMono { response ->
            response.bodyToMono(String::class.java).defaultIfEmpty("null")
                .map { Answer(response.statusCode().value(), objectMapper.readTree(it)) }
        }.block()!!
    }

    private fun factBody(
        build: Long,
        name: String = FACT,
        scope: String = "objects",
        member: String = MEMBER,
        type: String = "integer",
        means: String = "손에 든 카드 수",
    ): String = objectMapper.writeValueAsString(
        mapOf("name" to name, "gameBuildId" to build.toString(), "scope" to scope, "member" to member,
              "type" to type, "unit" to "cards", "means" to means)
    )

    private fun ruleBody(build: Long, facts: List<String> = listOf(FACT)): String = objectMapper.writeValueAsString(
        mapOf("name" to RULE, "gameBuildId" to build.toString(), "says" to "턴이 시작되면 카드를 두 장 받는다.", "facts" to facts)
    )
}

private const val COOKIE = "artel_access_token"
private const val FACT = "cards_in_hand"
private const val MEMBER = "CardManager.myCards.Count"
private const val RULE = "turn_start_draws_two_cards"
