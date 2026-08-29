package kr.artel.orchestration.contentmap

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.contentmap.entity.Actionability
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.entity.CapabilityObservationEntity
import kr.artel.orchestration.contentmap.entity.CapabilityOrigin
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.InputPhase
import kr.artel.orchestration.contentmap.entity.Interaction
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.observe.CapabilityObservationService
import kr.artel.orchestration.contentmap.observe.ObservedEffect
import kr.artel.orchestration.contentmap.observe.ObservedEffectKind
import kr.artel.orchestration.contentmap.observe.ScreenObservationService
import kr.artel.orchestration.contentmap.observe.observedEffectOf
import kr.artel.orchestration.contentmap.repository.CapabilityObservationRepository
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaRunEntity
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.sdk.dto.ActionItemDto
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
 * 액션과 `pulse` 를 시간축으로 붙여 관측을 남긴다 (ARTEL-450).
 *
 * 이 파일이 지키는 것은 **무엇을 이 액션의 결과로 볼 것인가** 하나다. `ActionTimeline` 의 규칙
 * 다섯이 그 답이고, 규칙마다 그것이 깨졌을 때 무엇이 잘못되는지가 테스트 이름에 있다.
 *
 * 값이 아니라 규칙을 고정한다. 특히 **배경 빼기**(규칙 5)가 이 기능의 존재 이유에 가장 가깝다 —
 * 실측 `pulse` 14,489 개 전부가 무언가 달라졌다고 말했으므로, 그것을 빼지 않으면 `fired` 는
 * 언제나 참이고 아무 뜻도 없다.
 */
@ActiveProfiles("test")
@SpringBootTest
class CapabilityObservationTest {

    @Autowired private lateinit var screenObservation: ScreenObservationService
    @Autowired private lateinit var observations: CapabilityObservationService
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var gameInstances: GameInstanceRepository
    @Autowired private lateinit var testRuns: TestRunRepository
    @Autowired private lateinit var qaRuns: QaRunRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var scenes: SceneRepository
    @Autowired private lateinit var capabilities: CapabilityRepository
    @Autowired private lateinit var rows: CapabilityObservationRepository
    @Autowired private lateinit var db: DatabaseClient

    /**
     * Spring 이 든 매퍼를 그대로 쓴다. `ObjectMapper()` 를 새로 만들면 Kotlin 모듈이 없어
     * data class 를 되읽지 못하고, 저장은 되는데 읽기만 깨지는 테스트가 된다.
     */
    @Autowired private lateinit var objectMapper: ObjectMapper

    // ---------- 규칙 5: 배경은 뺀다 ----------

    /**
     * **이 기능이 조용히 쓸모없어지는 자리다.**
     *
     * 실측에서 `pulse` 14,489 개 전부가 `changed` 를 비우지 않은 채 왔고, 적 애니메이터 selector
     * 다섯 개가 그 변화의 2 만 건을 차지한다. "뭔가 달라졌다"를 `fired` 로 읽으면 눌러 본 모든
     * 기능이 동작한 것이 되고, ARTEL-451 이 그것으로 전부를 `confirmed` 로 올린다.
     */
    @Test
    fun `액션 전후로 계속 흔들리던 것은 fired 로 세지 않는다`(): Unit = runBlocking {
        val world = newWorld()
        val battle = newScene(world, BATTLE)
        newClickCapability(world, battle, TURN_END)

        // 액션 전: 애니메이터만 흔들린다.
        repeat(4) { screenObservation.observe(world.instanceId, battlePulse(it + 1L, ANIMATOR)) }
        click(world, requestId = 1, instanceId = TURN_END_ID)
        // 액션 후: 여전히 애니메이터만 흔들린다.
        repeat(4) { screenObservation.observe(world.instanceId, battlePulse(it + 5L, ANIMATOR)) }

        val observation = rows.findByQaRunIdOrderByActedAtAsc(world.qaRunId).toList().single()
        assertThat(observation.fired).isFalse
        assertThat(effectsOf(observation)).isEmpty()
    }

