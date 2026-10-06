package kr.artel.orchestration.contentmap

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.contentmap.dto.MacroParameterResponse
import kr.artel.orchestration.contentmap.dto.MacroScreenResponse
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.entity.ScreenEntity
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.MacroRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.contentmap.repository.ScreenMacroRepository
import kr.artel.orchestration.contentmap.repository.ScreenRepository
import kr.artel.orchestration.contentmap.service.MacroViewService
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
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

private const val BATTLE = "TurnBattleScene"
private const val ATTACK = "attack_with_combined_card"
private const val CLOSE = "close_dialog"

/**
 * 빌드의 macro 를 사람이 읽는 두 경로(ARTEL-943).
 *
 * 이 파일이 지키는 것은 다섯이다.
 *
 * 1. **목록에 `source` 가 없다** — 직렬화한 JSON 에 그 key 자체가 없는지 본다. 한 건이 20,000자라
 *    실리면 빌드 하나가 수백 KB 가 되고, 그 사실은 타입만 봐서는 다음 사람에게 안 보인다
 * 2. **상세는 원문 그대로를 낸다** — 공백도 줄바꿈도 깎지 않는다
 * 3. **빈 `screens` 가 빈 채로 나간다** — 그 뜻은 아직 어디서 쓸지 모른다는 것이고, 목록에서
 *    빼 버리면 "없다" 로 바뀐다
 * 4. **`parameters` 가 순서대로 나오고 선언 타입을 tree 에서 집어 온다** — 이름과 순서는
 *    `parameter_names` 가, 타입은 `definition_json` 이 원본이다
 * 5. **빌드 경계를 넘지 않는다** — 경로의 `projectId` 도, 다른 빌드의 macro id 도 막힌다
 */
@ActiveProfiles("test")
@SpringBootTest
class MacroViewTest {

    @Autowired private lateinit var view: MacroViewService
    @Autowired private lateinit var macros: MacroRepository
    @Autowired private lateinit var screenMacros: ScreenMacroRepository
    @Autowired private lateinit var screens: ScreenRepository
    @Autowired private lateinit var scenes: SceneRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var members: ProjectMemberRepository
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var db: DatabaseClient

    /**
     * ARTEL-943 의 Validation Notes 첫 줄 — macro 둘을 서로 다른 `screen` 에 달고 목록에 둘 다
     * **자기** `screen` 과 함께 서는지 본다.
     *
     * 조인 하나로 두 macro 의 관계를 한꺼번에 읽으므로, 묶는 자리가 틀리면 두 macro 가 같은
     * `screen` 목록을 들고 나온다. 그 실패는 macro 가 하나뿐인 테스트로는 절대 안 잡힌다.
     */
    @Test
    fun `macro 마다 자기 screen 이 붙어 나온다`(): Unit = runBlocking {
        val world = newWorld()
        val attack = world.register(ATTACK, screenIds = listOf(world.hand))
        val close = world.register(CLOSE, screenIds = listOf(world.dialog))

        val items = view.list(world.userId, world.projectId, world.buildId)!!.items

        assertThat(items.map { it.id }).containsExactly(attack, close)
        assertThat(items.single { it.id == attack }.screens)
            .containsExactly(MacroScreenResponse(id = world.hand, name = "손패", sceneName = BATTLE))
        assertThat(items.single { it.id == close }.screens)
            .containsExactly(MacroScreenResponse(id = world.dialog, name = null, sceneName = BATTLE))
    }

    /**
     * `screen` 둘에 달린 macro. **관계 하나짜리 테스트로는 셋이 한 번도 안 돈다** — `groupBy` 가
     * 한 macro 에 행 여럿을 모으는 경로, `MACRO_SCREEN_JOIN` 의 `ORDER BY sc.id ASC`,
     * 상세의 `findScreenRowsByMacroId` 가 행 둘 이상을 내는 경로.
     *
     * **거꾸로 단다.** `dialog` 를 먼저 `link` 하고 `hand` 를 나중에 하므로, 관계를 적은 순서와
     * `screen.id` 순서가 어긋난다. 응답이 `hand` 를 앞에 내야 `ORDER BY` 가 실제로 걸린 것이다 —
     * 그냥 순서대로 달면 `ORDER BY` 를 지워도 테스트가 통과한다.
     *
     * 그 정렬은 KDoc 이 "같은 관계를 다시 받아도 흔들리지 않는다" 고 계약으로 적어 둔 것이다.
     * 테스트가 보지 않으면 계약이 아니다.
     */
    @Test
    fun `screen 둘에 달린 macro 가 screen id 순으로 나온다`(): Unit = runBlocking {
        val world = newWorld()
        val macroId = world.register(ATTACK, screenIds = listOf(world.dialog, world.hand))

        val expected = listOf(
            MacroScreenResponse(id = world.hand, name = "손패", sceneName = BATTLE),
            MacroScreenResponse(id = world.dialog, name = null, sceneName = BATTLE),
        )
        assertThat(world.hand).describedAs("픽스처가 id 순서를 보장한다").isLessThan(world.dialog)
        assertThat(view.list(world.userId, world.projectId, world.buildId)!!.items.single().screens)
            .containsExactlyElementsOf(expected)
        assertThat(view.read(world.userId, world.projectId, world.buildId, macroId)!!.screens)
            .describedAs("상세도 같은 순서다")
            .containsExactlyElementsOf(expected)
    }

