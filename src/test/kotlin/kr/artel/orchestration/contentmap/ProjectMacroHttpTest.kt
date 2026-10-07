package kr.artel.orchestration.contentmap

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.entity.ScreenEntity
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.MacroRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.contentmap.repository.ScreenMacroRepository
import kr.artel.orchestration.contentmap.repository.ScreenRepository
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
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Instant
import java.util.UUID

/**
 * macro 조회 **주소와 응답 필드 이름**이 계약이다(ARTEL-943).
 *
 * `MacroViewTest` 는 서비스를 직접 불러 무엇을 내는지 본다. 이 파일이 따로 있는 이유는 그 방식이
 * 잡지 못하는 것 둘 때문이다.
 *
 * 1. **주소.** `artel-home` 이 이 두 경로를 글자 그대로 적어 두고 부른다(ARTEL-941). 경로가 바뀌면
 *    이 서버의 테스트는 다 통과하면서 그쪽 화면만 404 가 된다
 * 2. **직렬화한 필드 이름.** Kotlin 프로퍼티 이름을 바꾸면 응답 key 가 조용히 따라 바뀐다
 *
 * 표를 비우지 않는다. `@BeforeEach` 에서 `deleteAll` 하는 테스트가 전체 suite 를 한 번에 돌릴 때
 * 다른 suite 가 남긴 행의 FK 에 막히는 것이 이미 알려진 실패고(ARTEL-661), 이 파일은 자기 행만
 * 새로 만들어 쓰므로 비울 이유가 없다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProjectMacroHttpTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUsers: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var macros: MacroRepository
    @Autowired private lateinit var screenMacros: ScreenMacroRepository
    @Autowired private lateinit var screens: ScreenRepository
    @Autowired private lateinit var scenes: SceneRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var members: ProjectMemberRepository

    @Test
    fun `목록과 상세가 game-build 하위 주소에 선다`(): Unit = runBlocking {
        val world = newWorld()

        val list = get(world.token, "${world.base}/macros")
        val item = list["items"].single()

        assertThat(item["id"].asLong()).isEqualTo(world.macroId)
        assertThat(item["name"].asText()).isEqualTo("attack_with_combined_card")
        assertThat(item["updatedAt"].isNull).isFalse()
        assertThat(item["parameters"].map { it["name"].asText() }).containsExactly("card_a")
        assertThat(item["parameters"].single()["type"].asText()).isEqualTo("string")
        assertThat(item["screens"].single()["id"].asLong()).isEqualTo(world.screenId)
        assertThat(item["screens"].single()["name"].asText()).isEqualTo("손패")
        assertThat(item["screens"].single()["sceneName"].asText()).isEqualTo("TurnBattleScene")
        assertThat(item.fieldNames().asSequence().toList())
            .describedAs("목록에는 source 도 definition 도 없다")
            .containsExactlyInAnyOrder("id", "name", "parameters", "screens", "updatedAt")

        val detail = get(world.token, "${world.base}/macros/${world.macroId}")

        assertThat(detail["source"].asText()).isEqualTo(SOURCE)
        assertThat(detail.fieldNames().asSequence().toList())
            .describedAs("상세는 목록 항목에 source 를 더한 것이다")
            .containsExactlyInAnyOrder("id", "name", "parameters", "screens", "updatedAt", "source")
    }

    /** 이 빌드에 없는 macro 는 404 다. 200 에 빈 몸통으로 답하면 화면이 빈 원문을 그린다. */
    @Test
    fun `이 빌드에 없는 macro 는 404 다`(): Unit = runBlocking {
        val world = newWorld()

        assertThat(statusOf { get(world.token, "${world.base}/macros/${world.macroId + 10_000}") })
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    // ---------- 픽스처 ----------

    private suspend fun newWorld(): World {
        val now = Instant.now()
        val seed = UUID.randomUUID().toString().take(8)
        val user = oauthUsers.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = seed,
                login = "user-$seed",
                displayName = "user-$seed",
                avatarUrl = null,
                email = "user-$seed@example.com",
            )
        )
        val project = projects.save(
            ProjectEntity(name = "macro-http-$seed", genre = "ACTION", createdAt = now, updatedAt = now)
        )
        members.save(
            ProjectMemberEntity(
                projectId = project.id!!,
                appUserId = user.userId.toLong(),
                role = "OWNER",
                createdAt = now,
            )
        )
        val buildId = gameBuilds.save(
            GameBuildEntity(projectId = project.id!!, version = "v$seed", createdAt = now, updatedAt = now)
        ).id!!
        val contentMapId = contentMaps.save(
            ContentMapEntity(
                gameBuildId = buildId,
                schemaVersion = 6,
                capture = Capture.PLAYER.wire,
                evidenceDigest = "macro-http-$seed",
            )
        )!!.id!!
        val sceneId = scenes.save(
            SceneEntity(contentMapId = contentMapId, name = "TurnBattleScene", walked = true)
        )!!.id!!
        val screenId = screens.save(
            ScreenEntity(
                sceneId = sceneId,
                name = "손패",
                discriminator = Json.of("""[{"selector":"Canvas[0]/Hand","active":true}]"""),
            )
        )!!.id!!
        val macroId = macros.upsertByName(
            contentMapId = contentMapId,
            name = "attack_with_combined_card",
            source = SOURCE,
            definitionJson = Json.of(DEFINITION),
            parameterNames = Json.of("""["card_a"]"""),
        ).id
        screenMacros.link(screenId, macroId)

        return World(
            token = jwtService.issue(user),
            base = "/api/projects/${project.id}/game-builds/$buildId",
            macroId = macroId,
            screenId = screenId,
        )
    }

    private data class World(
        val token: String,
        val base: String,
        val macroId: Long,
        val screenId: Long,
    )

    private fun get(token: String, uri: String): JsonNode = objectMapper.readTree(
        WebClient.create("http://localhost:$port").get().uri(uri)
            .cookie("artel_access_token", token)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun statusOf(call: () -> Any?): HttpStatus =
        try {
            call()
            HttpStatus.OK
        } catch (e: WebClientResponseException) {
            HttpStatus.valueOf(e.statusCode.value())
        }
}

private val SOURCE = """
    def attack_with_combined_card(card_a: string):
        require(scene() == "TurnBattleScene", "전투 화면으로 먼저 간다")
        button_click(find(label=card_a))
""".trimIndent()

private val DEFINITION = """
    {"entrypoint":"attack_with_combined_card",
     "defs":[{"name":"attack_with_combined_card",
       "parameters":[{"name":"card_a","type":"string"}],
       "statements":[{"kind":"require","condition":{"op":"eq"},"remedy":"전투 화면으로 먼저 간다"}]}]}
""".trimIndent()
