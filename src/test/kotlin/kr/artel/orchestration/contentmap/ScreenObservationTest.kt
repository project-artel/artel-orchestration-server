package kr.artel.orchestration.contentmap

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.contentmap.entity.Actionability
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.entity.CapabilityOrigin
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.EdgeSource
import kr.artel.orchestration.contentmap.entity.Interaction
import kr.artel.orchestration.contentmap.entity.SceneEdgeEntity
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.entity.TransitionKind
import kr.artel.orchestration.contentmap.observe.ScreenObservationService
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneEdgeRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.contentmap.repository.ScreenCapabilityRepository
import kr.artel.orchestration.contentmap.repository.ScreenRepository
import kr.artel.orchestration.contentmap.repository.ScreenTransitionRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaRunEntity
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * `pulse` 에서 화면을 가르고 화면 전이를 남긴다 (ARTEL-453).
 *
 * 이 파일이 지키는 것은 둘이다.
 *
 * 1. **이슈의 인수 조건** — `Canvas/continue` 켜짐·꺼짐이 화면 둘로 갈리고, `screen_capability`
 *    가 씬 목록의 부분집합이며, 씬을 넘는 전이가 `scene_edge` 를 검증됨으로 올리고, 정적 후보에
 *    없던 전이가 `source='runtime'` 으로 들어온다
 * 2. **임계값** — 무엇을 다른 화면으로 보고 무엇을 같은 화면으로 보는가. 이것이 나중에 손댈
 *    가능성이 가장 큰 값이라, 무엇이 의도였는지를 말하는 것이 테스트뿐이다
 *    (`화면을 가르지 않는 것들` · `한 pulse 만 스친 상태` 두 건)
 *
 * 값 자체가 아니라 **규칙**을 고정한다. 값(멤버 값·게임플레이 오브젝트)이 아니라 조작 가능한
 * 객체의 켜짐/꺼짐만 본다는 것, 그리고 연속 관측이 있어야 굳는다는 것.
 */
@ActiveProfiles("test")
@SpringBootTest
class ScreenObservationTest {

    @Autowired private lateinit var observation: ScreenObservationService
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var gameInstances: GameInstanceRepository
    @Autowired private lateinit var testRuns: TestRunRepository
    @Autowired private lateinit var qaRuns: QaRunRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var scenes: SceneRepository
    @Autowired private lateinit var capabilities: CapabilityRepository
    @Autowired private lateinit var screens: ScreenRepository
    @Autowired private lateinit var screenCapabilities: ScreenCapabilityRepository
    @Autowired private lateinit var transitions: ScreenTransitionRepository
    @Autowired private lateinit var sceneEdges: SceneEdgeRepository
    @Autowired private lateinit var db: DatabaseClient

    // ---------- 인수 조건 ----------

    /**
     * **이 기능이 존재하는 이유다.** `Canvas/continue` 가 켜진 화면과 꺼진 화면은 같은 씬인데,
     * 한 화면으로 뭉치면 "continue 를 눌러라"는 TC 가 절반의 경우에 실패하고 QA agent 가 그것을
     * 결함으로 보고한다.
     */
    @Test
    fun `continue 켜짐과 꺼짐이 한 씬에서 화면 둘로 갈린다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))
        observeTwice(world, whole("TitleScene", deactive = listOf(CONTINUE)))

        val rows = screens.findBySceneIdOrderByIdAsc(title).toList()
        assertThat(rows).hasSize(2)
        assertThat(read(rows.first().discriminator)).containsExactly(CONTINUE to true)
        assertThat(read(rows.last().discriminator)).containsExactly(CONTINUE to false)
    }

    /**
     * 화면은 자기 기능 목록을 따로 갖지 않는다. 두 벌 두면 갈라진다.
     *
     * `pulse` 가 `offers` 로 광고한 객체는 `discriminator` 에는 들어가지만(`evidence` 가 놓친 팝업을 가르는 유일한
     * 수단이다) `screen_capability` 에는 들어가지 않는다 — 그 표의 행은 **씬 기능 행**을 가리키고,
     * 없는 기능을 여기서 만들면 근거 없는 것이 근거 있는 것처럼 취급된다.
     */
    @Test
    fun `화면 기능이 씬 기능 목록의 부분집합이다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        val continueId = newCapability(world, title, CONTINUE)
        newCapability(world, title, "Canvas[2]/settings[3]")

        // continue 는 켜져 있고, settings 는 꺼져 있고, popup 은 `evidence` 에 없는데 `pulse` 가 광고한다.
        observeTwice(
            world,
            whole(
                "TitleScene",
                active = listOf(CONTINUE),
                deactive = listOf("Canvas[2]/settings[3]"),
                advertised = listOf("Canvas[2]/popup[7]"),
            ),
        )