    /**
     * 목록은 이름 오름차순이다. 등록 순서로 내면 agent 가 무엇을 먼저 떠올렸나가 되고, 그것은
     * 사람이 목록에서 찾는 축이 아니다.
     */
    @Test
    fun `목록은 이름 오름차순이다`(): Unit = runBlocking {
        val world = newWorld()
        world.register("zoom_out")
        world.register("attack")
        world.register("move")

        val items = view.list(world.userId, world.projectId, world.buildId)!!.items

        assertThat(items.map { it.name }).containsExactly("attack", "move", "zoom_out")
    }

    /**
     * **목록에 `source` key 가 아예 없다.** 타입에 칸이 없다는 것만으로는 다음 사람이 그 칸을
     * 더해도 아무것도 안 깨지므로, 직렬화한 모양을 직접 본다.
     *
     * `definition` 도 양쪽에 없다. 실행용 캐시이고 사람이 읽을 것은 원문이다.
     */
    @Test
    fun `목록 JSON 에 source 와 definition 이 없다`(): Unit = runBlocking {
        val world = newWorld()
        world.register(ATTACK, screenIds = listOf(world.hand))

        val json = objectMapper.writeValueAsString(view.list(world.userId, world.projectId, world.buildId))

        assertThat(json).doesNotContain("source").doesNotContain("definition")
        assertThat(json).describedAs("목록이 비어서 통과한 것이 아니다").contains(ATTACK)
    }

    /** 상세는 원문 **그대로**다. 들여쓰기가 Python 문법의 일부라 공백 하나도 깎지 않는다. */
    @Test
    fun `상세는 원문을 글자 그대로 낸다`(): Unit = runBlocking {
        val world = newWorld()
        val macroId = world.register(ATTACK, screenIds = listOf(world.hand))

        val detail = view.read(world.userId, world.projectId, world.buildId, macroId)!!

        assertThat(detail.source).isEqualTo(SOURCE)
        assertThat(detail.id).isEqualTo(macroId)
        assertThat(detail.name).isEqualTo(ATTACK)
        assertThat(detail.screens)
            .describedAs("목록 항목에 source 를 더한 것이라 나머지 칸이 같다")
            .containsExactly(MacroScreenResponse(id = world.hand, name = "손패", sceneName = BATTLE))

        val json = objectMapper.writeValueAsString(detail)
        assertThat(json).describedAs("실행용 tree 는 상세에도 안 싣는다").doesNotContain("definition")
    }

    /**
     * 어느 `screen` 과도 안 이어진 macro 도 목록에 선다. **빈 배열로 선다** — 그 뜻은 아직 어디서
     * 쓸지 모른다는 것이지 아무 데서나 된다는 것이 아니고, 목록에서 빼면 "없다" 로 바뀐다.
     */
    @Test
    fun `screen 이 안 붙은 macro 도 빈 배열로 목록에 선다`(): Unit = runBlocking {
        val world = newWorld()
        val orphan = world.register(ATTACK)

        val items = view.list(world.userId, world.projectId, world.buildId)!!.items

        assertThat(items.map { it.id }).containsExactly(orphan)
        assertThat(items.single().screens).isEmpty()
        assertThat(view.read(world.userId, world.projectId, world.buildId, orphan)!!.screens).isEmpty()
    }

    /**
     * 이름과 순서는 `parameter_names` 가, 선언 타입은 `definition_json` 의 진입점 `def` 가 낸다.
     * 타입이 그 칸에 없는 것은 `V98` 의 결정이라, 둘을 합치지 않으면 응답에 타입이 영영 안 실린다.
     *
     * tree 에 없는 parameter 는 타입만 null 이고 이름과 자리는 남는다. tree 의 모양이 아직 계약이
     * 아니라(ARTEL-918) 이 쪽이 비어도 조회는 서야 한다.
     */
    @Test
    fun `parameter 는 순서대로 나오고 타입은 tree 에서 온다`(): Unit = runBlocking {
        val world = newWorld()
        val macroId = world.register(ATTACK)

        val parameters = view.list(world.userId, world.projectId, world.buildId)!!.items.single().parameters

        assertThat(parameters).containsExactly(
            MacroParameterResponse(name = "card_a", type = "string"),
            MacroParameterResponse(name = "card_b", type = "string"),
            MacroParameterResponse(name = "repeat", type = null),
        )
        assertThat(view.read(world.userId, world.projectId, world.buildId, macroId)!!.parameters)
            .describedAs("상세도 같은 자리에서 읽는다")
            .isEqualTo(parameters)
    }

