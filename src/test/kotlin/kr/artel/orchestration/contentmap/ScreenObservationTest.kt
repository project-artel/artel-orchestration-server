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

    // ---------- collection family (ARTEL-649) ----------

    /**
     * **실측을 못으로 박는다.** `artel_integration` 의 `TurnBattleScene` 화면 29행은 같은 씬을 29번
     * 다르게 적은 것이었다. selector 도 combine panel 의 세 상태도 그 행들에서 그대로 가져왔다.
     *
     * 여기가 깨지면 collection family 판정이 좁아진 것이고, 그 결과는 화면 수가 실제 상태 수가 아니라
     * **플레이 길이**에 비례하는 것이다. 특히 "켜진 인스턴스만 센다"로 바꾸면 여기서 잡힌다 —
     * 적 셋은 동시 활성이 1을 넘은 적이 없어 그 규칙으로는 collection 이 아니고, 그러면 인덱스가
     * 바뀔 때마다 화면이 새로 앉는다(실측 재계산: 29행이 3이 아니라 12로만 접힌다).
     */
    @Test
    fun `실측 TurnBattleScene 이 화면 셋으로 접힌다`(): Unit = runBlocking {
        val world = newWorld()
        val battle = newScene(world, "TurnBattleScene")

        // 평시 전투 → combine panel 열림 → combine 확정 가능 → 다시 평시 전투.
        // 손패와 적 인스턴스는 매번 완전히 다른 인덱스로 갈아탄다.
        observeTwice(world, battlePulse(combineZone = false, confirm = false, cardIndices = listOf(16, 17, 31, 32, 33, 34, 35, 36), enemyBase = 21))
        observeTwice(world, battlePulse(combineZone = true, confirm = false, cardIndices = listOf(37, 38, 39), enemyBase = 28))
        observeTwice(world, battlePulse(combineZone = true, confirm = true, cardIndices = listOf(40), enemyBase = 35))
        observeTwice(world, battlePulse(combineZone = false, confirm = false, cardIndices = (41..50).toList(), enemyBase = 42))

        val rows = screens.findBySceneIdOrderByIdAsc(battle).toList()
        assertThat(rows).hasSize(3)

        // 남는 것은 화면당 정확히 하나씩인 singleton family 여덟뿐이다.
        assertThat(read(rows.first().discriminator).map { it.first }).containsExactly(
            "CardSystem[6]/CardManager[3]",
            "CombineSystem[7]/CombineButton[0]",
            "CombineSystem[7]/CombineZone[1]",
            "CombineSystem[7]/CombineZone[1]/Button[2]",
            "CombineSystem[7]/CombineZone[1]/Zone1[0]",
            "CombineSystem[7]/CombineZone[1]/Zone2[1]",
            "DebugCanvas[4]/TurnEndButton[0]",
            "Word[12]",
        )
        // 셋을 가르는 축은 combine panel 뿐이다.
        assertThat(rows.map { row -> read(row.discriminator).filter { it.first.startsWith("CombineSystem[7]/CombineZone") } })
            .containsExactly(
                listOf(
                    "CombineSystem[7]/CombineZone[1]" to false,
                    "CombineSystem[7]/CombineZone[1]/Button[2]" to false,
                    "CombineSystem[7]/CombineZone[1]/Zone1[0]" to false,
                    "CombineSystem[7]/CombineZone[1]/Zone2[1]" to false,
                ),
                listOf(
                    "CombineSystem[7]/CombineZone[1]" to true,
                    "CombineSystem[7]/CombineZone[1]/Button[2]" to false,
                    "CombineSystem[7]/CombineZone[1]/Zone1[0]" to true,
                    "CombineSystem[7]/CombineZone[1]/Zone2[1]" to true,
                ),
                listOf(
                    "CombineSystem[7]/CombineZone[1]" to true,
                    "CombineSystem[7]/CombineZone[1]/Button[2]" to true,
                    "CombineSystem[7]/CombineZone[1]/Zone1[0]" to true,
                    "CombineSystem[7]/CombineZone[1]/Zone2[1]" to true,
                ),
            )
        // 손패가 통째로 갈렸어도 평시 전투로 돌아온 것은 **재방문**이다.
        assertThat(rows.first().observedCount).isEqualTo(2)
    }

    /**
     * collection 판정은 `fold` 를 잃어도 남는다. **그 지식이 프로세스 메모리가 아니라 DB 에 산다.**
     *
     * `fold` 옆에 두면 런의 첫 `pulse` · 서버 재시작 · 서버 두 대 — 셋 다에서 같은 화면이 다른
     * `discriminator` 로 앉아 행이 갈린다. `screen` 의 식별 키(`uk_screen_discriminator`)가 런과
     * 프로세스를 넘어 사는 값이므로 그 값을 만드는 규칙도 그래야 한다.
     *
     * 두 번째 관측은 손패가 한 장뿐이라 **그 관측만 보면 `Card(Clone)` 은 singleton 이다.** 그런데도
     * 화면이 늘지 않아야 한다.
     */
    @Test
    fun `collection 판정이 fold 를 잃어도 남는다`(): Unit = runBlocking {
        val world = newWorld()
        val battle = newScene(world, "TurnBattleScene")

        observeTwice(world, battlePulse(combineZone = false, confirm = false, cardIndices = listOf(16, 17, 18), enemyBase = 21))

        val restarted = newInstance(world)
        observeTwice(restarted, battlePulse(combineZone = false, confirm = false, cardIndices = listOf(99), enemyBase = 70))

        assertThat(screens.findBySceneIdOrderByIdAsc(battle).toList()).hasSize(1)
    }

    /**
     * family 는 **경로 모든 마디**의 형제 인덱스를 지운 것이다.
     *
     * 마지막 마디만 지우면 `Card(Clone)[37]/Cost` 와 `Card(Clone)[38]/Cost` 가 서로 다른 family 가
     * 되고, 각자 인스턴스가 하나뿐이라 어느 쪽도 collection 으로 잡히지 않는다. 스폰되는 것이
     * 잎이라는 보장이 없으므로 그 규칙은 규칙이 있으나 마나가 된다.
     */
    @Test
    fun `스폰된 부모 아래의 자식도 한 collection family 다`(): Unit = runBlocking {
        val world = newWorld()
        val battle = newScene(world, "TurnBattleScene")

        val turnEnd = "DebugCanvas[4]/TurnEndButton[0]"
        observeTwice(
            world,
            offeringPulse(
                "TurnBattleScene",
                listOf(turnEnd to true, "Card(Clone)[37]/Cost[0]" to true, "Card(Clone)[38]/Cost[0]" to true),
            ),
        )
        observeTwice(
            world,
            offeringPulse(
                "TurnBattleScene",
                listOf(turnEnd to true, "Card(Clone)[39]/Cost[0]" to true, "Card(Clone)[40]/Cost[0]" to true),
            ),
        )

        val row = screens.findBySceneIdOrderByIdAsc(battle).toList().single()
        assertThat(read(row.discriminator)).containsExactly(turnEnd to true)
    }

    /**
     * collection 이 없는 씬은 `discriminator` 가 달라지지 않는다. selector 는 실측 `Map_scene` 행에서
     * 그대로 가져왔다.
     *
     * 이 규칙의 비용이 여기서 드러난다 — 비용이 0 이어야 한다. 화면을 가르던 컨트롤이 하나라도
     * 빠지면 켜짐/꺼짐이 다른 화면이 한 화면으로 뭉쳐 TC 가 절반씩 실패한다.
     */
    @Test
    fun `collection 이 없는 씬은 discriminator 가 달라지지 않는다`(): Unit = runBlocking {
        val world = newWorld()
        val map = newScene(world, "Map_scene")
        val enter = "Canvas[7]/Button (Legacy)[0]"

        observeTwice(world, offeringPulse("Map_scene", listOf(enter to true, "MapScene[1]" to true)))
        observeTwice(world, offeringPulse("Map_scene", listOf(enter to false, "MapScene[1]" to true)))

        val rows = screens.findBySceneIdOrderByIdAsc(map).toList()
        assertThat(rows).hasSize(2)
        assertThat(read(rows.first().discriminator)).containsExactly(enter to true, "MapScene[1]" to true)
        assertThat(read(rows.last().discriminator)).containsExactly(enter to false, "MapScene[1]" to true)
    }

    // ---------- 픽스처 ----------

    private data class World(
        val gameInstanceId: Long,
        val contentMapId: Long,
        val projectId: Long,
        val gameBuildId: Long,
    )

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
     * 꺼진 채로도 `discriminator` 에 들어와 있었다.
     */
    private fun offeringPulse(scene: String, objects: List<Pair<String, Boolean>>): String {
        val active = objects.filter { it.second }.joinToString(",") { offering(it.first) }
        val deactive = objects.filterNot { it.second }.joinToString(",") { offering(it.first) }
        return """
            {"type":"PULSE","schema":2,"scene":"$scene","whole":true,
             "active":[$active],"deactive":[$deactive]}
        """.trimIndent()
    }

    /**
     * 실측 `TurnBattleScene` 한 장 (`artel_integration`, 2026-08-28).
     *
     * selector 는 실제 행에서 그대로 가져왔고, 흔드는 것은 실제로 흔들렸던 것만이다 — 손패 카드의
     * 인덱스, 적 인스턴스의 인덱스, 그리고 combine panel 의 세 상태. 적이 대부분 꺼진 채인 것도
     * 실측 그대로다: 스폰되자마자 풀에 들어가고, `BossFlower(Clone)` 은 한 번도 켜진 적이 없다.
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
        return World(instance.id!!, contentMap.id!!, project.id!!, build.id!!)
    }

    /**
     * 같은 빌드·같은 지도를 보는 **다른 게임 인스턴스**. `fold` 는 인스턴스별이므로
     * (`ScreenObservationService.folds`) 여기서 시작하는 관측은 아무것도 모르는 `fold` 로 출발한다 —
     * 서버 재시작과 서버 두 대가 이쪽에서는 같은 모양이다.
     */
    private suspend fun newInstance(world: World): World {
        val now = Instant.now()
        val instance = gameInstances.save(
            GameInstanceEntity(
                projectId = world.projectId,
                name = "instance",
                platform = "UNITY",
                lastGameBuildId = world.gameBuildId,
                lastConnectedAt = now,
                createdAt = now,
                updatedAt = now,
            )
        )
        val testRunId = testRuns.save(TestRunEntity(projectId = world.projectId, name = "런")).id!!
        qaRuns.save(
            QaRunEntity(
                testRunId = testRunId,
                gameInstanceId = instance.id!!,
                startedBy = newUser(),
                status = "RUNNING",
                startedAt = now,
            )
        )
        return world.copy(gameInstanceId = instance.id!!)
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
