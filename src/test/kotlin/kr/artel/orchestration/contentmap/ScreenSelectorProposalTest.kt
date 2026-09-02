package kr.artel.orchestration.contentmap

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.contentmap.entity.Actionability
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.entity.CapabilityOrigin
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.Interaction
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.entity.ScreenSelectorSource
import kr.artel.orchestration.contentmap.observe.ScreenObservationService
import kr.artel.orchestration.contentmap.observe.ScreenSelectorFrames
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.contentmap.repository.SceneScreenSelectorRepository
import kr.artel.orchestration.contentmap.repository.ScreenRepository
import kr.artel.orchestration.contentmap.repository.ScreenSelectorProposalRepository
import kr.artel.orchestration.contentmap.repository.ScreenTransitionRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaRunEntity
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaAgentEnvelope
import kr.artel.orchestration.qa.service.QaAgentInboundRouter
import kr.artel.orchestration.qa.service.QaAgentPort
import kr.artel.orchestration.qa.service.QaAgentSession
import kr.artel.orchestration.qa.service.QaAgentSessionContext
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private const val SESSION_ID = "screen-selector-session"

/**
 * 목록에 없는 selector 를 물어보고, 답이 오면 같아지는 화면을 합친다 (ARTEL-655).
 *
 * 이 파일이 지키는 것은 넷이다.
 *
 * 1. **제안이 나가되 화면을 가르지 않는다** — 목록 밖 selector 는 물어볼 거리이지 판정 근거가
 *    아니다. 여기가 깨지면 ARTEL-654 가 멈춘 화면 폭발이 다시 열린다
 * 2. **한 번만 묻는다** — 없으면 카드를 뽑을 때마다 제안이 하나씩 나간다
 * 3. **합치기가 도착 순서에 의존하지 않는다** — 같은 답을 순서만 바꿔 적용한 결과가 같아야 한다.
 *    같은 런을 두 번 돌린 결과가 달라지는 것을 막는 것이 `uk_screen_discriminator` 설계 전체의
 *    이유이고, 그 재현성이 여기서 깨지면 합치기가 그것을 되돌린다
 * 4. **답이 안 와도 오늘과 똑같이 돈다** — 제안은 관측의 곁가지다
 */
@ActiveProfiles("test")
@SpringBootTest
class ScreenSelectorProposalTest {

    class RecordingAgentPort : QaAgentPort {
        val sent: MutableList<QaAgentEnvelope> = CopyOnWriteArrayList()

        override suspend fun createSession(
            context: QaAgentSessionContext,
            onMessage: suspend (QaAgentEnvelope) -> Unit,
            onDisconnect: suspend () -> Unit
        ): QaAgentSession = QaAgentSession(SESSION_ID)

        override suspend fun send(sessionId: String, envelope: QaAgentEnvelope) {
            sent += envelope
        }

        override suspend fun close(sessionId: String) = Unit
    }

    @TestConfiguration
    class StubConfig {
        @Bean
        @Primary
        fun recordingAgentPort(): QaAgentPort = RecordingAgentPort()
    }

    @Autowired private lateinit var observation: ScreenObservationService
    @Autowired private lateinit var inboundRouter: QaAgentInboundRouter
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var gameInstances: GameInstanceRepository
    @Autowired private lateinit var testRuns: TestRunRepository
    @Autowired private lateinit var testScenarios: TestScenarioRepository
    @Autowired private lateinit var qaRuns: QaRunRepository
    @Autowired private lateinit var qaTries: QaTryRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var scenes: SceneRepository
    @Autowired private lateinit var screenSelectors: SceneScreenSelectorRepository
    @Autowired private lateinit var capabilities: CapabilityRepository
    @Autowired private lateinit var screens: ScreenRepository
    @Autowired private lateinit var transitions: ScreenTransitionRepository
    @Autowired private lateinit var proposals: ScreenSelectorProposalRepository
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var agentPort: QaAgentPort
    @Autowired private lateinit var db: DatabaseClient