    /**
     * tree 가 진입점 `def` 를 안 들고 있으면 타입만 비고 이름과 순서는 그대로 나간다.
     * ARTEL-918 이 tree 의 key 를 다르게 정하는 날 조회가 통째로 깨지는 것을 막는 줄이다.
     */
    @Test
    fun `tree 가 타입을 말하지 않으면 타입만 빈다`(): Unit = runBlocking {
        val world = newWorld()
        world.register(ATTACK, definition = """{"statements":[]}""")

        val parameters = view.list(world.userId, world.projectId, world.buildId)!!.items.single().parameters

        assertThat(parameters.map { it.name }).containsExactly("card_a", "card_b", "repeat")
        assertThat(parameters.map { it.type }).containsOnlyNulls()
    }

    /**
     * macro 가 없는 빌드는 404 가 아니라 **빈 목록**이다. 빌드는 존재하고 접근도 되며, 화면은
     * "아직 등록된 macro 가 없다" 를 그려야 한다.
     *
     * 지도가 아예 없는 빌드도 같은 답이다 — `evidence` scan 을 안 돌린 빌드가 흔하고, 그 상태를
     * 404 로 답하면 화면이 빌드가 없는 것과 구분하지 못한다.
     */
    @Test
    fun `macro 가 없는 빌드는 빈 목록이다`(): Unit = runBlocking {
        val world = newWorld()
        assertThat(view.list(world.userId, world.projectId, world.buildId)!!.items).isEmpty()

        val bare = newBuild(world.projectId)
        assertThat(view.list(world.userId, world.projectId, bare))
            .describedAs("지도가 없는 빌드도 404 가 아니다")
            .isNotNull()
        assertThat(view.list(world.userId, world.projectId, bare)!!.items).isEmpty()
    }

    /**
     * 경로의 `projectId` 가 빌드의 것과 다르면 못 읽는다. 검사하지 않으면 그 값은 장식이 되고,
     * 아무 프로젝트 id 나 끼워 넣은 화면이 남의 빌드의 macro 원문을 그대로 보여 준다.
     *
     * 사용자는 두 프로젝트 모두의 멤버다 — **권한이 아니라 경로가 어긋난 것**을 잡는지 본다.
     */
    @Test
    fun `경로의 프로젝트가 다르면 목록도 상세도 안 나온다`(): Unit = runBlocking {
        val world = newWorld()
        val macroId = world.register(ATTACK)
        val other = newProject(world.userId)

        assertThat(view.list(world.userId, other, world.buildId)).isNull()
        assertThat(view.read(world.userId, other, world.buildId, macroId)).isNull()
        assertThat(view.list(world.userId, world.projectId, world.buildId)).isNotNull()
    }

    /**
     * 다른 빌드에 등록된 macro 는 이 빌드의 주소로 안 나온다. `macro.id` 는 전역으로 유일해서
     * `content_map_id` 를 함께 걸지 않으면 조회가 통과하고, 그러면 경로의 `gameBuildId` 가
     * 장식이 된다.
     *
     * 멤버인 사용자로 본다 — 권한이 아니라 **좁히는 조건**이 있는지 보는 것이다.
     */
    @Test
    fun `다른 빌드의 macro id 로는 상세가 안 나온다`(): Unit = runBlocking {
        val world = newWorld()
        val other = newWorld(reuse = world)
        val theirs = other.register(ATTACK)

        assertThat(view.read(world.userId, world.projectId, world.buildId, theirs)).isNull()
        assertThat(view.read(world.userId, world.projectId, other.buildId, theirs)).isNotNull()
    }

    /**
     * 아무 관계 없는 사용자에게는 **없는 것과 같다.**
     *
     * 이 기능의 권한 판정은 `gameBuilds.findAccessibleById` 하나뿐이라, 그것이 빠지거나 뒤집히면
     * 남의 빌드의 macro 이름과 원문이 그대로 나간다 — 게임의 내용 자체다. 위 테스트들은 전부
     * 멤버로 돌므로 그 줄이 없어져도 하나도 안 깨진다.
     *
     * 부재와 권한 없음을 같은 null(→ 404)로 묶는 것도 함께 본다. 구분해 주면 id 를 훑어 남의
     * 빌드가 존재한다는 사실을 알아낼 수 있다 —
     * `EvidenceDocumentServiceTest.남의 빌드는 보이지 않는다` 가 같은 모양이다.
     */
    @Test
    fun `남의 빌드의 macro 는 보이지 않는다`(): Unit = runBlocking {
        val world = newWorld()
        val macroId = world.register(ATTACK, screenIds = listOf(world.hand))
        val stranger = newUser()

        assertThat(view.list(stranger, world.projectId, world.buildId)).isNull()
        assertThat(view.read(stranger, world.projectId, world.buildId, macroId)).isNull()
    }

