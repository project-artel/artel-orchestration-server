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
import kr.artel.orchestration.contentmap.entity.SceneScreenSelectorEntity
import kr.artel.orchestration.contentmap.entity.ScreenSelectorMatch
import kr.artel.orchestration.contentmap.entity.ScreenSelectorSource
import kr.artel.orchestration.contentmap.entity.TransitionKind
import kr.artel.orchestration.contentmap.observe.ScreenObservationService
import kr.artel.orchestration.contentmap.observe.ScreenSelectorWhitelist
import kr.artel.orchestration.contentmap.observe.toRule
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneEdgeRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.contentmap.repository.SceneScreenSelectorRepository
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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
    @Autowired private lateinit var screenSelectors: SceneScreenSelectorRepository
    @Autowired private lateinit var capabilities: CapabilityRepository
    @Autowired private lateinit var screens: ScreenRepository
    @Autowired private lateinit var screenCapabilities: ScreenCapabilityRepository
    @Autowired private lateinit var transitions: ScreenTransitionRepository
    @Autowired private lateinit var sceneEdges: SceneEdgeRepository
    @Autowired private lateinit var db: DatabaseClient

    /**
     * 이 스위트가 남긴 행을 치운다.
     *
     * 없으면 `newWorld` 가 만든 `qa_run` 이 스위트 끝까지 살아남아, **뒤에 도는 다른 클래스의**
     * `DELETE FROM app_user` · `DELETE FROM game_instance` 가 `qa_run_started_by_fkey` ·
     * `qa_run_game_instance_id_fkey` 로 막힌다. 실패가 이 파일이 아니라 남의 파일에서 나므로
     * 원인을 찾기 어렵고, 클래스 실행 순서가 바뀔 때마다 피해자가 달라진다.
     *
     * 리액티브 트랜잭션은 롤백되지 않고 DB 를 공유하므로 FK 순서대로 직접 비운다.
     */
    @BeforeEach
    @AfterEach
    fun clean(): Unit = runBlocking {
        qaRuns.deleteAll()
        testRuns.deleteAll()
        gameInstances.deleteAll()
        db.sql("DELETE FROM app_user WHERE display_name = 'screen'").then().block()
    }

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
     * `pulse` 가 `offers` 로 광고한 객체는 `screen_capability` 에 들어가지 않는다 — 그 표의 행은
     * **씬 기능 행**을 가리키고, 없는 기능을 여기서 만들면 근거 없는 것이 근거 있는 것처럼
     * 취급된다.
     *
     * `discriminator` 에도 들어가지 않는다(ARTEL-654). SDK 가 광고한다는 것은 "지금 무엇에
     * 응답하는가" 이지 "이것이 화면을 식별한다" 가 아니다.
     */
    @Test
    fun `화면 기능이 씬 기능 목록의 부분집합이다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        val continueId = newCapability(world, title, CONTINUE)
        newCapability(world, title, "Canvas[2]/settings[3]")

        // continue 는 켜져 있고, settings 는 꺼져 있고, popup 은 목록에 없는데 `pulse` 가 광고한다.
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

        // popup 은 목록에 없으므로 `discriminator` 에도 없다. 그것을 가르고 싶으면 목록에 넣는다.
        assertThat(read(screen.discriminator).map { it.first }).doesNotContain("Canvas[2]/popup[7]")
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

    // ---------- 목록 (ARTEL-654) ----------

    /**
     * **기본값이 뒤집힌 자리다.** 목록에 없는 selector 는 처음 보는 것이어도 화면을 못 가른다.
     *
     * 여기가 깨지면 화면 수가 실제 상태 수가 아니라 **플레이 길이**에 비례한다. 실측
     * `TurnBattleScene` 이 그래서 29행이었고 `MAX_SCREENS_PER_SCENE` 32 코앞이었다.
     */
    @Test
    fun `목록에 없는 selector 는 처음 보는 것이어도 화면을 안 가른다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)

        // 목록에 없는 것이 매번 다른 이름으로 들어온다. 이름에 카운터를 넣는 게임이 이 모양이다.
        observeTwice(world, offeringPulse("TitleScene", listOf(CONTINUE to true, "agent(1)[0]" to true)))
        observeTwice(world, offeringPulse("TitleScene", listOf(CONTINUE to true, "agent(2)[0]" to true)))
        observeTwice(world, offeringPulse("TitleScene", listOf(CONTINUE to true, "agent(3)[0]" to false)))

        val rows = screens.findBySceneIdOrderByIdAsc(title).toList()
        assertThat(rows).hasSize(1)
        assertThat(read(rows.single().discriminator)).containsExactly(CONTINUE to true)
        // 세 번 왔지만 화면은 한 번 앉았고, 그 뒤로는 재방문조차 아니다 — 계속 같은 화면에 머물렀다.
        assertThat(rows.single().observedCount).isEqualTo(1)
    }

    /**
     * 목록이 빈 씬은 화면이 하나다. **오류가 아니다.**
     *
     * 가를 근거가 하나도 없는데 가르는 것보다 맞다. 씨앗(`capability.control_selector`)이 이 상태를
     * 드물게 만들지만, 씨앗이 하나도 없는 씬은 정상적으로 화면 하나로 산다.
     */
    @Test
    fun `목록이 빈 씬은 화면이 하나다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        // capability 를 하나도 만들지 않는다 — 씨앗이 없다.

        observeTwice(world, offeringPulse("TitleScene", listOf(CONTINUE to true)))
        observeTwice(world, offeringPulse("TitleScene", listOf(CONTINUE to false)))

        val rows = screens.findBySceneIdOrderByIdAsc(title).toList()
        assertThat(rows).hasSize(1)
        assertThat(read(rows.single().discriminator)).isEmpty()
    }

    /** 씨앗은 `capability.control_selector` 다. 원문 하나를 가리키는 `selector` 항목으로 심는다. */
    @Test
    fun `capability 의 control_selector 가 목록의 씨앗으로 들어간다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)

        observeTwice(world, offeringPulse("TitleScene", listOf(CONTINUE to true)))

        val seeded = screenSelectors.findBySceneIdOrderByIdAsc(title).toList()
        assertThat(seeded).hasSize(1)
        assertThat(seeded.single().pattern).isEqualTo(CONTINUE)
        assertThat(seeded.single().matchKind).isEqualTo(ScreenSelectorMatch.SELECTOR.wire)
        assertThat(seeded.single().source).isEqualTo(ScreenSelectorSource.STATIC_ANALYSIS.wire)
        assertThat(seeded.single().screenDefining).isTrue

        // 다시 관측해도 행이 늘지 않는다. 멱등을 `uk_scene_screen_selector` 가 강제한다.
        observeTwice(world, offeringPulse("TitleScene", listOf(CONTINUE to false)))
        assertThat(screenSelectors.findBySceneIdOrderByIdAsc(title).toList()).hasSize(1)
    }

    /**
     * 목록에서 뺀 컨트롤도 `screen_capability` 에는 남는다.
     *
     * 두 표가 다른 질문에 답한다 — `discriminator` 는 "무엇이 이 화면을 식별하나", `screen_capability`
     * 는 "이 화면에서 무엇을 할 수 있었나" 다. 목록을 손대는 것이 두 번째 답을 조용히 지우면,
     * 화면 판정을 고치려던 사람이 커버리지 기록을 함께 지운다.
     */
    @Test
    fun `목록에서 뺀 컨트롤도 screen_capability 에는 남는다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        val spinner = "Canvas[2]/spinner[0]"
        val continueId = newCapability(world, title, CONTINUE)
        val spinnerId = newCapability(world, title, spinner)
        // 사람이 spinner 를 화면 판정에서 뺀다. 늘 켜져 있어 가르는 데 쓸모가 없다는 판단이다.
        excludeByHuman(title, spinner)

        observeTwice(world, offeringPulse("TitleScene", listOf(CONTINUE to true, spinner to true)))

        val screen = screens.findBySceneIdOrderByIdAsc(title).toList().single()
        assertThat(read(screen.discriminator)).containsExactly(CONTINUE to true)
        assertThat(screenCapabilities.findByScreenId(screen.id!!).toList().map { it.capabilityId })
            .containsExactlyInAnyOrder(continueId, spinnerId)
    }

    /**
     * **실측을 못으로 박는다.** `artel_integration` 의 `TurnBattleScene` 화면 29행은 같은 씬을 29번
     * 다르게 적은 것이었다. selector 도 combine panel 의 세 상태도 그 행들에서 그대로 가져왔다.
     *
     * 목록을 씨앗(`control_selector` 셋)만으로 두면 화면은 **둘**이다. 씨앗이 combine 확정 버튼의
     * 켜짐/꺼짐만 담고 있어서, combine panel 이 열렸는지는 못 가른다.
     */
    @Test
    fun `씨앗만으로도 실측 TurnBattleScene 이 화면 둘로 접힌다`(): Unit = runBlocking {
        val world = newWorld()
        val battle = newScene(world, "TurnBattleScene")
        for (selector in SEEDED_BATTLE_CONTROLS) newCapability(world, battle, selector)

        observeBattle(world)

        val rows = screens.findBySceneIdOrderByIdAsc(battle).toList()
        assertThat(rows).hasSize(2)
        assertThat(rows.map { read(it.discriminator) }).containsExactly(
            listOf(
                "CombineSystem[7]/CombineButton[0]" to true,
                "CombineSystem[7]/CombineZone[1]/Button[2]" to false,
                "DebugCanvas[4]/TurnEndButton[0]" to true,
            ),
            listOf(
                "CombineSystem[7]/CombineButton[0]" to true,
                "CombineSystem[7]/CombineZone[1]/Button[2]" to true,
                "DebugCanvas[4]/TurnEndButton[0]" to true,
            ),
        )
    }

    /**
     * 실제로 화면을 가르는 넷을 목록에 넣으면 화면이 **셋**이다 — combine panel 닫힘 · 열림 ·
     * 확정 가능.
     *
     * 그 넷은 실측에서 화면마다 `active` 가 달랐던 selector 다. 둘이 `Zone1` 과 `Zone2` 인 것이
     * 이름만 보고 깎으면 안 되는 이유이기도 하다 — 끝자리가 숫자지만 서로 다른 오브젝트다.
     */
    @Test
    fun `화면을 가르는 넷을 목록에 넣으면 실측이 화면 셋으로 접힌다`(): Unit = runBlocking {
        val world = newWorld()
        val battle = newScene(world, "TurnBattleScene")
        for (selector in SPLITTING_BATTLE_CONTROLS) newCapability(world, battle, selector)

        observeBattle(world)

        val rows = screens.findBySceneIdOrderByIdAsc(battle).toList()
        assertThat(rows).hasSize(3)
        assertThat(rows.map { row -> read(row.discriminator).map { it.second } }).containsExactly(
            listOf(false, false, false, false),
            listOf(true, false, true, true),
            listOf(true, true, true, true),
        )
        // 손패가 통째로 갈렸어도 평시 전투로 돌아온 것은 **재방문**이다.
        assertThat(rows.first().observedCount).isEqualTo(2)
    }

    /**
     * **Kotlin 과 SQL 이 같은 결과를 낸다.**
     *
     * 목록은 `discriminator` 를 만드는 Kotlin 과 소급 처리를 하는 SQL 양쪽에서 평가된다. 한쪽에서만
     * 맞는 항목이 하나 생기면 같은 화면이 두 `discriminator` 로 갈리고, `uk_screen_discriminator` 가
     * 막으려던 분열이 목록 쪽에서 다시 열린다.
     *
     * 실측 `TurnBattleScene` 의 selector 전부를 세 대상 · 세 출처 · 제외 항목이 섞인 목록에 통과시켜
     * 양쪽을 맞대 본다.
     */
    @Test
    fun `목록 적용이 Kotlin 과 SQL 에서 같은 결과를 낸다`(): Unit = runBlocking {
        val world = newWorld()
        val battle = newScene(world, "TurnBattleScene")

        val rules = listOf(
            Triple(ScreenSelectorMatch.SELECTOR, "DebugCanvas[4]/TurnEndButton[0]", ScreenSelectorSource.STATIC_ANALYSIS) to true,
            Triple(ScreenSelectorMatch.SELECTOR, "CombineSystem[7]/CombineButton[0]", ScreenSelectorSource.STATIC_ANALYSIS) to true,
            Triple(ScreenSelectorMatch.PATH, "Card(Clone)", ScreenSelectorSource.AGENT) to true,
            Triple(ScreenSelectorMatch.PATH, "Word", ScreenSelectorSource.HUMAN) to false,
            Triple(ScreenSelectorMatch.SUBTREE, "CombineSystem/CombineZone", ScreenSelectorSource.AGENT) to true,
            // 넓은 항목에 낸 구멍. 같은 출처 안에서 좁은 것이 이긴다.
            Triple(ScreenSelectorMatch.PATH, "CombineSystem/CombineZone/Zone2", ScreenSelectorSource.AGENT) to false,
            // 사람이 agent 를 이긴다. `Zone1` 은 다시 들어온다.
            Triple(ScreenSelectorMatch.PATH, "CombineSystem/CombineZone/Zone1", ScreenSelectorSource.AGENT) to false,
            Triple(ScreenSelectorMatch.PATH, "CombineSystem/CombineZone/Zone1", ScreenSelectorSource.HUMAN) to true,
            // 어느 것에도 안 맞는 항목. 아무 일도 하지 않아야 한다.
            Triple(ScreenSelectorMatch.SUBTREE, "CombineSystem/CombineZone/Zone", ScreenSelectorSource.HUMAN) to true,
        )
        for ((target, defining) in rules) {
            val (match, pattern, source) = target
            screenSelectors.save(
                SceneScreenSelectorEntity(
                    sceneId = battle,
                    matchKind = match.wire,
                    pattern = pattern,
                    source = source.wire,
                    screenDefining = defining,
                )
            )
        }

        val whitelist = ScreenSelectorWhitelist(
            screenSelectors.findBySceneIdOrderByIdAsc(battle).toList().mapNotNull { it.toRule() }
        )

        val disagreed = OBSERVED_BATTLE_SELECTORS.filter { selector ->
            whitelist.defines(selector) != screenDefiningInSql(battle, selector)
        }
        assertThat(disagreed).isEmpty()
        // 양쪽이 "전부 false" 로 사이좋게 틀리는 것도 통과할 수 있으니, 실제로 갈렸는지 본다.
        assertThat(OBSERVED_BATTLE_SELECTORS.filter { whitelist.defines(it) })
            .isNotEmpty
            .doesNotContain("Word[12]", "CombineSystem[7]/CombineZone[1]/Zone2[1]")
            .contains("CombineSystem[7]/CombineZone[1]/Zone1[0]", "Card(Clone)[37]")
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

    /**
     * `pulse` 한 장. 실린 객체는 켜짐/꺼짐과 무관하게 전부 `offers` 를 단다 — 실측에서 스폰된 적은
     * 꺼진 채로도 SDK 가 조작 가능하다고 광고했다. 목록 밖이면 그래도 `discriminator` 에 안 들어간다.
     */
    private fun offeringPulse(scene: String, objects: List<Pair<String, Boolean>>): String {
        val active = objects.filter { it.second }.joinToString(",") { offering(it.first) }
        val deactive = objects.filterNot { it.second }.joinToString(",") { offering(it.first) }
        return """
            {"type":"PULSE","schema":2,"scene":"$scene","whole":true,
             "active":[$active],"deactive":[$deactive]}
        """.trimIndent()
    }

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

    /** 사람이 이 selector 를 화면 판정에서 뺀다. `screen_defining=false` 가 명시적 제외다. */
    private suspend fun excludeByHuman(sceneId: Long, selector: String) {
        screenSelectors.save(
            SceneScreenSelectorEntity(
                sceneId = sceneId,
                matchKind = ScreenSelectorMatch.SELECTOR.wire,
                pattern = selector,
                source = ScreenSelectorSource.HUMAN.wire,
                screenDefining = false,
            )
        )
    }

    /** SQL 쪽 평가. `V60` 이 만든 함수를 그대로 부른다. */
    private suspend fun screenDefiningInSql(sceneId: Long, selector: String): Boolean =
        db.sql("SELECT screen_defining_selector(:sceneId, :selector) AS defining")
            .bind("sceneId", sceneId)
            .bind("selector", selector)
            .map { row, _ -> row.get("defining", java.lang.Boolean::class.java)!!.booleanValue() }
            .one()
            .block()!!

    /**
     * 실측 `TurnBattleScene` 한 바퀴 (`artel_integration`, 2026-08-28).
     *
     * 평시 전투 → combine panel 열림 → combine 확정 가능 → 다시 평시 전투. 손패와 적 인스턴스는
     * 매번 완전히 다른 index 로 갈아탄다.
     */
    private suspend fun observeBattle(world: World) {
        observeTwice(world, battlePulse(combineZone = false, confirm = false, cardIndices = listOf(16, 17, 31, 32, 33, 34, 35, 36), enemyBase = 21))
        observeTwice(world, battlePulse(combineZone = true, confirm = false, cardIndices = listOf(37, 38, 39), enemyBase = 28))
        observeTwice(world, battlePulse(combineZone = true, confirm = true, cardIndices = listOf(40), enemyBase = 35))
        observeTwice(world, battlePulse(combineZone = false, confirm = false, cardIndices = (41..50).toList(), enemyBase = 42))
    }

    /**
     * 실측 `TurnBattleScene` 한 장.
     *
     * selector 는 실제 행에서 그대로 가져왔고, 흔드는 것은 실제로 흔들렸던 것만이다 — 손패 카드의
     * index, 적 인스턴스의 index, 그리고 combine panel 의 세 상태. 적이 대부분 꺼진 채인 것도 실측
     * 그대로다: 스폰되자마자 풀에 들어가고, `BossFlower(Clone)` 은 한 번도 켜진 적이 없다.
     */
    private fun battlePulse(
        combineZone: Boolean,
        confirm: Boolean,
        cardIndices: List<Int>,
        enemyBase: Int,
    ): String {
        val controls = listOf(
            "CardSystem[6]/CardManager[3]" to true,
            "CombineSystem[7]/CombineButton[0]" to true,
            "CombineSystem[7]/CombineZone[1]" to combineZone,
            "CombineSystem[7]/CombineZone[1]/Button[2]" to confirm,
            "CombineSystem[7]/CombineZone[1]/Zone1[0]" to combineZone,
            "CombineSystem[7]/CombineZone[1]/Zone2[1]" to combineZone,
            "DebugCanvas[4]/TurnEndButton[0]" to true,
            "Word[12]" to true,
        )
        val cards = cardIndices.map { "Card(Clone)[$it]" to true }
        val enemies = (enemyBase until enemyBase + 7).flatMap { index ->
            listOf(
                "MeleeRock(Clone)[$index]" to (index == enemyBase),
                "RangedCat(Clone)[$index]" to false,
                "BossFlower(Clone)[$index]" to false,
            )
        }
        return offeringPulse("TurnBattleScene", controls + cards + enemies)
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

    /** 실측 빌드에서 `TurnBattleScene` 의 capability 가 지목한 컨트롤. 목록의 씨앗이 이 셋이다. */
    private val SEEDED_BATTLE_CONTROLS = listOf(
        "CombineSystem[7]/CombineButton[0]",
        "CombineSystem[7]/CombineZone[1]/Button[2]",
        "DebugCanvas[4]/TurnEndButton[0]",
    )

    /**
     * 실측 화면 29행에서 **실제로 화면을 가른** selector 넷. 29행 전부에 실려 있으면서 `active` 가
     * 행마다 달랐던 것이 이 넷뿐이다.
     */
    private val SPLITTING_BATTLE_CONTROLS = listOf(
        "CombineSystem[7]/CombineZone[1]",
        "CombineSystem[7]/CombineZone[1]/Button[2]",
        "CombineSystem[7]/CombineZone[1]/Zone1[0]",
        "CombineSystem[7]/CombineZone[1]/Zone2[1]",
    )

    /** 실측 `TurnBattleScene` 화면 29행의 `discriminator` 에 등장한 selector 전부. */
    private val OBSERVED_BATTLE_SELECTORS = listOf(
        "BossFlower(Clone)[26]",
        "BossFlower(Clone)[27]",
        "BossFlower(Clone)[28]",
        "BossFlower(Clone)[29]",
        "BossFlower(Clone)[30]",
        "BossFlower(Clone)[31]",
        "BossFlower(Clone)[32]",
        "Card(Clone)[16]",
        "Card(Clone)[17]",
        "Card(Clone)[31]",
        "Card(Clone)[32]",
        "Card(Clone)[33]",
        "Card(Clone)[34]",
        "Card(Clone)[35]",
        "Card(Clone)[36]",
        "Card(Clone)[37]",
        "Card(Clone)[38]",
        "Card(Clone)[39]",
        "Card(Clone)[40]",
        "Card(Clone)[41]",
        "Card(Clone)[42]",
        "Card(Clone)[43]",
        "Card(Clone)[44]",
        "Card(Clone)[45]",
        "Card(Clone)[46]",
        "Card(Clone)[47]",
        "Card(Clone)[48]",
        "CardSystem[6]/CardManager[3]",
        "CombineSystem[7]/CombineButton[0]",
        "CombineSystem[7]/CombineZone[1]",
        "CombineSystem[7]/CombineZone[1]/Button[2]",
        "CombineSystem[7]/CombineZone[1]/Zone1[0]",
        "CombineSystem[7]/CombineZone[1]/Zone2[1]",
        "DebugCanvas[4]/TurnEndButton[0]",
        "MeleeRock(Clone)[21]",
        "MeleeRock(Clone)[22]",
        "MeleeRock(Clone)[23]",
        "MeleeRock(Clone)[24]",
        "MeleeRock(Clone)[25]",
        "MeleeRock(Clone)[26]",
        "MeleeRock(Clone)[27]",
        "RangedCat(Clone)[16]",
        "RangedCat(Clone)[17]",
        "RangedCat(Clone)[18]",
        "RangedCat(Clone)[19]",
        "RangedCat(Clone)[20]",
        "RangedCat(Clone)[21]",
        "RangedCat(Clone)[22]",
        "Word[12]",
    )

    private fun newUser(): Long =
        db.sql("INSERT INTO app_user (display_name, nickname, user_tag) VALUES ('screen', 'screen-' || gen_random_uuid(), '0000') RETURNING id")
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().block()!!
}
