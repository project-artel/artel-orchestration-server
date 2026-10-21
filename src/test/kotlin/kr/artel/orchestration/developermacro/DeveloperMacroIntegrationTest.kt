package kr.artel.orchestration.developermacro

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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * developer macro 의 조회·검사·등록·삭제와 내부 목록(ARTEL-1088).
 *
 * agent-server 는 이 클래스가 띄운 가짜 서버다. 원문에 `REJECT` 가 있으면 2번째 줄을 거절하고, 아니면 원문을 담은
 * 정의를 돌려준다 — 원문이 바뀌면 정의도 바뀌어 `version` 이 오르는지 볼 수 있다. 받은 검사 요청은
 * [checkRequests] 에 쌓여 `imports` 를 무엇으로 넘겼는지 본다.
 *
 * 표를 비우지 않는다. 테스트마다 새 프로젝트를 만들어 자기 행만 쓴다(`ProjectMacroHttpTest` 와 같은 이유).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeveloperMacroIntegrationTest {

    companion object {
        private val mapper = ObjectMapper()

        /** 가짜 agent-server 가 받은 `POST /internal/macros/check` 본문. */
        private val checkRequests = ConcurrentLinkedQueue<JsonNode>()

        private val fakeAgentServer: DisposableServer by lazy {
            HttpServer.create().port(0).route { routes ->
                routes.get("/internal/macros/grammar") { _, response ->
                    response.header("Content-Type", "application/json")
                        .sendString(Mono.just(GRAMMAR)).then()
                }
                routes.post("/internal/macros/check") { request, response ->
                    request.receive().aggregate().asString().flatMap { body ->
                        val json = mapper.readTree(body)
                        checkRequests.add(json)
                        response.header("Content-Type", "application/json")
                            .sendString(Mono.just(checkAnswer(json))).then()
                    }
                }
            }.bindNow()
        }

        private fun checkAnswer(request: JsonNode): String {
            val name = request["name"].asText()
            val source = request["source"].asText()
            if ("REJECT" in source) {
                return """{"ok":false,"rejection":{"line":2,"reason":"`REJECT` is not a macro call"}}"""
            }
            val answer = mapper.createObjectNode().put("ok", true).put("docstring", "$name 의 설명")
            answer.putObject("definition").put("name", name).put("source", source)
            answer.set<JsonNode>("parameters", mapper.readTree("""[{"name":"card","type":"string"},{"name":"count","type":"int"}]"""))
            return mapper.writeValueAsString(answer)
        }

        @JvmStatic
        @DynamicPropertySource
        fun settings(registry: DynamicPropertyRegistry) {
            // 배포 설정처럼 base-url 이 `/internal` 까지 포함한다.
            registry.add("artel.agent.base-url") { "http://localhost:${fakeAgentServer.port()}/internal" }
        }
    }

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUsers: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var members: ProjectMemberRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var internalApiServer: InternalApiServer

    @BeforeEach
    fun reset() {
        checkRequests.clear()
    }

    @Test
    fun `grammar 는 agent-server 의 본문과 content type 을 그대로 옮긴다`() {
        val world = newWorld()

        val response = client().get().uri("/api/macros/grammar").cookie(COOKIE, world.ownerSession)
            .retrieve().toEntity(String::class.java).block()!!

        assertThat(response.body).isEqualTo(GRAMMAR)
        assertThat(response.headers.contentType!!.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue()
    }

    @Test
    fun `등록하면 저장된 행을 돌려주고 version 은 정의가 바뀔 때만 오른다`() {
        val world = newWorld()

        val first = call(world.ownerSession, HttpMethod.PUT, "${world.base}/developer-macros/open_shop", putBody(SOURCE, world.buildA))
        assertThat(first.status).isEqualTo(200)
        assertThat(first.body["name"].asText()).isEqualTo("open_shop")
        assertThat(first.body["source"].asText()).isEqualTo(SOURCE)
        assertThat(first.body["parameters"].map { it.asText() }).containsExactly("card", "count")
        assertThat(first.body["docstring"].asText()).isEqualTo("open_shop 의 설명")
        assertThat(first.body["gameBuildId"].isTextual).describedAs("id 는 GameBuildResponse.id 처럼 문자열이다").isTrue()
        assertThat(first.body["gameBuildId"].asText()).isEqualTo(world.buildA.toString())
        assertThat(first.body["buildVersion"].asText()).isEqualTo(world.buildAVersion)
        assertThat(first.body["version"].asInt()).isEqualTo(1)
        assertThat(first.body["updatedBy"]["id"].isTextual).isTrue()
        assertThat(first.body["updatedBy"]["id"].asText()).isEqualTo(world.ownerId.toString())

        val same = call(world.ownerSession, HttpMethod.PUT, "${world.base}/developer-macros/open_shop", putBody(SOURCE, world.buildA))
        assertThat(same.body["version"].asInt()).describedAs("같은 원문은 버전을 올리지 않는다").isEqualTo(1)

        val movedBuild = call(world.ownerSession, HttpMethod.PUT, "${world.base}/developer-macros/open_shop", putBody(SOURCE, world.buildB))
        assertThat(movedBuild.body["version"].asInt()).describedAs("기준 build 만 옮긴 것은 정의 변경이 아니다").isEqualTo(1)
        assertThat(movedBuild.body["gameBuildId"].asText()).isEqualTo(world.buildB.toString())

        val changed = call(world.ownerSession, HttpMethod.PUT, "${world.base}/developer-macros/open_shop", putBody(SOURCE_V2, world.buildB))
        assertThat(changed.body["version"].asInt()).isEqualTo(2)

        val list = call(world.ownerSession, HttpMethod.GET, "${world.base}/developer-macros")
        val item = list.body["items"].single()
        assertThat(item.fieldNames().asSequence().toList())
            .describedAs("목록에는 원문이 없다")
            .containsExactlyInAnyOrder(
                "name", "parameters", "docstring", "gameBuildId", "buildVersion", "buildLabel", "version",
                "updatedAt", "updatedBy"
            )
        assertThat(item["version"].asInt()).isEqualTo(2)
        assertThat(item["buildLabel"].asText()).isEqualTo("Beta")

        val detail = call(world.ownerSession, HttpMethod.GET, "${world.base}/developer-macros/open_shop")
        assertThat(detail.body["source"].asText()).isEqualTo(SOURCE_V2)
    }

    @Test
    fun `검사는 같은 프로젝트의 다른 developer macro 를 imports 로 넘기고 자기 자신은 뺀다`() {
        val world = newWorld()
        val other = newWorld()
        call(world.ownerSession, HttpMethod.PUT, "${world.base}/developer-macros/helper_a", putBody(HELPER_SOURCE, world.buildA))
        call(world.ownerSession, HttpMethod.PUT, "${world.base}/developer-macros/open_shop", putBody(SOURCE, world.buildA))
        call(other.ownerSession, HttpMethod.PUT, "${other.base}/developer-macros/elsewhere", putBody(SOURCE, other.buildA))
        checkRequests.clear()

        val checked = call(
            world.ownerSession, HttpMethod.POST, "${world.base}/developer-macros:check",
            """{"name":"open_shop","source":${json(SOURCE_V2)},"gameBuildId":"${world.buildA}"}"""
        )

        assertThat(checked.status).isEqualTo(200)
        assertThat(checked.body["ok"].asBoolean()).isTrue()
        assertThat(checked.body["parameters"].map { it.asText() }).containsExactly("card", "count")
        assertThat(checked.body["docstring"].asText()).isEqualTo("open_shop 의 설명")
        assertThat(checked.body["rejection"].isNull).isTrue()

        val sent = checkRequests.single()
        assertThat(sent["name"].asText()).isEqualTo("open_shop")
        assertThat(sent["source"].asText()).isEqualTo(SOURCE_V2)
        assertThat(sent["imports"].fieldNames().asSequence().toList()).containsExactly("helper_a")
        assertThat(sent["imports"]["helper_a"].asText()).isEqualTo(HELPER_SOURCE)

        val detail = call(world.ownerSession, HttpMethod.GET, "${world.base}/developer-macros/open_shop")
        assertThat(detail.body["source"].asText()).describedAs("검사는 저장하지 않는다").isEqualTo(SOURCE)
    }

    @Test
    fun `거절은 검사에서 200 의 ok false, 등록에서 400 developer_macro_rejected 이고 아무것도 쓰지 않는다`() {
        val world = newWorld()

        val checked = call(
            world.ownerSession, HttpMethod.POST, "${world.base}/developer-macros:check",
            """{"name":"broken","source":${json(REJECTED_SOURCE)},"gameBuildId":"${world.buildA}"}"""
        )
        assertThat(checked.status).isEqualTo(200)
        assertThat(checked.body["ok"].asBoolean()).isFalse()
        assertThat(checked.body["rejection"]["line"].asInt()).isEqualTo(2)
        assertThat(checked.body["rejection"]["reason"].asText()).isEqualTo("`REJECT` is not a macro call")
        assertThat(checked.body["parameters"].isNull).isTrue()

        val put = call(world.ownerSession, HttpMethod.PUT, "${world.base}/developer-macros/broken", putBody(REJECTED_SOURCE, world.buildA))
        assertThat(put.status).isEqualTo(400)
        assertThat(put.body["code"].asText()).isEqualTo("developer_macro_rejected")
        assertThat(put.body["fields"]["line"].asText()).isEqualTo("2")
        assertThat(put.body["fields"]["reason"].asText()).isEqualTo("`REJECT` is not a macro call")
        assertThat(put.body["message"].asText()).contains("`REJECT` is not a macro call")

        assertThat(call(world.ownerSession, HttpMethod.GET, "${world.base}/developer-macros/broken").status).isEqualTo(404)
    }

    @Test
    fun `멤버가 아니면 프로젝트가 없는 것처럼 404 이고, 다른 프로젝트의 build 는 404 다`() {
        val world = newWorld()
        val outsider = newWorld()
        call(world.ownerSession, HttpMethod.PUT, "${world.base}/developer-macros/open_shop", putBody(SOURCE, world.buildA))

        val requests = listOf(
            Triple(HttpMethod.GET, "${world.base}/developer-macros", null),
            Triple(HttpMethod.GET, "${world.base}/developer-macros/open_shop", null),
            Triple(HttpMethod.PUT, "${world.base}/developer-macros/open_shop", putBody(SOURCE, world.buildA)),
            Triple(
                HttpMethod.POST, "${world.base}/developer-macros:check",
                """{"name":"open_shop","source":${json(SOURCE)},"gameBuildId":"${world.buildA}"}"""
            ),
            Triple(HttpMethod.DELETE, "${world.base}/developer-macros/open_shop", null),
        )
        for ((method, path, body) in requests) {
            assertThat(call(outsider.ownerSession, method, path, body).status).describedAs("$method $path").isEqualTo(404)
        }

        val foreignBuild = call(
            world.ownerSession, HttpMethod.PUT, "${world.base}/developer-macros/open_shop", putBody(SOURCE, outsider.buildA)
        )
        assertThat(foreignBuild.status).isEqualTo(404)
        assertThat(checkRequests).describedAs("멤버 검사와 build 검사가 agent-server 호출보다 먼저다").hasSize(1)
    }

    @Test
    fun `이름이 Python identifier 가 아니면 400 이다`() {
        val world = newWorld()

        val put = call(world.ownerSession, HttpMethod.PUT, "${world.base}/developer-macros/1st-macro", putBody(SOURCE, world.buildA))
        assertThat(put.status).isEqualTo(400)

        val check = call(
            world.ownerSession, HttpMethod.POST, "${world.base}/developer-macros:check",
            """{"name":"open shop","source":${json(SOURCE)},"gameBuildId":"${world.buildA}"}"""
        )
        assertThat(check.status).isEqualTo(400)
        assertThat(check.body["fields"].has("name")).isTrue()
        assertThat(checkRequests).isEmpty()
    }

    @Test
    fun `gameBuildId 가 숫자로 된 문자열이 아니면 400 이고, JSON 숫자는 받는다`() {
        val world = newWorld()
        val path = "${world.base}/developer-macros/open_shop"

        for (gameBuildId in listOf("\"abc\"", "\"\"", "null", "\"-1\"")) {
            val answer = call(world.ownerSession, HttpMethod.PUT, path, """{"source":${json(SOURCE)},"gameBuildId":$gameBuildId}""")
            assertThat(answer.status).describedAs(gameBuildId).isEqualTo(400)
            assertThat(answer.body["fields"].has("gameBuildId")).describedAs(gameBuildId).isTrue()
        }
        assertThat(checkRequests).isEmpty()

        // Jackson 이 숫자를 문자열 필드로 받는다. 숫자로 보내던 클라이언트도 그대로 통한다.
        val numeric = call(world.ownerSession, HttpMethod.PUT, path, """{"source":${json(SOURCE)},"gameBuildId":${world.buildA}}""")
        assertThat(numeric.status).isEqualTo(200)
    }

    @Test
    fun `삭제는 204, 없는 이름은 404 다`() {
        val world = newWorld()
        call(world.ownerSession, HttpMethod.PUT, "${world.base}/developer-macros/open_shop", putBody(SOURCE, world.buildA))

        assertThat(call(world.ownerSession, HttpMethod.DELETE, "${world.base}/developer-macros/open_shop").status).isEqualTo(204)
        assertThat(call(world.ownerSession, HttpMethod.DELETE, "${world.base}/developer-macros/open_shop").status).isEqualTo(404)
        assertThat(call(world.ownerSession, HttpMethod.GET, "${world.base}/developer-macros").body["items"].size()).isZero()
    }

    @Test
    fun `내부 목록은 내부 포트에서 원문까지 싣고 공개 포트에는 없다`() {
        val world = newWorld()
        call(world.ownerSession, HttpMethod.PUT, "${world.base}/developer-macros/open_shop", putBody(SOURCE, world.buildA))
        val internalPath = "/internal/projects/${world.projectId}/developer-macros"

        val internal = objectMapper.readTree(
            WebClient.create("http://localhost:${internalApiServer.port}").get().uri(internalPath)
                .retrieve().bodyToMono(String::class.java).block()
        )
        val item = internal["items"].single()
        assertThat(item["name"].asText()).isEqualTo("open_shop")
        assertThat(item["source"].asText()).isEqualTo(SOURCE)
        assertThat(item["parameters"].map { it.asText() }).containsExactly("card", "count")

        val missing = WebClient.create("http://localhost:${internalApiServer.port}").get()
            .uri("/internal/projects/${Long.MAX_VALUE}/developer-macros")
            .exchangeToMono { Mono.just(it.statusCode().value()) }.block()
        assertThat(missing).isEqualTo(404)

        assertThat(call(world.ownerSession, HttpMethod.GET, internalPath).status)
            .describedAs("공개 포트에는 `/internal` 이 없다").isEqualTo(404)
    }

    // ---------- 픽스처 ----------

    private data class World(
        val ownerId: Long,
        val ownerSession: String,
        val projectId: Long,
        val base: String,
        val buildA: Long,
        val buildAVersion: String,
        val buildB: Long,
    )

    private fun newWorld(): World = runBlocking {
        val now = Instant.now()
        val seed = UUID.randomUUID().toString().take(8)
        val owner = signIn(seed)
        val project = projects.save(
            ProjectEntity(name = "developer-macro-$seed", genre = "ACTION", createdAt = now, updatedAt = now)
        )
        members.save(
            ProjectMemberEntity(projectId = project.id!!, appUserId = owner.userId.toLong(), role = "OWNER", createdAt = now)
        )
        val buildA = gameBuilds.save(
            GameBuildEntity(projectId = project.id, version = "1.0.$seed", createdAt = now, updatedAt = now)
        )
        val buildB = gameBuilds.save(
            GameBuildEntity(projectId = project.id, version = "1.1.$seed", label = "Beta", createdAt = now, updatedAt = now)
        )
        World(
            ownerId = owner.userId.toLong(),
            ownerSession = jwtService.issue(owner),
            projectId = project.id,
            base = "/api/projects/${project.id}",
            buildA = buildA.id!!,
            buildAVersion = buildA.version,
            buildB = buildB.id!!,
        )
    }

    private suspend fun signIn(seed: String): AuthenticatedUser = oauthUsers.upsert(
        OAuthIdentity(
            provider = "github",
            providerUserId = "devmacro-$seed",
            login = "dev-$seed",
            displayName = "dev-$seed",
            avatarUrl = null,
            email = "dev-$seed@example.com",
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

    private fun putBody(source: String, gameBuildId: Long) = """{"source":${json(source)},"gameBuildId":"$gameBuildId"}"""

    private fun json(value: String) = objectMapper.writeValueAsString(value)
}

private const val COOKIE = "artel_access_token"

/** agent-server `MacroGrammarResponse` 의 모양(`{grammar, rules}`). 이 서버는 읽지 않고 바이트 그대로 옮긴다. */
private const val GRAMMAR =
    """{"grammar":"# macro grammar\n\n`def name(...) -> None:` 로 시작한다.\n","rules":["entry 는 -> None 이다","docstring 이 있어야 한다"]}"""

private val SOURCE = """
    def open_shop(card: str, count: int) -> None:
        ""${'"'}상점을 연다""${'"'}
        button_click(find(label="Shop"))
""".trimIndent()

private val SOURCE_V2 = """
    def open_shop(card: str, count: int) -> None:
        ""${'"'}상점을 연다""${'"'}
        button_click(find(label="Store"))
""".trimIndent()

private val HELPER_SOURCE = """
    def helper_a() -> None:
        ""${'"'}돕는다""${'"'}
        button_click(find(label="Ok"))
""".trimIndent()

private val REJECTED_SOURCE = """
    def broken() -> None:
        REJECT()
""".trimIndent()