    // ---------- 픽스처 ----------

    private suspend fun newWorld(reuse: World? = null): World {
        val userId = reuse?.userId ?: newUser()
        val projectId = reuse?.projectId ?: newProject(userId)
        val buildId = newBuild(projectId)
        val contentMapId = contentMaps.save(
            ContentMapEntity(
                gameBuildId = buildId,
                schemaVersion = 6,
                capture = Capture.PLAYER.wire,
                evidenceDigest = "macro-view-${System.nanoTime()}",
            )
        )!!.id!!
        val sceneId = scenes.save(
            SceneEntity(contentMapId = contentMapId, name = BATTLE, walked = true)
        )!!.id!!
        // 이름이 붙은 `screen` 과 안 붙은 `screen` 을 함께 둔다. 이름은 LLM 이 짓는 표시용 값이라
        // nullable 이고, 응답이 그것을 견디는지 한 테스트에서 함께 보인다.
        val hand = screens.save(
            ScreenEntity(
                sceneId = sceneId,
                name = "손패",
                discriminator = Json.of("""[{"selector":"Canvas[0]/Hand","active":true}]"""),
            )
        )!!.id!!
        val dialog = screens.save(
            ScreenEntity(
                sceneId = sceneId,
                discriminator = Json.of("""[{"selector":"Canvas[0]/Dialog","active":true}]"""),
            )
        )!!.id!!
        return World(userId, projectId, buildId, contentMapId, hand, dialog)
    }

    private inner class World(
        val userId: Long,
        val projectId: Long,
        val buildId: Long,
        val contentMapId: Long,
        val hand: Long,
        val dialog: Long,
    ) {
        /**
         * macro 하나를 직접 적고 id 를 돌려준다.
         *
         * `MacroDefinitionService` 를 지나지 않는 것은 그 경로가 QA 런과 agent 세션을 요구해 이
         * 조회와 상관없는 fixture 를 여섯 개 더 세워야 하기 때문이다. 적는 값은 그 서비스가 적는
         * 것과 같은 모양이다.
         */
        suspend fun register(
            name: String,
            screenIds: List<Long> = emptyList(),
            definition: String = DEFINITION,
        ): Long {
            val macroId = macros.upsertByName(
                contentMapId = contentMapId,
                name = name,
                source = SOURCE,
                definitionJson = Json.of(definition.replace(ENTRYPOINT, name)),
                parameterNames = Json.of("""["card_a","card_b","repeat"]"""),
            ).id
            screenIds.forEach { screenMacros.link(it, macroId) }
            return macroId
        }
    }

    private suspend fun newUser(): Long =
        db.sql(
            "INSERT INTO app_user (display_name, nickname, user_tag) " +
                "VALUES ('console', 'console-' || gen_random_uuid(), '0000') RETURNING id"
        )
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().block()!!

    private suspend fun newProject(userId: Long): Long {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(name = "macro-view-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
        )
        members.save(
            ProjectMemberEntity(projectId = project.id!!, appUserId = userId, role = "OWNER", createdAt = now)
        )
        return project.id
    }

    private suspend fun newBuild(projectId: Long): Long {
        val now = Instant.now()
        return gameBuilds.save(
            GameBuildEntity(projectId = projectId, version = "v${System.nanoTime()}", createdAt = now, updatedAt = now)
        ).id!!
    }
}

private const val ENTRYPOINT = "__entrypoint__"

/** 공백과 줄바꿈이 든 원문. 상세가 이것을 글자 그대로 돌려주는지 보는 데 쓴다. */
private val SOURCE = """
    def $ATTACK(card_a: string, card_b: string, repeat):
        require(scene() == "$BATTLE", "전투 화면으로 먼저 간다")
        button_click(find(label=card_a))
""".trimIndent()

/**
 * `MacroDefinitionWriteTest` 의 fixture 와 같은 모양이다. `repeat` 만 tree 에서 타입을 뺐다 —
 * 그 자리가 응답에서 `type: null` 로 나와야 한다.
 */
private val DEFINITION = """
    {"entrypoint":"$ENTRYPOINT",
     "defs":[{"name":"$ENTRYPOINT",
       "parameters":[{"name":"card_a","type":"string"},{"name":"card_b","type":"string"},{"name":"repeat"}],
       "statements":[
         {"kind":"require","condition":{"op":"eq"},"remedy":"전투 화면으로 먼저 간다"},
         {"kind":"call","tool":"button_click"}]}]}
""".trimIndent()