        val screen = screens.findBySceneIdOrderByIdAsc(title).toList().single()
        val recorded = screenCapabilities.findByScreenId(screen.id!!).toList()

        val sceneCapabilities = capabilities.findBySceneIdOrderByIdAsc(title).toList().map { it.id }
        assertThat(recorded.map { it.capabilityId }).isSubsetOf(sceneCapabilities)
        // 켜진 것만. 꺼진 settings 는 이 화면이 제공한 기능이 아니다.
        assertThat(recorded.map { it.capabilityId }).containsExactly(continueId)
        assertThat(recorded.single().observedCount).isEqualTo(1)
        // ARTEL-450 이 없어 무엇을 눌렀는지 모른다. 0 이 정직한 값이다.
        assertThat(recorded.single().firedCount).isZero()

        // popup 은 `discriminator` 에는 있다 — 그래야 팝업이 뜬 화면과 아닌 화면이 갈린다.
        assertThat(read(screen.discriminator)).contains("Canvas[2]/popup[7]" to true)
    }

    /**
     * 씬을 넘는 전이가 정적 후보를 검증됨으로 올린다.
     *
     * `verified_at IS NULL` 인 간선이 곧 커버리지 구멍이고 QA agent 에게 다음에 무엇을 시도할지
     * 알려주는 유일한 신호다. 관측이 그것을 지워 주지 않으면 그 신호는 영영 틀린 채로 남는다.
     */
    @Test
    fun `씬을 넘는 전이가 정적 간선을 검증됨으로 올린다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        val map = newScene(world, "MapScene")
        val continueId = newCapability(world, title, CONTINUE)
        newCapability(world, map, BACK)
        val edgeId = sceneEdges.save(
            SceneEdgeEntity(
                fromSceneId = title,
                toSceneName = "MapScene",
                toSceneId = map,
                capabilityId = continueId,
                source = EdgeSource.STATIC.wire,
            )
        ).id!!

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))
        observeTwice(world, whole("MapScene", active = listOf(BACK)))

        val edge = sceneEdges.findById(edgeId)!!
        assertThat(edge.verifiedAt).isNotNull
        assertThat(edge.observedCount).isEqualTo(1)
        assertThat(edge.firstObservedTransitionId).isNotNull

        val transition = transitions.findById(edge.firstObservedTransitionId!!)!!
        assertThat(transition.crossesScene).isTrue
        // ARTEL-450 이 없어 무엇이 이 전이를 일으켰는지 모른다. 그 자리는 비운다.
        assertThat(transition.capabilityId).isNull()
        assertThat(transition.kind).isEqualTo(TransitionKind.ACTION.wire)
    }

    /**
     * 정적 후보에 없던 전이는 `source='runtime'` 으로 들어온다.
     *
     * **오류가 아니라 발견이다** — 정적 분석이 놓친 씬 전이이고, `evidence` 수집을 어디서 고칠지
     * 알려주는 신호다.
     */
    @Test
    fun `정적 후보에 없던 전이가 runtime 으로 들어온다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        val map = newScene(world, "MapScene")
        newCapability(world, title, CONTINUE)
        newCapability(world, map, BACK)

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))
        observeTwice(world, whole("MapScene", active = listOf(BACK)))

        val edge = sceneEdges.findByFromSceneIdOrderByIdAsc(title).toList().single()
        assertThat(edge.source).isEqualTo(EdgeSource.RUNTIME.wire)
        assertThat(edge.toSceneName).isEqualTo("MapScene")
        assertThat(edge.toSceneId).isEqualTo(map)
        assertThat(edge.capabilityId).isNull()
        assertThat(edge.verifiedAt).isNotNull
        assertThat(edge.observedCount).isEqualTo(1)
    }

    /**
     * 아는 화면을 다시 밟아도 행이 늘지 않는다. `observed_count` 는 `pulse` 수가 아니라 **방문 수**다.
     *
     * 한 화면에 머무는 동안 `pulse` 는 초당 여러 번 온다. 그것을 다 세면 이 값은 체류 시간이 되고,
     * "몇 번 지나갔나"를 묻는 소비자가 답을 얻지 못한다.
     */
    @Test
    fun `재방문이 화면을 늘리지 않고 방문 수만 올린다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)

        val lit = whole("TitleScene", active = listOf(CONTINUE))
        val unlit = whole("TitleScene", deactive = listOf(CONTINUE))

        observeTwice(world, lit)
        // 머무는 동안 `pulse` 가 더 와도 아무 일도 없어야 한다.
        observation.observe(world.gameInstanceId, lit)
        observation.observe(world.gameInstanceId, lit)
        observeTwice(world, unlit)
        observeTwice(world, lit)

        val rows = screens.findBySceneIdOrderByIdAsc(title).toList()
        assertThat(rows).hasSize(2)
        assertThat(rows.first().observedCount).isEqualTo(2)
        assertThat(rows.last().observedCount).isEqualTo(1)

        // 왕복이라 전이는 둘이다. 같은 화면 쌍을 다시 밟으면 행이 아니라 관측 수가 오른다.
        assertThat(transitions.findByFromScreenIdOrderByIdAsc(rows.first().id!!).toList()).hasSize(1)
        assertThat(transitions.findByFromScreenIdOrderByIdAsc(rows.last().id!!).toList()).hasSize(1)
    }

    // ---------- 임계값 ----------

    /**
     * **임계값을 고정한다.** 감시 멤버 값이 바뀌어도, 게임플레이 오브젝트가 나타났다 사라져도
     * 화면은 갈리지 않는다.
     *
     * 이 둘을 `discriminator` 에 넣으면 화면 수가 플레이 길이에 비례한다 — 다이어그램이 읽을 수 없어지고
     * 화면 캡처가 행마다 튄다. 여기가 깨지는 것을 보게 되면 `discriminator` 규칙이 넓어진 것이고,
     * `ScreenFold` 의 `임계값` 절이 그 판단의 근거다.
     */
    @Test
    fun `값 변화와 게임플레이 오브젝트는 화면을 가르지 않는다`(): Unit = runBlocking {
        val world = newWorld()
        val battle = newScene(world, "TurnBattleScene")
        newCapability(world, battle, CONTINUE)

        // 같은 컨트롤이 계속 켜져 있고, 값과 잡 오브젝트만 흔들린다.
        observeTwice(world, battleReading(turn = 1, extras = listOf("Cards[3]/hand[1]")))
        observation.observe(world.gameInstanceId, battleReading(turn = 2, extras = listOf("Cards[3]/hand[2]")))
        observation.observe(
            world.gameInstanceId,
            battleReading(turn = 7, extras = listOf("Cards[3]/hand[1]", "Enemies[4]/slime[2]")),
        )

        assertThat(screens.findBySceneIdOrderByIdAsc(battle).toList()).hasSize(1)
    }

    /**
     * **정착(settling)을 고정한다.** 한 `pulse` 만 스친 상태는 화면이 되지 않는다.
     *
     * 전이 중간 프레임은 반쯤 지어진 UI 를 보여준다. 그 한 프레임이 화면 행과 전이 두 개를 만들면
     * 재현 경로에 아무도 본 적 없는 화면이 낀다. 사람이 한 프레임도 못 본 화면은 화면이 아니다.
     */
    @Test
    fun `한 pulse 만 스친 상태는 화면이 되지 않는다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)

        // 꺼짐이 딱 한 `pulse` 스치고 지나간다.
        observation.observe(world.gameInstanceId, whole("TitleScene", active = listOf(CONTINUE)))
        observation.observe(world.gameInstanceId, whole("TitleScene", deactive = listOf(CONTINUE)))
        observation.observe(world.gameInstanceId, whole("TitleScene", active = listOf(CONTINUE)))
        observation.observe(world.gameInstanceId, whole("TitleScene", active = listOf(CONTINUE)))

        val rows = screens.findBySceneIdOrderByIdAsc(title).toList()
        assertThat(rows).hasSize(1)
        assertThat(read(rows.single().discriminator)).containsExactly(CONTINUE to true)
    }

    /**
     * 델타 `pulse` 가 전량 `pulse` 위에 얹힌다. **말하지 않은 객체는 있던 자리를 지킨다.**
     *
     * agent-server `PulseMemory.apply` 와 같은 규칙이다. 이 채널의 소비자가 둘인데 델타를 다르게
     * 읽으면 화면이 갈린 이유를 두 쪽에서 다르게 설명하게 되고, 어느 쪽이 틀렸는지 가릴 수 없다.
     */
    @Test
    fun `델타 pulse 가 전량 pulse 위에 얹힌다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)
        newCapability(world, title, "Canvas[2]/settings[3]")

        observeTwice(
            world,
            whole("TitleScene", active = listOf(CONTINUE, "Canvas[2]/settings[3]")),
        )
        // 델타는 continue 만 말한다. settings 는 말하지 않았으니 켜진 채로 남아야 한다.
        val delta = """
            {"type":"PULSE","schema":2,"scene":"TitleScene","whole":false,
             "active":[],"deactive":[{"selector":"$CONTINUE"}]}
        """.trimIndent()
        observation.observe(world.gameInstanceId, delta)
        observation.observe(world.gameInstanceId, delta)

        val rows = screens.findBySceneIdOrderByIdAsc(title).toList()
        assertThat(rows).hasSize(2)
        assertThat(read(rows.last().discriminator))
            .containsExactly(CONTINUE to false, "Canvas[2]/settings[3]" to true)
    }

    /** QA 런이 없으면 화면을 만들지 않는다. 화면은 런이 관측한 것이다. */
    @Test
    fun `활성 런이 없으면 화면을 만들지 않는다`(): Unit = runBlocking {
        val world = newWorld(withRun = false)
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))

        assertThat(screens.findBySceneIdOrderByIdAsc(title).toList()).isEmpty()
    }

    // ---------- 픽스처 ----------

    private data class World(val gameInstanceId: Long, val contentMapId: Long)

    private val CONTINUE = "Canvas[2]/continue[1]"
    private val BACK = "Canvas[2]/back[1]"

    /** `discriminator` 가 굳으려면 연속 두 `pulse` 가 필요하다(`ScreenFold.SETTLE_READINGS`). */
    private suspend fun observeTwice(world: World, payload: String) {
        observation.observe(world.gameInstanceId, payload)
        observation.observe(world.gameInstanceId, payload)
    }

    private fun whole(
        scene: String,
        active: List<String> = emptyList(),
        deactive: List<String> = emptyList(),
        advertised: List<String> = emptyList(),
    ): String {
        val activeJson = (active.map { plain(it) } + advertised.map { offering(it) }).joinToString(",")
        val deactiveJson = deactive.joinToString(",") { plain(it) }
        return """
            {"type":"PULSE","schema":2,"scene":"$scene","whole":true,
             "active":[$activeJson],"deactive":[$deactiveJson],"unwatchable":0}
        """.trimIndent()
    }

    private fun plain(selector: String) = """{"selector":"$selector","path":"$selector"}"""

    private fun offering(selector: String) =
        """{"selector":"$selector","offers":{"click":{"key":"click"}}}"""

    /** 값과 잡 오브젝트만 흔들리는 `pulse`. 컨트롤은 계속 켜져 있다. */
    private fun battleReading(turn: Int, extras: List<String>): String {
        val extraJson = extras.joinToString("") { ",${plain(it)}" }
        return """
            {"type":"PULSE","schema":2,"scene":"TurnBattleScene","whole":true,
             "changed":["TurnBattleSystem.turn"],
             "active":[{"selector":"$CONTINUE","members":[
                 {"on":"Battle.Turns.TurnBattleSystem","member":"turn","value":$turn,"asked":true}]}$extraJson],
             "deactive":[]}
        """.trimIndent()
    }

    private suspend fun newWorld(withRun: Boolean = true): World {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(name = "screen-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
        )
        val build = gameBuilds.save(
            GameBuildEntity(
                projectId = project.id!!,
                version = "v${System.nanoTime()}",
                createdAt = now,
                updatedAt = now,
            )
        )
        val instance = gameInstances.save(
            GameInstanceEntity(
                projectId = project.id!!,
                name = "instance",
                platform = "UNITY",
                lastGameBuildId = build.id,
                lastConnectedAt = now,
                createdAt = now,
                updatedAt = now,
            )
        )
        val contentMap = contentMaps.save(
            ContentMapEntity(
                gameBuildId = build.id!!,
                schemaVersion = 6,
                capture = Capture.PLAYER.wire,
                evidenceDigest = "screen-observation",
            )
        )
        if (withRun) {
            val testRunId = testRuns.save(TestRunEntity(projectId = project.id!!, name = "런")).id!!
            qaRuns.save(
                QaRunEntity(
                    testRunId = testRunId,
                    gameInstanceId = instance.id!!,
                    startedBy = newUser(),
                    status = "RUNNING",
                    startedAt = now,
                )
            )
        }
        return World(instance.id!!, contentMap.id!!)
    }

    private suspend fun newScene(world: World, name: String): Long =
        scenes.save(SceneEntity(contentMapId = world.contentMapId, name = name, walked = true)).id!!

    private suspend fun newCapability(world: World, sceneId: Long, selector: String): Long =
        capabilities.save(
            CapabilityEntity(
                sceneId = sceneId,
                contentMapId = world.contentMapId,
                origin = CapabilityOrigin.OBSERVED.wire,
                summary = "$selector 를 누른다",
                controlSelector = selector,
                interaction = Interaction.CLICK.wire,
                actionability = Actionability.RUNNABLE.wire,
            )
        ).id!!

    /**
     * `discriminator` 를 `(selector, active)` 로 읽는다.
     *
     * 원문 문자열로 단언하지 않는 이유: jsonb 는 객체 키를 길이·바이트 순으로 다시 쓴다. 우리가
     * 쓴 순서와 읽는 순서가 다르므로, 문자열을 고정하면 적재기가 아니라 Postgres 의 표기를
     * 고정하게 된다.
     */
    private fun read(discriminator: Json): List<Pair<String, Boolean>> =
        ObjectMapper().readTree(discriminator.asString())
            .map { it.path("selector").asText() to it.path("active").asBoolean() }

    private fun newUser(): Long =
        db.sql("INSERT INTO app_user (display_name) VALUES ('screen') RETURNING id")
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().block()!!
}