    /** 액션 뒤에만 나타난 변화는 남는다. 그것이 `observed_effects` 이고 ARTEL-451 의 재료다. */
    @Test
    fun `액션 뒤에만 나타난 변화가 fired 로 남는다`(): Unit = runBlocking {
        val world = newWorld()
        val battle = newScene(world, BATTLE)
        newClickCapability(world, battle, TURN_END)

        repeat(4) { screenObservation.observe(world.instanceId, battlePulse(it + 1L, ANIMATOR)) }
        click(world, requestId = 1, instanceId = TURN_END_ID)
        repeat(4) { screenObservation.observe(world.instanceId, battlePulse(it + 5L, ANIMATOR, TURN_CHANGED)) }

        val observation = rows.findByQaRunIdOrderByActedAtAsc(world.qaRunId).toList().single()
        assertThat(observation.fired).isTrue
        assertThat(effectsOf(observation)).containsExactly(
            ObservedEffect(
                kind = ObservedEffectKind.MEMBER,
                target = "Battle.Turns.TurnBattleSystem",
                detail = "currentTurn",
                on = "$BATTLE/DebugCanvas[4]/TurnEndButton[0]",
            )
        )
    }

    // ---------- 컨트롤 하나에 기능이 여럿 ----------

    /**
     * 실측 `Canvas[2]/continue[2]` 뒤에 기능 다섯 행이 있다. **클릭 한 번이 어느 갈래를 탔는지는
     * `pulse` 가 말하지 않으므로** 전부에 한 행씩 남긴다.
     *
     * 여기서 하나를 골라 집으면 "안다"와 "여럿 중 하나를 골랐다"가 구분되지 않는다. 가르는 것은
     * 관측된 효과를 기대와 맞대는 ARTEL-451 이다.
     */
    @Test
    fun `한 컨트롤 뒤의 기능 전부에 관측 행이 하나씩 생긴다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, TITLE)
        val branches = List(3) { newClickCapability(world, title, CONTINUE) }
        newClickCapability(world, title, "Canvas[2]/ExitButton[3]")

        repeat(4) { screenObservation.observe(world.instanceId, titlePulse(it + 1L)) }
        click(world, requestId = 1, instanceId = CONTINUE_ID)
        repeat(4) { screenObservation.observe(world.instanceId, titlePulse(it + 5L)) }

        val recorded = rows.findByQaRunIdOrderByActedAtAsc(world.qaRunId).toList()
        assertThat(recorded.map { it.capabilityId }).containsExactlyInAnyOrderElementsOf(branches)
        assertThat(recorded.map { it.actionMethod }.distinct()).containsExactly("button_click")
    }

    /** 지도가 모르는 컨트롤을 눌렀다. **오류가 아니라 근거의 구멍이다** — 행은 안 남는다. */
    @Test
    fun `지도가 모르는 컨트롤은 관측 행을 안 남긴다`(): Unit = runBlocking {
        val world = newWorld()
        newScene(world, TITLE)

        repeat(4) { screenObservation.observe(world.instanceId, titlePulse(it + 1L)) }
        click(world, requestId = 1, instanceId = CONTINUE_ID)
        repeat(4) { screenObservation.observe(world.instanceId, titlePulse(it + 5L)) }

        assertThat(rows.findByQaRunIdOrderByActedAtAsc(world.qaRunId).toList()).isEmpty()
    }

    // ---------- 규칙 1: 겨눈 것이 이름으로 남은 액션만 ----------

    /**
     * 좌표만 받는 액션은 관측을 만들지 않는다. **실측 한 런의 액션 394 개 중 302 개가 이것이다.**
     *
     * 좌표 위에 무엇이 있었는지를 우리가 추측해 적으면, 그 추측이 그대로 다음 이슈의 승격 근거가
     * 된다. 이 기능이 낼 수 있는 가장 비싼 오류가 그것이다.
     */
    @Test
    fun `좌표만 받는 액션은 관측을 만들지 않는다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, TITLE)
        newClickCapability(world, title, CONTINUE)

        repeat(4) { screenObservation.observe(world.instanceId, titlePulse(it + 1L)) }
        observations.dispatched(
            world.instanceId,
            requestId = 1,
            actions = listOf(ActionItemDto(id = 1, method = "move_mouse", params = listOf(835, 646))),
        )
        observations.settled(world.instanceId, requestId = 1, succeeded = true)
        repeat(4) { screenObservation.observe(world.instanceId, titlePulse(it + 5L)) }

        assertThat(rows.findByQaRunIdOrderByActedAtAsc(world.qaRunId).toList()).isEmpty()
    }

    /** 키 입력은 `capability.input_key` 로 붙는다. `any` 는 근거가 키를 지목하지 않은 조작이라 어느 키에도 맞는다. */
    @Test
    fun `키 입력이 input_key 로 붙고 any 는 어느 키에도 맞는다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, TITLE)
        val space = newKeyCapability(world, title, "Space")
        val anyKey = newKeyCapability(world, title, Interaction.ANY_INPUT_KEY)
        newKeyCapability(world, title, "UpArrow")

        repeat(4) { screenObservation.observe(world.instanceId, titlePulse(it + 1L)) }
        observations.dispatched(
            world.instanceId,
            requestId = 1,
            actions = listOf(ActionItemDto(id = 1, method = "key_click", params = listOf("Space", 0.1))),
        )
        observations.settled(world.instanceId, requestId = 1, succeeded = true)
        repeat(4) { screenObservation.observe(world.instanceId, titlePulse(it + 5L)) }

        val recorded = rows.findByQaRunIdOrderByActedAtAsc(world.qaRunId).toList()
        assertThat(recorded.map { it.capabilityId }).containsExactlyInAnyOrder(space, anyKey)
    }

    // ---------- 규칙 2: SDK 가 받았다고 답한 액션만 ----------

    /**
     * 거절당한 액션은 게임에 **닿지도 못했다.** 그것을 `fired=false` 로 적으면 "버튼이 아무 일도
     * 안 했다"가 되어, ARTEL-451 이 멀쩡한 기능을 `contradicted` 로 내린다.
     *
     * 대신 다음 성공의 `attempts` 가 오른다 — 힌트가 나쁜 자리가 그렇게 드러난다.
     */
    @Test
    fun `거절당한 액션은 관측 대신 다음 성공의 attempts 를 올린다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, TITLE)
        newClickCapability(world, title, CONTINUE)

        repeat(4) { screenObservation.observe(world.instanceId, titlePulse(it + 1L)) }
        observations.dispatched(world.instanceId, requestId = 1, actions = listOf(clickOn(CONTINUE_ID)))
        observations.settled(world.instanceId, requestId = 1, succeeded = false)
        repeat(4) { screenObservation.observe(world.instanceId, titlePulse(it + 5L)) }
        assertThat(rows.findByQaRunIdOrderByActedAtAsc(world.qaRunId).toList()).isEmpty()

        click(world, requestId = 2, instanceId = CONTINUE_ID)
        repeat(4) { screenObservation.observe(world.instanceId, titlePulse(it + 9L)) }

        val observation = rows.findByQaRunIdOrderByActedAtAsc(world.qaRunId).toList().single()
        assertThat(observation.attempts).isEqualTo(2)
    }

    /** 답이 없으면 관측도 없다. 못 읽은 답을 성공으로 읽으면 닿지도 못한 조작의 관측이 생긴다. */
    @Test
    fun `답이 안 온 액션은 관측을 만들지 않는다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, TITLE)
        newClickCapability(world, title, CONTINUE)

        repeat(4) { screenObservation.observe(world.instanceId, titlePulse(it + 1L)) }
        observations.dispatched(world.instanceId, requestId = 1, actions = listOf(clickOn(CONTINUE_ID)))
        repeat(8) { screenObservation.observe(world.instanceId, titlePulse(it + 5L)) }

        assertThat(rows.findByQaRunIdOrderByActedAtAsc(world.qaRunId).toList()).isEmpty()
    }

    // ---------- 규칙 3: 창은 배타적이다 ----------

    /**
     * 액션 B 가 A 의 창 안에서 나가면 그 구간의 변화는 원인이 둘이라 가릴 수 없다. **A 는 관측을
     * 못 남긴다.**
     *
     * 남기면 B 가 일으킨 변화가 A 의 fired 로 적히고, 그것이 그대로 A 의 승격 근거가 된다.
     */
    @Test
    fun `창이 닫히기 전에 다음 액션이 나가면 앞선 액션은 관측을 못 남긴다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, TITLE)
        newClickCapability(world, title, CONTINUE)
        newClickCapability(world, title, EXIT)

        repeat(4) { screenObservation.observe(world.instanceId, titlePulse(it + 1L)) }
        click(world, requestId = 1, instanceId = CONTINUE_ID)
        // 창(4 `pulse`)이 차기 전에 둘째 액션이 나간다.
        screenObservation.observe(world.instanceId, titlePulse(5L))
        click(world, requestId = 2, instanceId = EXIT_ID)
        repeat(4) { screenObservation.observe(world.instanceId, titlePulse(it + 6L)) }

        val recorded = rows.findByQaRunIdOrderByActedAtAsc(world.qaRunId).toList()
        assertThat(recorded).hasSize(1)
        assertThat(recorded.single().capabilityId)
            .isEqualTo(capabilities.findBySceneIdOrderByIdAsc(title).toList().last().id)
    }

    // ---------- 규칙 4: 창을 `reading` 번호로 적는다 ----------

    /**
     * 창의 경계는 우리 도착 시각이 아니라 SDK 의 순번으로 남는다.
     *
     * 실측에서 SDK 가 매긴 `reading` 이 30,290 까지 오르는 동안 우리가 받은 `pulse` 는 14,036 개였다 —
     * 절반이 전달 과정에서 사라진다. 우리 시각으로 적으면 나중에 그 구간을 다시 세는 사람이 무엇을
     * 봤는지 복원할 수 없다.
     */
    @Test
    fun `창의 경계가 SDK 의 reading 번호로 적힌다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, TITLE)
        newClickCapability(world, title, CONTINUE)

        // 우리가 받은 `pulse` 는 넷인데 SDK 순번은 둘씩 건너뛴다.
        for (reading in listOf(10L, 12L, 14L, 16L)) {
            screenObservation.observe(world.instanceId, titlePulse(reading))
        }
        click(world, requestId = 1, instanceId = CONTINUE_ID)
        for (reading in listOf(18L, 20L, 22L, 24L)) {
            screenObservation.observe(world.instanceId, titlePulse(reading))
        }

        val observation = rows.findByQaRunIdOrderByActedAtAsc(world.qaRunId).toList().single()
        assertThat(observation.readingBefore).isEqualTo(16L)
        assertThat(observation.readingAfter).isEqualTo(24L)
    }

    /** `pulse` 를 하나도 못 담은 창은 버린다. 본 것이 없으면 "아무 일도 없었다"고 말할 수 없다. */
    @Test
    fun `창에 pulse 가 하나도 안 들어오면 관측을 만들지 않는다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, TITLE)
        newClickCapability(world, title, CONTINUE)

        repeat(4) { screenObservation.observe(world.instanceId, titlePulse(it + 1L)) }
        click(world, requestId = 1, instanceId = CONTINUE_ID)
        // `pulse` 없이 다음 액션이 나간다.
        click(world, requestId = 2, instanceId = CONTINUE_ID)
        repeat(4) { screenObservation.observe(world.instanceId, titlePulse(it + 5L)) }

        assertThat(rows.findByQaRunIdOrderByActedAtAsc(world.qaRunId).toList()).hasSize(1)
    }

    // ---------- 조준 해석 ----------

    /**
     * instance id 는 `pulse` 가 이름을 붙여 주기 전에는 지도가 모르는 숫자다. 본 적 없는 번호에
     * 조준을 지어내지 않는다.
     */
    @Test
    fun `pulse 가 본 적 없는 instance id 는 조준으로 안 읽는다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, TITLE)
        newClickCapability(world, title, CONTINUE)

        repeat(4) { screenObservation.observe(world.instanceId, titlePulse(it + 1L)) }
        click(world, requestId = 1, instanceId = 999_999)
        repeat(4) { screenObservation.observe(world.instanceId, titlePulse(it + 5L)) }

        assertThat(rows.findByQaRunIdOrderByActedAtAsc(world.qaRunId).toList()).isEmpty()
    }

    /** 관측은 액션이 나갈 때의 씬에 붙는다. 씬을 넘긴 조작이 도착한 씬의 기능에 달리면 안 된다. */
    @Test
    fun `씬을 넘긴 조작도 나갈 때의 씬 기능에 붙는다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, TITLE)
        val map = newScene(world, MAP)
        val continueId = newClickCapability(world, title, CONTINUE)
        newClickCapability(world, map, CONTINUE)

        repeat(4) { screenObservation.observe(world.instanceId, titlePulse(it + 1L)) }
        click(world, requestId = 1, instanceId = CONTINUE_ID)
        repeat(4) { screenObservation.observe(world.instanceId, mapPulse(it + 5L)) }

        val observation = rows.findByQaRunIdOrderByActedAtAsc(world.qaRunId).toList().single()
        assertThat(observation.capabilityId).isEqualTo(continueId)
        assertThat(effectsOf(observation))
            .contains(ObservedEffect(ObservedEffectKind.SCENE, target = MAP, detail = TITLE))
    }

    // ---------- `changed` 읽기 ----------

    /**
     * `changed` 항목의 네 모양을 `capability_effect` 와 맞댈 수 있는 칸으로 옮긴다.
     *
     * **모르는 모양을 흘리지 않는 것**이 이 테스트의 절반이다. 흘리면 `fired=true` 인데 효과가 빈
     * 행이 생기고, 그것을 본 사람은 우리 파서가 아니라 게임을 의심한다.
     */
    @Test
    fun `changed 항목 네 모양을 효과로 읽는다`() {
        assertThat(observedEffectOf("scene", actedScene = TITLE, currentScene = MAP))
            .isEqualTo(ObservedEffect(ObservedEffectKind.SCENE, target = MAP, detail = TITLE))

        assertThat(observedEffectOf("$TITLE/$CONTINUE|active", TITLE, TITLE))
            .isEqualTo(ObservedEffect(ObservedEffectKind.OBJECT, target = "$TITLE/$CONTINUE", detail = "active"))

        assertThat(observedEffectOf("Battle.Turns.TurnBattleSystem::EnemyTurn", BATTLE, BATTLE))
            .isEqualTo(
                ObservedEffect(ObservedEffectKind.MEMBER, target = "Battle.Turns.TurnBattleSystem", detail = "EnemyTurn")
            )

        assertThat(observedEffectOf("$BATTLE/$TURN_END|Combat.Enemies.Enemy::Hp", BATTLE, BATTLE))
            .isEqualTo(
                ObservedEffect(
                    kind = ObservedEffectKind.MEMBER,
                    target = "Combat.Enemies.Enemy",
                    detail = "Hp",
                    on = "$BATTLE/$TURN_END",
                )
            )

        // 모르는 모양. 통째로 남는다.
        assertThat(observedEffectOf("무엇인가", TITLE, TITLE))
            .isEqualTo(ObservedEffect(ObservedEffectKind.OBJECT, target = "무엇인가"))
    }

    // ---------- 픽스처 ----------

    private data class World(val instanceId: Long, val contentMapId: Long, val qaRunId: Long)

    private suspend fun click(world: World, requestId: Long, instanceId: Long) {
        observations.dispatched(world.instanceId, requestId, listOf(clickOn(instanceId)))
        observations.settled(world.instanceId, requestId, succeeded = true)
    }

    private fun clickOn(instanceId: Long) =
        ActionItemDto(id = instanceId, method = "button_click", params = listOf(instanceId))

    private fun effectsOf(observation: CapabilityObservationEntity): List<ObservedEffect> =
        objectMapper.readValue(
            observation.observedEffects.asString(),
            objectMapper.typeFactory.constructCollectionType(List::class.java, ObservedEffect::class.java),
        )

    /**
     * `TitleScene` 한 장. 실측 프레임에서 selector 와 instance id 를 그대로 가져왔다.
     *
     * 매 `pulse` 가 `changed` 를 실어 온다 — 실측 14,489 개 전부가 그랬다. 그 사실이 곧 배경 빼기가
     * 필요한 이유이므로 픽스처도 그렇게 둔다.
     */
    private fun titlePulse(reading: Long): String = pulse(
        scene = TITLE,
        reading = reading,
        changed = listOf("$TITLE/$CONTINUE|world", "$TITLE/$EXIT|world"),
        objects = listOf(CONTINUE_ID to CONTINUE, EXIT_ID to EXIT),
    )

    private fun mapPulse(reading: Long): String = pulse(
        scene = MAP,
        reading = reading,
        changed = listOf("scene", "$MAP/Canvas[7]/Button (Legacy)[0]|active"),
        objects = listOf(3001L to "Canvas[7]/Button (Legacy)[0]"),
    )

    /** `TurnBattleScene` 한 장. [changed] 로 무엇이 흔들리는지를 테스트가 정한다. */
    private fun battlePulse(reading: Long, vararg changed: String): String = pulse(
        scene = BATTLE,
        reading = reading,
        changed = changed.toList(),
        objects = listOf(TURN_END_ID to TURN_END),
    )

    private fun pulse(
        scene: String,
        reading: Long,
        changed: List<String>,
        objects: List<Pair<Long, String>>,
    ): String {
        val active = objects.joinToString(",") { (id, selector) ->
            """{"id":$id,"selector":"$selector","path":"$selector"}"""
        }
        val changedJson = changed.joinToString(",") { "\"$it\"" }
        return """
            {"type":"PULSE","schema":2,"scene":"$scene","whole":true,"reading":$reading,
             "changed":[$changedJson],"active":[$active],"deactive":[]}
        """.trimIndent()
    }

    private suspend fun newWorld(): World {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(name = "observe-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
        )
        val build = gameBuilds.save(
            GameBuildEntity(projectId = project.id!!, version = "v${System.nanoTime()}", createdAt = now, updatedAt = now)
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
                evidenceDigest = "capability-observation",
            )
        )
        val testRunId = testRuns.save(TestRunEntity(projectId = project.id!!, name = "런")).id!!
        val qaRun = qaRuns.save(
            QaRunEntity(
                testRunId = testRunId,
                gameInstanceId = instance.id!!,
                startedBy = newUser(),
                status = "RUNNING",
                startedAt = now,
            )
        )
        return World(instance.id!!, contentMap.id!!, qaRun.id!!)
    }

    private suspend fun newScene(world: World, name: String): Long =
        scenes.save(SceneEntity(contentMapId = world.contentMapId, name = name, walked = true)).id!!

    private suspend fun newClickCapability(world: World, sceneId: Long, selector: String): Long =
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

    private suspend fun newKeyCapability(world: World, sceneId: Long, key: String): Long =
        capabilities.save(
            CapabilityEntity(
                sceneId = sceneId,
                contentMapId = world.contentMapId,
                origin = CapabilityOrigin.OBSERVED.wire,
                summary = "$key 를 누른다",
                interaction = Interaction.PRESS.wire,
                inputKey = key,
                inputPhase = InputPhase.DOWN.wire,
                actionability = Actionability.RUNNABLE.wire,
            )
        ).id!!

    private fun newUser(): Long =
        db.sql("INSERT INTO app_user (display_name) VALUES ('observe') RETURNING id")
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().block()!!

    private companion object {
        const val TITLE = "TitleScene"
        const val MAP = "Map_scene"
        const val BATTLE = "TurnBattleScene"

        const val CONTINUE = "Canvas[2]/continue[2]"
        const val CONTINUE_ID = 32562L
        const val EXIT = "Canvas[2]/ExitButton[3]"
        const val EXIT_ID = 32622L
        const val TURN_END = "DebugCanvas[4]/TurnEndButton[0]"
        const val TURN_END_ID = 40100L

        /** 실측에서 변화의 2 만 건을 차지한 적 애니메이터. 아무도 안 눌러도 매 `pulse` 흔들린다. */
        const val ANIMATOR =
            "$BATTLE/MeleeRock(Clone)[22]|Combat.Enemies.SlimeAnimator::spriteRenderer"

        /** 턴 종료가 실제로 바꾸는 것. 액션 뒤에만 나타난다. */
        const val TURN_CHANGED =
            "$BATTLE/$TURN_END|Battle.Turns.TurnBattleSystem::currentTurn"
    }
}