    private val recorder: RecordingAgentPort get() = agentPort as RecordingAgentPort

    @BeforeEach
    fun clearSent() {
        recorder.sent.clear()
    }

    // ---------- 제안이 나간다 ----------

    /**
     * **이 이슈가 존재하는 이유다.** 목록이 씨앗으로만 차고 아무것도 늘리지 않으면, 나중에 진짜로
     * 화면을 가르는 UI 가 나타나도 무시하고 서로 다른 두 화면이 한 행으로 뭉친다. 그리고 뭉치는
     * 것은 조용하다.
     *
     * 동시에 그 selector 가 화면을 **가르지는 않는다.** 제안은 물어보는 것이지 판정이 아니다.
     */
    @Test
    fun `목록 밖 selector 가 나타나면 제안이 나가고 화면은 안 갈린다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))
        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE, UNKNOWN)))

        assertThat(screens.findBySceneIdOrderByIdAsc(title).toList()).hasSize(1)

        val proposal = proposalsSent().single()
        assertThat(proposal.payload.path("reason").asText()).isEqualTo("unknown-selector")
        assertThat(proposal.payload.path("scene").path("name").asText()).isEqualTo("TitleScene")
        assertThat(candidateSelectors(proposal.payload)).containsExactly(UNKNOWN)
        // 답하는 쪽은 게임을 모른다. 지금 서 있는 화면과 그 `discriminator` 가 판단의 절반이다.
        assertThat(proposal.payload.path("current_screen").path("screen_id").asText()).isNotBlank
        assertThat(proposal.payload.path("changes").size()).isGreaterThan(0)
        // 물어본 기록이 남는다. 다시 묻지 않는 것이 이 행의 일이다.
        assertThat(proposals.findBySceneIdOrderByIdAsc(title).toList().map { it.selector })
            .containsExactly(UNKNOWN)
    }

    /** 후보의 통계 셋이 실린다. 없으면 답하는 쪽이 캡처 하나만 보고 판단해야 한다. */
    @Test
    fun `제안에 후보의 통계가 실린다`(): Unit = runBlocking {
        val world = newWorld()
        val battle = newScene(world, "TurnBattleScene")
        newCapability(world, battle, CONTINUE)

        observeTwice(world, whole("TurnBattleScene", active = listOf(CONTINUE)))
        observeTwice(
            world,
            whole("TurnBattleScene", active = listOf(CONTINUE, "Card(Clone)[37]", "Card(Clone)[38]")),
        )

        val candidate = proposalsSent().first().payload.path("candidates")
            .single { it.path("selector").asText() == "Card(Clone)[37]" }
        assertThat(candidate.path("path").asText()).isEqualTo("Card(Clone)")
        // 같은 경로의 인스턴스가 둘이다 — 스폰되는 것일 가능성을 이 숫자가 말한다.
        assertThat(candidate.path("instances_in_reading").asInt()).isEqualTo(2)
        assertThat(candidate.path("distinct_values_observed").asInt()).isEqualTo(2)
        assertThat(candidate.path("readings_seen_in_scene").asInt()).isGreaterThan(0)
        assertThat(candidate.path("in_whitelist").asBoolean()).isFalse
    }

    /**
     * **없으면 카드를 뽑을 때마다 제안이 하나씩 나간다.** 실측 `TurnBattleScene` 의 한 `pulse` 에
     * selector 가 62 개이고 그중 목록 밖이 59 개인데, `pulse` 는 초당 여러 번 온다.
     */
    @Test
    fun `같은 selector 는 다시 묻지 않는다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)

        repeat(4) {
            observeTwice(world, whole("TitleScene", active = listOf(CONTINUE, UNKNOWN)))
            observeTwice(world, whole("TitleScene", active = listOf(CONTINUE), deactive = listOf(UNKNOWN)))
        }

        assertThat(proposalsSent()).hasSize(1)
        assertThat(proposals.findBySceneIdOrderByIdAsc(title).toList()).hasSize(1)
    }

    /**
     * 답이 끝내 안 와도 화면 기록은 지금과 똑같이 돈다.
     *
     * 제안을 기다렸다가 화면을 앉히면 답이 늦거나 안 오는 동안 관측이 통째로 사라진다 — 행 없는
     * 지도보다 나중에 합쳐지는 행이 낫다.
     */
    @Test
    fun `답이 안 와도 화면 기록이 계속 된다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE, UNKNOWN)))
        observeTwice(world, whole("TitleScene", deactive = listOf(CONTINUE), active = listOf(UNKNOWN)))

        val rows = screens.findBySceneIdOrderByIdAsc(title).toList()
        assertThat(rows).hasSize(2)
        assertThat(rows.map { read(it.discriminator) }).containsExactly(
            listOf(CONTINUE to true),
            listOf(CONTINUE to false),
        )
    }

    // ---------- 답이 온다 ----------

    /**
     * 넣는 답은 **소급되지 않는다.** 이미 뭉쳐 있던 화면은 안 갈린다 — 그 값이 애초에
     * `discriminator` 에 안 들어갔으니 기록이 없어 복원할 수 없다. 다음 관측부터 갈린다.
     */
    @Test
    fun `넣는 답은 과거 화면을 안 가르고 다음 관측부터 갈린다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE, UNKNOWN)))
        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE), deactive = listOf(UNKNOWN)))
        val before = screens.findBySceneIdOrderByIdAsc(title).toList()
        assertThat(before).hasSize(1)

        answerProposal(world, entry(UNKNOWN, screenDefining = true))

        // 과거 행은 그대로다.
        assertThat(screens.findBySceneIdOrderByIdAsc(title).toList().map { it.id })
            .isEqualTo(before.map { it.id })
        assertThat(screenSelectors.findBySceneIdOrderByIdAsc(title).toList().map { it.pattern to it.source })
            .contains(UNKNOWN to ScreenSelectorSource.AGENT.wire)

        // 다음 관측부터 갈린다.
        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE, UNKNOWN)))
        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE), deactive = listOf(UNKNOWN)))
        assertThat(screens.findBySceneIdOrderByIdAsc(title).toList()).hasSize(3)
    }

    /**
     * 빼는 답은 소급해서 합친다. 지워야 할 값이 기록에 있기 때문이다.
     *
     * 합칠 때 `observed_count` 를 더하고, 두 화면이 한 화면이 되어 **전이가 아니게 된** 전이를
     * 지운다 — 런타임도 자기 자신으로 가는 전이는 남기지 않는다.
     */
    @Test
    fun `빼는 답이 같아지는 화면을 합치고 observed_count 를 더한다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)
        newCapability(world, title, SPINNER)

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE, SPINNER)))
        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE), deactive = listOf(SPINNER)))
        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE, SPINNER)))
        assertThat(screens.findBySceneIdOrderByIdAsc(title).toList()).hasSize(2)
        assertThat(transitionsOf(title)).isNotEmpty()

        val result = ruleFrame(world, entry(SPINNER, screenDefining = false))

        assertThat(result.path("folded_screens").asInt()).isEqualTo(1)
        val rows = screens.findBySceneIdOrderByIdAsc(title).toList()
        assertThat(rows).hasSize(1)
        assertThat(read(rows.single().discriminator)).containsExactly(CONTINUE to true)
        // 세 번 앉은 관측이 한 행에 모인다. 합쳐지는 행은 같은 화면을 다르게 적은 것이지 다른 관측이 아니다.
        assertThat(rows.single().observedCount).isEqualTo(3)
        // 두 화면이 한 화면이 되면 그 사이의 전이는 전이가 아니다.
        assertThat(transitionsOf(title)).isEmpty()
    }

    /**
     * **재현성을 못으로 박는다.**
     *
     * 도착한 순서대로 두 행씩 짝지어 합치면 답이 오는 순서가 최종 상태를 바꾸고, 같은 런을
     * 두 번 돌린 결과가 달라진다. 그것을 막으려고 합치기를 "목록을 적용한 뒤 같아지는 것끼리 묶는"
     * 집합 연산으로 두었고, 이 테스트가 그 성질을 확인한다.
     *
     * 같은 답 셋을 여섯 가지 순서로 적용해 최종 상태를 통째로 맞대 본다.
     */
    @Test
    fun `답이 어떤 순서로 와도 최종 상태가 같다`(): Unit = runBlocking {
        val answers = listOf(
            entry(SPINNER, screenDefining = false),
            entry(BADGE, screenDefining = false),
            entry(UNKNOWN, screenDefining = true),
        )
        val outcomes = permutations(answers).map { order ->
            val world = newWorld()
            val title = newScene(world, "TitleScene")
            for (selector in listOf(CONTINUE, SPINNER, BADGE)) newCapability(world, title, selector)

            observeTwice(world, whole("TitleScene", active = listOf(CONTINUE, SPINNER, BADGE)))
            observeTwice(world, whole("TitleScene", active = listOf(CONTINUE, BADGE), deactive = listOf(SPINNER)))
            observeTwice(world, whole("TitleScene", active = listOf(CONTINUE), deactive = listOf(SPINNER, BADGE)))
            observeTwice(world, whole("TitleScene", active = listOf(CONTINUE, SPINNER, BADGE, UNKNOWN)))
            assertThat(screens.findBySceneIdOrderByIdAsc(title).toList()).hasSize(3)

            for (answer in order) ruleFrame(world, answer)
            finalState(title)
        }

        assertThat(outcomes.distinct())
            .describedAs("답의 도착 순서가 최종 상태를 바꾸면 같은 런을 두 번 돌린 결과가 달라진다")
            .hasSize(1)
        // 세 화면이 하나로 합쳐졌는지도 함께 본다 — 전부 빈 결과로 사이좋게 같아지는 것을 막는다.
        assertThat(outcomes.first()).hasSize(1)
    }

    // ---------- 거절 ----------

    /**
     * 없는 대상은 거절되고 사유가 돌아온다 (ARTEL-657 의 인수 조건).
     *
     * 그대로 받으면 아무것에도 안 맞는 항목이 조용히 쌓이고, 부른 쪽은 고쳤다고 믿는다.
     */
    @Test
    fun `없는 selector 와 사유 없는 항목은 거절되고 사유가 돌아온다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)
        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))

        val result = ruleFrame(
            world,
            entry("Canvas[2]/nothingLikeThis[9]", screenDefining = true),
            entry(CONTINUE, screenDefining = false, reason = null),
            entry(CONTINUE, screenDefining = false, match = "regex"),
        )

        assertThat(result.path("accepted").size()).isZero()
        val reasons = result.path("rejected").map { it.path("reason").asText() }
        assertThat(reasons).hasSize(3)
        assertThat(reasons[0]).contains("matches nothing observed")
        assertThat(reasons[1]).contains("reason is required")
        assertThat(reasons[2]).contains("match must be one of")
    }

    /** 목록은 씬 단위다. 지금 서 있지 않은 씬은 그 씬에 서서 본 것이 아니므로 근거가 없다. */
    @Test
    fun `모르는 씬은 거절된다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)
        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))

        val result = ruleFrame(world, entry(CONTINUE, screenDefining = false), scene = "NoSuchScene")

        assertThat(result.path("rejected").single().path("reason").asText())
            .contains("unknown scene")
        assertThat(screens.findBySceneIdOrderByIdAsc(title).toList()).hasSize(1)
    }

    // ---------- 씬 상한 ----------

    /**
     * **상한에서 포기하지 않는다.**
     *
     * 화면이 상한에 닿았다는 것은 목록이 너무 잘다는 뜻이다 — 목록이 성기면 화면이 늘지 않고
     * 뭉친다. 그러므로 할 일은 기록을 멈추는 것이 아니라 목록을 좁히는 것이고, 좁히는 유일한
     * 방법이 묻는 것이다. 그래서 상한에 닿으면 지금 화면을 가르고 있는 것들을 후보로 낸다.
     */
    @Test
    fun `화면 상한에 닿으면 목록을 좁힐 제안이 나간다`(): Unit = runBlocking {
        val world = newWorld()
        val battle = newScene(world, "TurnBattleScene")
        val controls = (0 until 6).map { "Canvas[$it]/control$it[0]" }
        for (selector in controls) newCapability(world, battle, selector)

        // 6개의 켜짐/꺼짐 조합은 64 가지다. 상한(32)을 넘길 때까지 서로 다른 조합을 흘린다.
        for (mask in 0..ScreenObservationService.MAX_SCREENS_PER_SCENE) {
            val active = controls.filterIndexed { index, _ -> (mask shr index) and 1 == 1 }
            val deactive = controls - active.toSet()
            observeTwice(world, whole("TurnBattleScene", active = active, deactive = deactive))
        }

        assertThat(screens.findBySceneIdOrderByIdAsc(battle).toList())
            .hasSize(ScreenObservationService.MAX_SCREENS_PER_SCENE)
        val capProposal = proposalsSent().last { it.payload.path("reason").asText() == "scene-screen-cap" }
        assertThat(capProposal.payload.path("candidates").size()).isGreaterThan(0)
        assertThat(capProposal.payload.path("candidates").map { it.path("in_whitelist").asBoolean() })
            .describedAs("상한 제안의 후보는 지금 화면을 가르고 있는 것들이다")
            .containsOnly(true)
        // 상한 제안도 한 번만 나간다.
        assertThat(proposalsSent().count { it.payload.path("reason").asText() == "scene-screen-cap" })
            .isEqualTo(1)
    }

    // ---------- 픽스처 ----------

    private data class World(val gameInstanceId: Long, val contentMapId: Long, val qaTryId: Long)

    private val CONTINUE = "Canvas[2]/continue[1]"
    private val SPINNER = "Canvas[2]/spinner[0]"
    private val BADGE = "Canvas[2]/badge[3]"
    private val UNKNOWN = "CombineSystem[7]/CombineZone[1]/Zone1[0]"

    /** `discriminator` 가 굳으려면 연속 두 `pulse` 가 필요하다(`ScreenFold.SETTLE_READINGS`). */
    private suspend fun observeTwice(world: World, payload: String) {
        observation.observe(world.gameInstanceId, payload)
        observation.observe(world.gameInstanceId, payload)
    }

    private fun whole(
        scene: String,
        active: List<String> = emptyList(),
        deactive: List<String> = emptyList(),
    ): String {
        val activeJson = active.joinToString(",") { plain(it) }
        val deactiveJson = deactive.joinToString(",") { plain(it) }
        return """
            {"type":"PULSE","schema":2,"scene":"$scene","whole":true,
             "active":[$activeJson],"deactive":[$deactiveJson]}
        """.trimIndent()
    }

    private fun plain(selector: String) = """{"selector":"$selector","path":"$selector"}"""

    private fun proposalsSent() = recorder.sent.filter { it.type == ScreenSelectorFrames.PROPOSAL }

    private fun candidateSelectors(payload: JsonNode) =
        payload.path("candidates").map { it.path("selector").asText() }

    private fun entry(
        pattern: String,
        screenDefining: Boolean,
        reason: String? = "캡처에서 눈에 보이게 달랐다",
        match: String = "selector",
    ): String {
        val reasonJson = reason?.let { ""","reason":"$it"""" } ?: ""
        return """{"match":"$match","pattern":"$pattern","screen_defining":$screenDefining$reasonJson}"""
    }

    /** 나간 제안에 `SCREEN_SELECTOR_VERDICT` 로 답한다. */
    private suspend fun answerProposal(world: World, vararg entries: String): JsonNode {
        val proposal = proposalsSent().last()
        return deliver(
            world,
            ScreenSelectorFrames.VERDICT,
            """{"proposal_id":"${proposal.messageId}","entries":[${entries.joinToString(",")}]}""",
            correlationId = proposal.messageId,
        )
    }

    /** QA agent 의 tool 이 보내는 `SCREEN_SELECTOR_RULE`. */
    private suspend fun ruleFrame(
        world: World,
        vararg entries: String,
        scene: String = "TitleScene",
    ): JsonNode = deliver(
        world,
        ScreenSelectorFrames.RULE,
        """{"scene":"$scene","entries":[${entries.joinToString(",")}]}""",
    )

    private suspend fun deliver(
        world: World,
        type: String,
        payload: String,
        correlationId: String? = null,
    ): JsonNode {
        inboundRouter.handle(
            QaAgentEnvelope(
                messageId = UUID.randomUUID().toString(),
                type = type,
                qaTryId = world.qaTryId.toString(),
                correlationId = correlationId,
                timestamp = Instant.now(),
                payload = objectMapper.readTree(payload),
            )
        )
        return recorder.sent.last { it.type == ScreenSelectorFrames.RESULT }.payload
    }

    /**
     * 한 씬의 최종 상태 전부. 순서 독립을 재는 자다.
     *
     * 화면 id 는 담지 않는다. 월드마다 시퀀스가 다르므로 id 를 넣으면 무엇을 해도 다르게 나온다.
     * 담는 것은 **무엇이 남았고 몇 번 봤나**이고, 그것이 순서가 바꾸면 안 되는 값이다.
     */
    private suspend fun finalState(sceneId: Long): List<Pair<List<Pair<String, Boolean>>, Int>> =
        screens.findBySceneIdOrderByIdAsc(sceneId).toList()
            .map { read(it.discriminator) to it.observedCount }
            .sortedBy { it.first.joinToString() }

    private suspend fun transitionsOf(sceneId: Long) =
        screens.findBySceneIdOrderByIdAsc(sceneId).toList()
            .flatMap { transitions.findByFromScreenIdOrderByIdAsc(it.id!!).toList() }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.size <= 1) listOf(items)
        else items.flatMap { head ->
            permutations(items - head).map { listOf(head) + it }
        }

    private suspend fun newWorld(): World {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(name = "selector-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
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
                evidenceDigest = "screen-selector-proposal",
            )
        )
        val user = newUser()
        val testRunId = testRuns.save(TestRunEntity(projectId = project.id!!, name = "런")).id!!
        qaRuns.save(
            QaRunEntity(
                testRunId = testRunId,
                gameInstanceId = instance.id!!,
                startedBy = user,
                status = "RUNNING",
                startedAt = now,
            )
        )
        val scenario = testScenarios.save(TestScenarioEntity(projectId = project.id!!))!!
        val qaTry = qaTries.save(
            QaTryEntity(
                testScenarioId = scenario.id!!,
                gameInstanceId = instance.id!!,
                startedBy = user,
                agentSessionId = SESSION_ID,
                status = "RUNNING",
                startedAt = now,
            )
        )!!
        return World(instance.id!!, contentMap.id!!, qaTry.id!!)
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

    private fun read(discriminator: Json): List<Pair<String, Boolean>> =
        ObjectMapper().readTree(discriminator.asString())
            .map { it.path("selector").asText() to it.path("active").asBoolean() }

    private fun newUser(): Long =
        db.sql("INSERT INTO app_user (display_name, nickname, user_tag) VALUES ('selector', 'selector-' || gen_random_uuid(), '0000') RETURNING id")
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().block()!!
}
