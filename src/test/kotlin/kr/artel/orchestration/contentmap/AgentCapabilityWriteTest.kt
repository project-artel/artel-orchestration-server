package kr.artel.orchestration.contentmap

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.contentmap.entity.Actionability
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.entity.CapabilityOrigin
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.ContentMapMode
import kr.artel.orchestration.contentmap.entity.Interaction
import kr.artel.orchestration.contentmap.entity.ObservationSource
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.entity.VerificationState
import kr.artel.orchestration.contentmap.observe.CapabilityWriteFrames
import kr.artel.orchestration.contentmap.repository.CapabilityInferenceRepository
import kr.artel.orchestration.contentmap.repository.CapabilityObservationRepository
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
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaAgentEnvelope
import kr.artel.orchestration.qa.service.QaAgentInboundRouter
import kr.artel.orchestration.qa.service.QaAgentPort
import kr.artel.orchestration.qa.service.QaAgentSession
import kr.artel.orchestration.qa.service.QaAgentSessionContext
import kr.artel.orchestration.qa.service.QaLogService
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private const val SESSION_ID = "capability-write-session"
private const val BATTLE = "TurnBattleScene"
private const val TITLE = "TitleScene"

/**
 * agent 가 본 것을 capability 에 적는 경로(ARTEL-644).
 *
 * 계약 문서는 `docs/capability-write-frames.md` 다. 이 파일이 지키는 것은 셋이다.
 *
 * 1. **이슈의 인수 조건** — works 와 fails 가 다른 결과로 남고, `evidence` 에 없던 capability 가
 *    `origin = observed` 로 서고, 같은 문장을 두 번 보내도 행이 둘이 되지 않으며, 그 `scene` 에
 *    없는 capability 는 사유와 함께 거절된다
 * 2. **agent 가 정적 분석을 덮지 않는다** — `evidence` 출신 행에서 `verification` 과 그 포인터
 *    말고 어느 칸도 움직이지 않는다는 것을 컬럼 단위로 본다
 * 3. **실측의 축소판** — capability 12 개 중 10 개에 문장을 적고 나머지 2 개가 그대로인지 본다.
 *    실측 사본에서 472 개 중 10 개만 바뀌는지 보는 것과 같은 질문이다
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AgentCapabilityWriteTest {

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

    @Autowired private lateinit var router: QaAgentInboundRouter
    @Autowired private lateinit var capabilities: CapabilityRepository
    @Autowired private lateinit var observations: CapabilityObservationRepository
    @Autowired private lateinit var inferences: CapabilityInferenceRepository
    @Autowired private lateinit var scenes: SceneRepository
    @Autowired private lateinit var sceneEdges: SceneEdgeRepository
    @Autowired private lateinit var screens: ScreenRepository
    @Autowired private lateinit var screenCapabilities: ScreenCapabilityRepository
    @Autowired private lateinit var screenTransitions: ScreenTransitionRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var gameInstances: GameInstanceRepository
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var testRuns: TestRunRepository
    @Autowired private lateinit var testScenarios: TestScenarioRepository
    @Autowired private lateinit var qaRuns: QaRunRepository
    @Autowired private lateinit var qaTries: QaTryRepository
    @Autowired private lateinit var qaLogs: QaLogRepository
    @Autowired private lateinit var logService: QaLogService
    @Autowired private lateinit var appUsers: AppUserRepository
    @Autowired private lateinit var identities: OAuthIdentityRepository
    @Autowired private lateinit var oauthUsers: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var agentPort: QaAgentPort

    private val recorder: RecordingAgentPort get() = agentPort as RecordingAgentPort

    /**
     * 이 스위트가 만든 것을 스스로 치운다. `qa_run` 을 남기면 뒤에 도는 다른 스위트의 전역 삭제가
     * FK 에 막힌다(ARTEL-661) — 남기지 않는 것이 이 스위트의 몫이다.
     *
     * `capability` 보다 `scene_edge` · `screen_transition` 을 **먼저** 비운다. 둘 다
     * `capability_id` 를 `ON DELETE SET NULL` 로 물고 있어, capability 를 먼저 지우면 그 컬럼이
     * NULL 이 되면서 `uk_scene_edge_auto`(`capability_id IS NULL` 부분 유니크)가 걸린다. 다른
     * 스위트가 남긴 `scene_edge` 행이 하나만 있어도 이 스위트 전체가 삭제에서 죽는다.
     */
    @BeforeEach
    @AfterEach
    fun clean(): Unit = runBlocking {
        recorder.sent.clear()
        inferences.deleteAll()
        observations.deleteAll()
        screenCapabilities.deleteAll()
        screenTransitions.deleteAll()
        sceneEdges.deleteAll()
        screens.deleteAll()
        capabilities.deleteAll()
        scenes.deleteAll()
        contentMaps.deleteAll()
        qaLogs.deleteAll()
        qaTries.deleteAll()
        qaRuns.deleteAll()
        testRuns.deleteAll()
        testScenarios.deleteAll()
        gameInstances.deleteAll()
        gameBuilds.deleteAll()
        projects.deleteAll()
        identities.deleteAll()
        appUsers.deleteAll()
    }

    @Test
    fun `works 는 confirmed 로, fails 는 contradicted 로 남는다`(): Unit = runBlocking {
        val world = newWorld()
        val works = newEvidenceCapability(world, world.battle, "EndTurn 을 누르면 턴이 넘어간다", key = "k-works")
        val fails = newEvidenceCapability(world, world.battle, "Combine 을 누르면 카드가 합쳐진다", key = "k-fails")

        verdict(world, key = "k-works", verdict = "works", rationale = "턴 카운터가 3 에서 4 로 올랐다")
        verdict(world, key = "k-fails", verdict = "fails", rationale = "Combine 버튼을 눌러도 Zone 이 그대로였다")

        assertThat(capabilities.findById(works)!!.verification).isEqualTo(VerificationState.CONFIRMED.wire)
        assertThat(capabilities.findById(fails)!!.verification).isEqualTo(VerificationState.CONTRADICTED.wire)

        val rows = observations.findAll().toList()
        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.verdict }).containsExactlyInAnyOrder("works", "fails")
        assertThat(rows.map { it.source }.distinct()).containsExactly(ObservationSource.AGENT.wire)
    }

    /** verdict 만 받으면 나중에 그것이 맞았는지 확인할 길이 없다. 그래서 rationale 이 함께 남는다. */
    @Test
    fun `무엇을 보고 그렇게 말했는지가 런과 함께 남는다`(): Unit = runBlocking {
        val world = newWorld()
        val id = newEvidenceCapability(world, world.battle, "EndTurn 을 누르면 턴이 넘어간다", key = "k1")

        verdict(world, key = "k1", verdict = "works", rationale = "턴 카운터가 3 에서 4 로 올랐다")

        val row = observations.findAll().toList().single()
        assertThat(row.rationale).isEqualTo("턴 카운터가 3 에서 4 로 올랐다")
        assertThat(row.qaRunId).isEqualTo(world.qaRunId)
        assertThat(row.qaTryId).isEqualTo(world.qaTryId)
        assertThat(row.agentMessageId).isNotNull()
        assertThat(row.actedAt).isNotNull()
        // verification 에서 그 문장으로 되짚을 수 있다.
        assertThat(capabilities.findById(id)!!.verificationObservationId).isEqualTo(row.id)
    }

    @Test
    fun `rationale 이 없으면 거절하고 이유를 돌려준다`(): Unit = runBlocking {
        val world = newWorld()
        newEvidenceCapability(world, world.battle, "EndTurn 을 누르면 턴이 넘어간다", key = "k1")

        verdict(world, key = "k1", verdict = "works", rationale = "   ")

        val answer = recorder.sent.single()
        assertThat(answer.type).isEqualTo("ERROR")
        assertThat(answer.payload.path("message").asText()).contains("payload.rationale is required")
        assertThat(observations.findAll().toList()).isEmpty()
    }

    @Test
    fun `같은 문장을 두 번 보내도 행이 둘이 되지 않는다`(): Unit = runBlocking {
        val world = newWorld()
        newEvidenceCapability(world, world.battle, "EndTurn 을 누르면 턴이 넘어간다", key = "k1")

        verdict(world, key = "k1", verdict = "works", rationale = "턴 카운터가 올랐다")
        verdict(world, key = "k1", verdict = "works", rationale = "턴 카운터가 올랐다")

        assertThat(observations.findAll().toList()).hasSize(1)
        val ids = recorder.sent.map { it.payload.path("observation_id").asText() }.distinct()
        assertThat(ids).describedAs("두 응답의 id 가 다르면 멱등이 응답까지 오지 않은 것이다").hasSize(1)
    }

    /**
     * works 뒤에 fails 는 **다른 문장**이라 행이 둘이다. 하나로 덮으면 어긋남 자체가 사라지는데,
     * 그 어긋남이 이 표가 남기려는 것이다.
     */
    @Test
    fun `한 런에서 verdict 이 뒤집히면 행이 둘로 남는다`(): Unit = runBlocking {
        val world = newWorld()
        val id = newEvidenceCapability(world, world.battle, "EndTurn 을 누르면 턴이 넘어간다", key = "k1")

        verdict(world, key = "k1", verdict = "works", rationale = "한 번은 넘어갔다")
        verdict(world, key = "k1", verdict = "fails", rationale = "두 번째에는 멈췄다")

        assertThat(observations.findAll().toList()).hasSize(2)
        assertThat(capabilities.findById(id)!!.verification).isEqualTo(VerificationState.CONTRADICTED.wire)
    }

    @Test
    fun `evidence 에 없던 capability 를 observed 로 적는다`(): Unit = runBlocking {
        val world = newWorld()

        discover(
            world,
            """
            {"scene":"$BATTLE","origin":"observed","interaction":"none",
             "summary":"Combat.RewardPanel 이 마지막 Combat.Enemy 의 hp 가 0 이 되면 보상 줄을 띄운다",
             "given_text":"전투에 적이 하나 이상 살아 있다",
             "rationale":"마지막 적을 두 번 처치했고 두 번 다 보상 패널이 열렸다",
             "verdict":"works"}
            """.trimIndent()
        )

        val row = capabilities.findAll().toList().single()
        assertThat(row.origin).isEqualTo(CapabilityOrigin.OBSERVED.wire)
        assertThat(row.verification).isEqualTo(VerificationState.CONFIRMED.wire)
        // 산식의 입력이 없으므로 키가 없다. 더미값을 넣으면 그 순간 키가 키가 아니게 된다.
        assertThat(row.capabilityKey).isNull()
        // interaction = none 은 단독 명세가 될 수 없다. 실측 472 행 중 418 행이 같은 상태다.
        assertThat(row.actionability).isEqualTo(Actionability.NOT_A_STEP.wire)

        val answer = recorder.sent.single()
        assertThat(answer.type).isEqualTo(CapabilityWriteFrames.WRITE_RESULT)
        assertThat(answer.payload.path("created").asBoolean()).isTrue()
        assertThat(answer.payload.path("capability_id").asText()).isEqualTo(row.id.toString())
    }

    @Test
    fun `같은 발견을 두 번 보내면 두 번째는 created 가 false 다`(): Unit = runBlocking {
        val world = newWorld()
        val payload = """
            {"scene":"$BATTLE","origin":"observed","interaction":"none","verdict":"works",
             "summary":"적을 처치하면 보상을 받는다","rationale":"두 번 처치했고 두 번 다 받았다"}
        """.trimIndent()

        discover(world, payload)
        discover(world, payload)

        assertThat(capabilities.findAll().toList()).hasSize(1)
        assertThat(recorder.sent.map { it.payload.path("created").asBoolean() }).containsExactly(true, false)
        val ids = recorder.sent.map { it.payload.path("capability_id").asText() }.distinct()
        assertThat(ids).hasSize(1)
    }

    @Test
    fun `inferred 는 딛고 선 observation 을 밝혀야 한다`(): Unit = runBlocking {
        val world = newWorld()
        newEvidenceCapability(world, world.battle, "EndTurn 을 누르면 턴이 넘어간다", key = "k1")
        verdict(world, key = "k1", verdict = "works", rationale = "턴 카운터가 올랐다")
        val observationId = observations.findAll().toList().single().id!!
        recorder.sent.clear()

        discover(
            world,
            """
            {"scene":"$BATTLE","origin":"inferred","interaction":"none",
             "summary":"턴이 넘어가면 Combat.Enemy 가 행동한다","rationale":"턴을 넘긴 뒤 적이 움직였다"}
            """.trimIndent()
        )
        assertThat(recorder.sent.single().type).isEqualTo("ERROR")
        assertThat(recorder.sent.single().payload.path("message").asText()).contains("requires based_on")
        recorder.sent.clear()

        discover(
            world,
            """
            {"scene":"$BATTLE","origin":"inferred","interaction":"none",
             "summary":"턴이 넘어가면 Combat.Enemy 가 행동한다","rationale":"턴을 넘긴 뒤 적이 움직였다",
             "based_on":["$observationId"]}
            """.trimIndent()
        )

        val inference = inferences.findAll().toList().single()
        assertThat(inference.basedOn.asString()).contains(observationId.toString())
        val created = capabilities.findAll().toList().single { it.origin == CapabilityOrigin.INFERRED.wire }
        assertThat(created.verification).isEqualTo(VerificationState.UNVERIFIED.wire)
    }

    @Test
    fun `그 scene 에 없는 capability 는 어느 scene 의 것인지와 함께 거절된다`(): Unit = runBlocking {
        val world = newWorld()
        newEvidenceCapability(world, world.title, "게임을 시작한다", key = "k-title")

        verdict(world, key = "k-title", verdict = "works", rationale = "시작 버튼이 먹었다", scene = BATTLE)

        val answer = recorder.sent.single()
        assertThat(answer.type).isEqualTo("ERROR")
        val message = answer.payload.path("message").asText()
        assertThat(message).contains("belongs to scene $TITLE, not $BATTLE")
        assertThat(observations.findAll().toList()).isEmpty()
        // 사람이 볼 흔적도 남는다. 조용히 버리지 않는다.
        assertThat(qaLogs.findAll().toList().count { it.type == "ERROR" }).isEqualTo(1)
    }

    @Test
    fun `지도가 모르는 scene 은 거절된다`(): Unit = runBlocking {
        val world = newWorld()

        discover(
            world,
            """
            {"scene":"ShopScene","origin":"observed","interaction":"none","verdict":"works",
             "summary":"상점에서 물건을 산다","rationale":"상점에 들어가 하나 샀다"}
            """.trimIndent()
        )

        assertThat(recorder.sent.single().payload.path("message").asText())
            .contains("references an unknown scene: ShopScene")
        assertThat(capabilities.findAll().toList()).isEmpty()
    }

    /**
     * 이 스위트가 존재하는 가장 큰 이유. agent 가 `verification` 과 그 포인터 말고 무엇이든 움직일
     * 수 있으면, 정적 분석이 알아낸 것이 런 하나에 조용히 지워진다.
     */
    @Test
    fun `evidence 출신 행에서 verification 말고는 아무 칸도 움직이지 않는다`(): Unit = runBlocking {
        val world = newWorld()
        val id = newEvidenceCapability(world, world.battle, "EndTurn 을 누르면 턴이 넘어간다", key = "k1")
        val before = capabilities.findById(id)!!

        verdict(world, key = "k1", verdict = "works", rationale = "턴 카운터가 올랐다")

        val after = capabilities.findById(id)!!
        assertThat(after.verification).isEqualTo(VerificationState.CONFIRMED.wire)
        assertThat(after.verificationObservationId).isNotNull()
        assertThat(
            after.copy(
                verification = before.verification,
                verificationObservationId = before.verificationObservationId,
                updatedAt = before.updatedAt,
            )
        ).isEqualTo(before)
    }

    /**
     * 실측 사본이 묻는 질문의 축소판이다. capability 12 개 중 10 개에 문장을 적고, 그 10 개만
     * 바뀌었는지와 나머지 2 개가 통째로 그대로인지를 함께 본다.
     */
    @Test
    fun `열 개를 적으면 그 열 개만 바뀐다`(): Unit = runBlocking {
        val world = newWorld()
        val ids = (1..12).map { newEvidenceCapability(world, world.battle, "capability $it", key = "k$it") }
        val before = ids.associateWith { capabilities.findById(it)!! }

        (1..10).forEach { verdict(world, key = "k$it", verdict = "works", rationale = "$it 번을 눌러 봤다") }

        val touched = ids.take(10)
        val untouched = ids.drop(10)
        assertThat(touched.map { capabilities.findById(it)!!.verification }.distinct())
            .containsExactly(VerificationState.CONFIRMED.wire)
        untouched.forEach { id ->
            assertThat(capabilities.findById(id)).describedAs("적지 않은 행은 통째로 그대로다")
                .isEqualTo(before.getValue(id))
        }
        assertThat(observations.findAll().toList()).hasSize(10)
    }

    /** 쓰기가 결과이고 답은 부산물이다. 세션이 없으면 답만 못 보낸다 — 지식 쓰기와 같은 판단이다. */
    @Test
    fun `세션이 없어도 쓰기는 남는다`(): Unit = runBlocking {
        val world = newWorld(agentSessionId = null)
        newEvidenceCapability(world, world.battle, "EndTurn 을 누르면 턴이 넘어간다", key = "k1")

        verdict(world, key = "k1", verdict = "works", rationale = "턴 카운터가 올랐다")

        assertThat(observations.findAll().toList()).hasSize(1)
        assertThat(recorder.sent).isEmpty()
    }

    /** 이 try 가 찍지 않은 캡처를 근거로 달면, rationale 이 가리키는 그림이 다른 런의 것이 된다. */
    @Test
    fun `이 try 의 것이 아닌 capture 는 거절된다`(): Unit = runBlocking {
        val world = newWorld()
        newEvidenceCapability(world, world.battle, "EndTurn 을 누르면 턴이 넘어간다", key = "k1")
        val capture = UUID.randomUUID().toString()

        verdict(world, key = "k1", verdict = "works", rationale = "턴 카운터가 올랐다", captureId = capture)
        assertThat(recorder.sent.single().payload.path("message").asText())
            .contains("references a capture this try never took")
        recorder.sent.clear()

        // 실제로 찍은 캡처는 통과하고 그 id 가 문장에 남는다.
        logService.append(
            qaTryId = world.qaTryId,
            direction = "SDK_TO_ORCHE",
            type = "SCREENSHOT",
            messageId = capture,
            message = "전체 화면을 캡처했습니다.",
            payload = objectMapper.createObjectNode().put("captureId", capture)
        )
        verdict(world, key = "k1", verdict = "works", rationale = "턴 카운터가 올랐다", captureId = capture)

        assertThat(observations.findAll().toList().single().captureId).isEqualTo(capture)
    }

    @Test
    fun `agent 는 evidence 출신 행을 만들 수 없다`(): Unit = runBlocking {
        val world = newWorld()

        discover(
            world,
            """
            {"scene":"$BATTLE","origin":"evidence","interaction":"none","verdict":"works",
             "summary":"정적 분석인 척하는 행","rationale":"그럴듯하게 적어 본다"}
            """.trimIndent()
        )

        assertThat(recorder.sent.single().payload.path("message").asText())
            .contains("payload.origin must be one of")
        assertThat(capabilities.findAll().toList()).isEmpty()
    }

    /**
     * `observed` 는 "눌러 보고 결과를 봤다" 는 뜻이다. verdict 가 없으면 rationale 이 앉을 자리도
     * 없어 — observation 행은 verdict 없이 서지 못한다 — 검증만 하고 버리게 된다.
     */
    @Test
    fun `observed 인데 verdict 이 없으면 거절된다`(): Unit = runBlocking {
        val world = newWorld()

        discover(
            world,
            """
            {"scene":"$BATTLE","origin":"observed","interaction":"none",
             "summary":"적을 처치하면 보상을 받는다","rationale":"두 번 처치했고 두 번 다 받았다"}
            """.trimIndent()
        )

        assertThat(recorder.sent.single().payload.path("message").asText())
            .contains("origin observed requires a verdict")
        assertThat(capabilities.findAll().toList()).isEmpty()
    }

    @Test
    fun `inferred 에 verdict 을 실으면 거절된다`(): Unit = runBlocking {
        val world = newWorld()

        discover(
            world,
            """
            {"scene":"$BATTLE","origin":"inferred","interaction":"none","verdict":"works",
             "summary":"턴이 넘어가면 적이 행동한다","rationale":"턴을 넘긴 뒤 적이 움직였다","based_on":["1"]}
            """.trimIndent()
        )

        assertThat(recorder.sent.single().payload.path("message").asText())
            .contains("cannot carry a verdict")
        assertThat(capabilities.findAll().toList()).isEmpty()
    }

    /**
     * `observed` 행은 `capability_key` 가 없어 id 로만 지목할 수 있다. 문서가 그렇게 하라고
     * 말하는 유일한 경로라, 실제로 되는지 여기서 본다.
     */
    @Test
    fun `방금 만든 행은 capability_id 로 다시 지목한다`(): Unit = runBlocking {
        val world = newWorld()
        discover(
            world,
            """
            {"scene":"$BATTLE","origin":"observed","interaction":"none","verdict":"works",
             "summary":"적을 처치하면 보상을 받는다","rationale":"두 번 처치했고 두 번 다 받았다"}
            """.trimIndent()
        )
        val id = recorder.sent.single().payload.path("capability_id").asText()
        recorder.sent.clear()

        deliver(
            world,
            CapabilityWriteFrames.VERDICT,
            """{"scene":"$BATTLE","capability_id":"$id","verdict":"fails","rationale":"세 번 더 처치했지만 보상이 없었다"}"""
        )

        assertThat(recorder.sent.single().type).isEqualTo(CapabilityWriteFrames.WRITE_RESULT)
        assertThat(capabilities.findById(id.toLong())!!.verification)
            .isEqualTo(VerificationState.CONTRADICTED.wire)
        assertThat(observations.findAll().toList()).hasSize(2)
    }

    @Test
    fun `대상을 둘 다 적거나 하나도 안 적으면 거절된다`(): Unit = runBlocking {
        val world = newWorld()
        newEvidenceCapability(world, world.battle, "EndTurn 을 누르면 턴이 넘어간다", key = "k1")

        deliver(
            world,
            CapabilityWriteFrames.VERDICT,
            """{"scene":"$BATTLE","capability_key":"k1","capability_id":"1","verdict":"works","rationale":"눌러 봤다"}"""
        )
        deliver(
            world,
            CapabilityWriteFrames.VERDICT,
            """{"scene":"$BATTLE","verdict":"works","rationale":"눌러 봤다"}"""
        )

        assertThat(recorder.sent.map { it.payload.path("message").asText() })
            .allSatisfy { assertThat(it).contains("needs exactly one of capability_key or capability_id") }
        assertThat(observations.findAll().toList()).isEmpty()
    }

    /** `ck_capability_press_needs_key` 를 DB 예외가 아니라 읽을 수 있는 사유로 돌려준다. */
    @Test
    fun `press 는 input_key 를 요구하고 나머지는 실을 수 없다`(): Unit = runBlocking {
        val world = newWorld()

        discover(
            world,
            """
            {"scene":"$BATTLE","origin":"observed","interaction":"press","verdict":"works",
             "summary":"아무 키를 누르면 대사가 넘어간다","rationale":"space 를 눌러 봤다"}
            """.trimIndent()
        )
        discover(
            world,
            """
            {"scene":"$BATTLE","origin":"observed","interaction":"click","input_key":"space","verdict":"works",
             "summary":"버튼을 누르면 창이 닫힌다","rationale":"닫혔다"}
            """.trimIndent()
        )

        assertThat(recorder.sent).hasSize(2)
        assertThat(recorder.sent.map { it.payload.path("message").asText() })
            .allSatisfy { assertThat(it).contains("requires input_key") }
        assertThat(capabilities.findAll().toList()).isEmpty()
    }

    @Test
    fun `다른 런의 observation 을 딛고 섰다고 하면 거절된다`(): Unit = runBlocking {
        val other = newWorld()
        newEvidenceCapability(other, other.battle, "EndTurn 을 누르면 턴이 넘어간다", key = "k-other")
        verdict(other, key = "k-other", verdict = "works", rationale = "다른 런에서 눌러 봤다")
        val otherObservationId = observations.findAll().toList().single().id!!
        recorder.sent.clear()

        val world = newWorld()
        discover(
            world,
            """
            {"scene":"$BATTLE","origin":"inferred","interaction":"none",
             "summary":"턴이 넘어가면 적이 행동한다","rationale":"턴을 넘긴 뒤 적이 움직였다",
             "based_on":["$otherObservationId"]}
            """.trimIndent()
        )

        assertThat(recorder.sent.single().payload.path("message").asText())
            .contains("references observations outside this run")
        assertThat(inferences.findAll().toList()).isEmpty()
    }

    /**
     * 먼저 `inferred` 로 적어 둔 것을 나중에 실제로 보는 경우. origin 은 그대로 두고 verification 만
     * 올라간다 — 축이 둘인 설계가 이 경우를 위한 것이다.
     */
    @Test
    fun `inferred 로 적은 것을 나중에 봐도 행은 하나고 verification 만 올라간다`(): Unit = runBlocking {
        val world = newWorld()
        newEvidenceCapability(world, world.battle, "EndTurn 을 누르면 턴이 넘어간다", key = "k1")
        verdict(world, key = "k1", verdict = "works", rationale = "턴 카운터가 올랐다")
        val seed = observations.findAll().toList().single().id!!
        val summary = "턴이 넘어가면 Combat.Enemy 가 행동한다"

        discover(
            world,
            """
            {"scene":"$BATTLE","origin":"inferred","interaction":"none",
             "summary":"$summary","rationale":"턴을 넘긴 뒤 적이 움직였다","based_on":["$seed"]}
            """.trimIndent()
        )
        discover(
            world,
            """
            {"scene":"$BATTLE","origin":"observed","interaction":"none","verdict":"works",
             "summary":"$summary","rationale":"이번에는 적이 움직이는 것을 끝까지 봤다"}
            """.trimIndent()
        )

        val rows = capabilities.findAll().toList().filter { it.origin != CapabilityOrigin.EVIDENCE.wire }
        assertThat(rows).hasSize(1)
        assertThat(rows.single().origin).isEqualTo(CapabilityOrigin.INFERRED.wire)
        assertThat(rows.single().verification).isEqualTo(VerificationState.CONFIRMED.wire)
    }

    // --- helpers ---

    // ------------------------------------------------------- content_map_mode

    /**
     * `content_map_mode = frozen` 인 런은 지도를 **읽기만** 한다.
     *
     * 반복 측정이 성립하려면 arm 이 자기가 읽는 것을 바꾸면 안 된다. `verdict` 하나가 다음 런의
     * 출발점을 옮기면 두 런은 같은 설정이 아니고, 반복이 반복이 아니게 된다. `capability` 에는
     * `knowledge.scope_id` 같은 격리 축이 없어 이 게이트가 그것을 막는 유일한 수단이다.
     */
    @Test
    fun `frozen 런의 verdict 는 지도를 바꾸지 않고 거절된다`(): Unit = runBlocking {
        val world = newWorld(contentMapMode = ContentMapMode.FROZEN)
        val id = newEvidenceCapability(world, world.battle, "EndTurn 을 누르면 턴이 넘어간다", key = "k1")

        verdict(world, key = "k1", verdict = "works", rationale = "턴 카운터가 3 에서 4 로 올랐다")

        // 거절도 답이 온다. 조용히 버리면 agent 의 tool 이 타임아웃까지 매달려, 측정용 arm 이
        // 가장 느려지는 — 지표에는 실패로 남지 않는 — 회귀가 된다.
        val answer = recorder.sent.single()
        assertThat(answer.type).isEqualTo("ERROR")
        assertThat(answer.payload.path("message").asText()).contains("content_map_mode=frozen")
        assertThat(capabilities.findById(id)!!.verification).isEqualTo(VerificationState.UNVERIFIED.wire)
        assertThat(observations.findAll().toList()).isEmpty()
    }

    /** `off` 런은 지도를 보지도 못하므로 새 `capability` 를 적을 수도 없다. */
    @Test
    fun `off 런은 새 capability 를 적지 못한다`(): Unit = runBlocking {
        val world = newWorld(contentMapMode = ContentMapMode.OFF)

        discover(
            world,
            """
            {"scene":"$BATTLE","origin":"observed","interaction":"none",
             "summary":"마지막 적을 처치하면 보상 줄이 뜬다",
             "rationale":"두 번 처치했고 두 번 다 보상 패널이 열렸다",
             "verdict":"works"}
            """.trimIndent()
        )

        val answer = recorder.sent.single()
        assertThat(answer.type).isEqualTo("ERROR")
        assertThat(answer.payload.path("message").asText()).contains("content_map_mode=off")
        assertThat(capabilities.findAll().toList()).isEmpty()
        assertThat(observations.findAll().toList()).isEmpty()
    }

    /**
     * `run_config` 에 값이 없는 런은 지금까지처럼 쓴다. 이 게이트 이전에 만들어진 런이 전부 그
     * 모양이고, 모드를 모르는 런이 실패하면 그것은 실험의 공백이 아니라 장애다.
     */
    @Test
    fun `content_map_mode 가 없는 런은 지금까지처럼 쓴다`(): Unit = runBlocking {
        val world = newWorld()
        val id = newEvidenceCapability(world, world.battle, "EndTurn 을 누르면 턴이 넘어간다", key = "k1")

        verdict(world, key = "k1", verdict = "works", rationale = "턴 카운터가 3 에서 4 로 올랐다")

        assertThat(capabilities.findById(id)!!.verification).isEqualTo(VerificationState.CONFIRMED.wire)
    }

    private suspend fun verdict(
        world: World,
        key: String,
        verdict: String,
        rationale: String,
        scene: String = BATTLE,
        captureId: String? = null,
    ) {
        val capture = captureId?.let { ""","capture_id":"$it"""" } ?: ""
        deliver(
            world,
            CapabilityWriteFrames.VERDICT,
            """{"scene":"$scene","capability_key":"$key","verdict":"$verdict","rationale":"$rationale"$capture}"""
        )
    }

    private suspend fun discover(world: World, payload: String) =
        deliver(world, CapabilityWriteFrames.DISCOVERED, payload)

    private suspend fun deliver(world: World, type: String, payload: String) {
        router.handle(
            QaAgentEnvelope(
                messageId = UUID.randomUUID().toString(),
                type = type,
                qaTryId = world.qaTryId.toString(),
                correlationId = null,
                timestamp = Instant.parse("2026-08-29T00:00:00Z"),
                payload = objectMapper.readTree(payload)
            )
        )
    }

    private suspend fun newEvidenceCapability(
        world: World,
        sceneId: Long,
        summary: String,
        key: String,
    ): Long = capabilities.save(
        CapabilityEntity(
            sceneId = sceneId,
            contentMapId = world.contentMapId,
            capabilityKey = key,
            origin = CapabilityOrigin.EVIDENCE.wire,
            summary = summary,
            givenText = "전투가 시작됐다",
            controlPath = "Canvas/EndTurn",
            controlLabel = "턴 종료",
            controlSelector = "Canvas[7]/EndTurn[0]",
            interaction = Interaction.CLICK.wire,
            actionability = Actionability.RUNNABLE.wire,
        )
    ).id!!

    private suspend fun newWorld(
        agentSessionId: String? = SESSION_ID,
        contentMapMode: ContentMapMode? = null,
    ): World {
        val now = Instant.now()
        val userId = signIn().userId.toLong()
        val project = projects.save(
            ProjectEntity(name = "cap-write-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!
        val build = gameBuilds.save(
            GameBuildEntity(
                projectId = project.id!!,
                version = "v${System.nanoTime()}",
                createdAt = now,
                updatedAt = now,
            )
        )!!
        val instance = gameInstances.save(
            GameInstanceEntity(
                projectId = project.id!!,
                name = "instance",
                platform = "UNITY",
                lastGameBuildId = build.id,
                sdkUuid = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now,
            )
        )!!
        val contentMap = contentMaps.save(
            ContentMapEntity(
                gameBuildId = build.id!!,
                schemaVersion = 6,
                capture = Capture.PLAYER.wire,
                evidenceDigest = "capability-write",
            )
        )!!
        val battle = scenes.save(
            SceneEntity(contentMapId = contentMap.id!!, name = BATTLE, walked = true)
        )!!.id!!
        val title = scenes.save(
            SceneEntity(contentMapId = contentMap.id!!, name = TITLE, walked = true)
        )!!.id!!
        val testRunId = testRuns.save(TestRunEntity(projectId = project.id!!, name = "런"))!!.id!!
        val qaRun = qaRuns.save(
            QaRunEntity(
                testRunId = testRunId,
                gameInstanceId = instance.id!!,
                startedBy = userId,
                status = "RUNNING",
                startedAt = now,
            )
        )!!
        val scenario = testScenarios.save(TestScenarioEntity(projectId = project.id!!))!!
        val qaTry = qaTries.save(
            QaTryEntity(
                testScenarioId = scenario.id!!,
                gameInstanceId = instance.id!!,
                qaRunId = qaRun.id,
                startedBy = userId,
                agentSessionId = agentSessionId,
                status = "RUNNING",
                model = "claude-sonnet-4",
                promptVersion = "v3",
                // null 이면 `{}` 다 — 이 게이트가 생기기 전 런과 같은 모양이고, 나머지 테스트가
                // 전부 그 상태로 돈다.
                runConfig = contentMapMode
                    ?.let { Json.of("""{"content_map_mode":"${it.wire}"}""") }
                    ?: Json.of("{}"),
                startedAt = now,
            )
        )!!
        return World(
            contentMapId = contentMap.id!!,
            battle = battle,
            title = title,
            qaRunId = qaRun.id!!,
            qaTryId = qaTry.id!!,
        )
    }

    private suspend fun signIn(): AuthenticatedUser {
        val seed = UUID.randomUUID().toString().take(8)
        return oauthUsers.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = seed,
                login = "user-$seed",
                displayName = "user-$seed",
                avatarUrl = null,
                email = "user-$seed@example.com",
            )
        )
    }

    private data class World(
        val contentMapId: Long,
        val battle: Long,
        val title: Long,
        val qaRunId: Long,
        val qaTryId: Long,
    )
}
