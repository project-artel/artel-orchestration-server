package kr.artel.orchestration.contentmap

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.contentmap.capture.ScreenCaptureResultRouter
import kr.artel.orchestration.contentmap.capture.ScreenCaptureService
import kr.artel.orchestration.contentmap.entity.Actionability
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.entity.CapabilityOrigin
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.Interaction
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.observe.ScreenNameFrames
import kr.artel.orchestration.contentmap.observe.ScreenNameService
import kr.artel.orchestration.contentmap.observe.ScreenObservationService
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.contentmap.repository.ScreenRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.FakeDocumentStorage
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.qa.entity.QaLogEntity
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
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.socket.WebSocketSession
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private const val SESSION_ID = "screen-name-session"

/**
 * 새로 생긴 `screen` 에 agent 가 이름을 짓는다 (ARTEL-910).
 *
 * 이 파일이 지키는 것은 다섯이다.
 *
 * 1. **새로 만든 행에서만 묻는다** — `ScreenRepository.observe` 는 upsert 라 새로 앉힌 것과 다시 본
 *    것을 구분하지 못한다. 그 구분이 무너지면 같은 화면을 볼 때마다 LLM 호출이 하나씩 나간다.
 *    이 이슈에서 틀리기 가장 쉬운 자리라 첫 테스트가 그것이다
 * 2. **`screen capture` 가 붙은 뒤에 묻는다** — 글만 보고 짓는 이름은 selector 이름을 다시 쓰는 데
 *    그친다. 다만 그림이 영영 안 붙는 화면도 결국 질문을 받아야 한다
 * 3. **답이 `screen.name` 에 앉는다** — 그 칸은 V40 이 만든 뒤로 한 번도 쓰인 적이 없다
 * 4. **못 쓸 답은 안 쓴다** — `null`·빈 문자열·60자 초과. 셋 다 오류가 아니라 그냥 안 쓰는 답이다
 * 5. **이름이 없어도 `screen` 적재는 그대로 끝난다** — 이름은 content map 의 전제가 아니라 표시값이다
 */
@ActiveProfiles("test")
@SpringBootTest
class ScreenNameTest {

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

    /**
     * 앞으로 밀 수 있는 시계.
     *
     * `ScreenNameService` 의 마감이 30 초라 실제로 기다려서는 확인할 수 없고, 기다리는 테스트는
     * 느린 기계에서 흔들린다. 시계를 미는 쪽이 그 두 문제를 다 없앤다.
     */
    class ShiftableClock : Clock() {
        @Volatile
        var shift: Duration = Duration.ZERO

        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.now().plus(shift)
    }

    @TestConfiguration
    class StubConfig {
        @Bean
        @Primary
        fun recordingAgentPort(): QaAgentPort = RecordingAgentPort()

        @Bean
        @Primary
        fun shiftableClock(): Clock = ShiftableClock()

        /** `capture_url` 이 실리는지 보려면 서명이 나와야 한다. 실제 S3 에 닿지 않는 쪽을 쓴다. */
        @Bean
        @Primary
        fun fakeDocumentStorage(): DocumentStorage = FakeDocumentStorage()
    }

    @Autowired private lateinit var observation: ScreenObservationService
    @Autowired private lateinit var captureResults: ScreenCaptureResultRouter
    @Autowired private lateinit var inboundRouter: QaAgentInboundRouter
    @Autowired private lateinit var sessionManager: SessionManager
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var gameInstances: GameInstanceRepository
    @Autowired private lateinit var testRuns: TestRunRepository
    @Autowired private lateinit var testScenarios: TestScenarioRepository
    @Autowired private lateinit var qaRuns: QaRunRepository
    @Autowired private lateinit var qaTries: QaTryRepository
    @Autowired private lateinit var qaLogs: QaLogRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var scenes: SceneRepository
    @Autowired private lateinit var capabilities: CapabilityRepository
    @Autowired private lateinit var screens: ScreenRepository
    @Autowired private lateinit var agentPort: QaAgentPort
    @Autowired private lateinit var clock: Clock
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var db: DatabaseClient

    private val recorder: RecordingAgentPort get() = agentPort as RecordingAgentPort
    private val shiftableClock: ShiftableClock get() = clock as ShiftableClock

    @BeforeEach
    fun reset() {
        recorder.sent.clear()
        shiftableClock.shift = Duration.ZERO
    }

    // ---------- 새로 만든 행에서만 묻는다 ----------

    /**
     * **이 이슈가 조용히 틀릴 수 있는 유일한 자리다.**
     *
     * 두 화면을 세 번 번갈아 보면 두 화면 모두 upsert 를 세 번씩 지난다. 그중 새로 앉히는 것은
     * 각각 한 번뿐이므로 질문도 둘이어야 한다. `observe` 의 `inserted` 가 없으면 여섯이 된다 —
     * 그 여섯은 그대로 LLM 호출 여섯이다.
     */
    @Test
    fun `같은 화면을 다시 봐도 이름을 다시 묻지 않는다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)

        repeat(3) {
            observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))
            observeTwice(world, whole("TitleScene", deactive = listOf(CONTINUE)))
        }

        val rows = screens.findBySceneIdOrderByIdAsc(title).toList()
        assertThat(rows).hasSize(2)
        // 화면 둘에 질문 둘. 관측 수가 아니라 화면 수를 따라간다.
        assertThat(askedScreenIds()).containsExactlyInAnyOrder(
            rows.first().id.toString(),
            rows.last().id.toString(),
        )
    }

    /**
     * 답하는 쪽은 이 payload 만 보고 이름을 짓는다. 그래서 무엇으로 가른 화면인지와 어느 씬인지가
     * 함께 실려야 한다 — 화면 번호만 실으면 `screen-12` 를 다시 `screen-12` 라 부르는 것 말고는
     * 할 수 있는 일이 없다.
     */
    @Test
    fun `질문에 화면과 씬과 request_id 가 실린다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))

        val screen = screens.findBySceneIdOrderByIdAsc(title).toList().single()
        val asked = asked().single()
        val payload = asked.payload

        assertThat(payload.path("screen").path("screen_id").asText()).isEqualTo(screen.id.toString())
        // 이름이 이미 있으면 묻지 않으므로 이 칸은 늘 비어 있다.
        assertThat(payload.path("screen").hasNonNull("name")).isFalse
        assertThat(discriminatorOf(payload.path("screen"))).containsExactly(CONTINUE to true)
        assertThat(payload.path("scene").path("scene_id").asText()).isEqualTo(title.toString())
        assertThat(payload.path("scene").path("name").asText()).isEqualTo("TitleScene")
        // 답이 이 값으로 되돌아온다. 봉투의 correlationId 와 같은 값이어야 둘 중 하나만 읽는
        // 구현에서도 풀린다.
        assertThat(payload.path("request_id").asText()).isEqualTo(asked.messageId)
        assertThat(asked.correlationId).isEqualTo(asked.messageId)
    }

    // ---------- screen capture 와의 순서 ----------

    /**
     * **그림이 붙은 뒤에 묻는다.** 글만 보고 지으면 이름이 selector 이름의 되풀이가 된다.
     *
     * SDK 가 붙어 있으면 `capture_screen` 이 나가고, 그 결과가 돌아오기 전에는 질문을 보내지
     * 않는다. 결과가 와서 그림이 묶인 뒤에야 질문이 나가고 그때 `capture_url` 이 실린다.
     */
    @Test
    fun `capture 가 붙은 뒤에 이름을 묻는다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)
        val sent = connect(world)

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))

        val screen = screens.findBySceneIdOrderByIdAsc(title).toList().single()
        // 아직 그림을 기다리는 중이다. 여기서 이미 물었으면 순서가 틀린 것이다.
        assertThat(asked()).isEmpty()

        val screenshot = newScreenshotLog(world, "qa-captures/${world.qaTryId}/title.jpg")
        captureResults.handle(
            world.gameInstanceId,
            actionResult(sent.captureRequests().single(), screenshot.messageId!!),
        )

        val payload = asked().single().payload
        assertThat(payload.path("screen").path("screen_id").asText()).isEqualTo(screen.id.toString())
        assertThat(payload.path("screen").path("capture_url").asText())
            .contains("qa-captures/${world.qaTryId}/title.jpg")
        assertThat(payload.path("screen").hasNonNull("capture_expires_at")).isTrue
    }

    /**
     * 게임이 `capture_screen` 을 몰라 실패로 답했다. **더 기다릴 것이 없으므로 그때 바로 묻는다.**
     *
     * 성공에만 물으면 그런 빌드의 화면은 마감이 지날 때까지 질문 없이 앉아 있고, 짧은 런은 그
     * 마감을 못 채우고 끝난다.
     */
    @Test
    fun `capture 가 실패해도 이름은 묻는다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)
        val sent = connect(world)

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))
        val screen = screens.findBySceneIdOrderByIdAsc(title).toList().single()

        val requestId = sent.captureRequests().single().path("id").longValue()
        val failure = """
            {"type":"ACTION_RESULT","id":9,"requestId":$requestId,"results":[
             {"id":$requestId,"success":false,"error":"Unsupported method: capture_screen",
              "action":"capture_screen"}]}
        """.trimIndent()
        captureResults.handle(world.gameInstanceId, failure)

        val payload = asked().single().payload
        assertThat(payload.path("screen").path("screen_id").asText()).isEqualTo(screen.id.toString())
        // 그림 없이 묻는다. 그 사실이 payload 에 그대로 보여야 답하는 쪽이 "그림이 있었으면 알았을
        // 것" 을 지어내지 않는다.
        assertThat(payload.path("screen").hasNonNull("capture_url")).isFalse
    }

    /**
     * **capture 답이 영영 안 와도 결국 묻는다.** 기다리다 안 묻는 경로를 남기지 않는 것이 요점이다.
     *
     * SDK 가 요청을 받고 아무 답도 안 한 상태를 만든다 — 런 도중 게임이 멎으면 실제로 그렇게 된다.
     * 마감이 지나면 다음 `pulse` 가 그것을 집어 capture 없이 묻는다.
     */
    @Test
    fun `capture 답이 안 와도 마감이 지나면 묻는다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)
        connect(world)

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))
        val screen = screens.findBySceneIdOrderByIdAsc(title).toList().single()
        assertThat(asked()).isEmpty()

        shiftableClock.shift = ScreenNameService.CAPTURE_GRACE.plusSeconds(1)
        // 마감을 보는 자리가 `pulse` 다. 화면이 굳는 자리에 얹으면 화면이 안 바뀌는 구간에서
        // 마감이 지나도 집을 기회가 안 온다.
        observation.observe(world.gameInstanceId, whole("TitleScene", active = listOf(CONTINUE)))

        val payload = asked().single().payload
        assertThat(payload.path("screen").path("screen_id").asText()).isEqualTo(screen.id.toString())
        assertThat(payload.path("screen").hasNonNull("capture_url")).isFalse
    }

    // ---------- 답이 앉는다 ----------

    /** `screen.name` 은 V40 이 만든 뒤로 한 번도 쓰인 적이 없다. 여기가 그 0 을 깨는 자리다. */
    @Test
    fun `답이 오면 screen name 에 앉는다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))
        val screen = screens.findBySceneIdOrderByIdAsc(title).toList().single()

        answer(world, asked().single(), name = "이어하기가 켜진 타이틀")

        assertThat(screens.findById(screen.id!!)!!.name).isEqualTo("이어하기가 켜진 타이틀")
        val logged = qaLogs.findPage(world.qaTryId, null, 100).toList()
            .filter { it.type == ScreenNameFrames.NAME }
        assertThat(logged.single().direction).isEqualTo("AGENT_TO_ORCHE")
        assertThat(logged.single().message).contains("이어하기가 켜진 타이틀")
    }

    /**
     * **이미 이름이 있으면 덮지 않는다.** 판정을 `WHERE name IS NULL` 이 한다.
     *
     * 화면을 합치는 `fold_scene_screens`(V67) 가 남길 행에 다른 행의 이름을 이어 나르므로, 늦게
     * 도착한 답이 그렇게 앉은 이름을 덮으면 안 된다. 서버가 둘이면 같은 화면에 두 답이 올 수도 있다.
     */
    @Test
    fun `두 번째 이름이 첫 이름을 덮지 않는다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))
        val screen = screens.findBySceneIdOrderByIdAsc(title).toList().single()
        answer(world, asked().single(), name = "첫 이름")

        val named = screens.nameIfAbsent(screen.id!!, "두 번째 이름")

        assertThat(named).isZero()
        assertThat(screens.findById(screen.id!!)!!.name).isEqualTo("첫 이름")
    }

    // ---------- 못 쓸 답 ----------

    /**
     * **`null` 은 정상적인 답이다** — "이 화면을 무엇이라 부를지 모르겠다" 이고, 스키마를 채우려고
     * 지어낸 이름보다 빈 칸이 낫다(ARTEL-909).
     *
     * 그 답을 받고도 계기가 남으면 같은 화면을 볼 때마다 다시 묻게 된다. 그래서 답을 받은 뒤 같은
     * 화면을 몇 번 더 봐도 질문이 늘지 않는 것을 함께 본다 — 묻는 계기가 `name IS NULL` 이 아니라
     * 행이 처음 생긴 그 한 번이기 때문이다.
     */
    @Test
    fun `이름이 null 인 답은 칸을 비운 채 끝난다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))
        val screen = screens.findBySceneIdOrderByIdAsc(title).toList().single()
        answer(world, asked().single(), name = null, note = "무슨 화면인지 알 수 없다")

        assertThat(screens.findById(screen.id!!)!!.name).isNull()

        repeat(3) { observeTwice(world, whole("TitleScene", active = listOf(CONTINUE))) }
        assertThat(asked()).hasSize(1)
    }

    /**
     * 빈 문자열과 60자 초과는 안 쓴다.
     *
     * 컬럼은 `VARCHAR(255)` 라 61자도 들어가기는 한다. 그래서 이것은 DB 가 아니라 표시 규약이고,
     * 그 값을 agent-server 와 맞췄다(ARTEL-909) — content map 의 한 칸과 QA agent 의 매 턴 문장에
     * 60 자를 넘겨 들어가는 것은 이름이 아니라 설명이다.
     */
    @Test
    fun `빈 이름과 60자 초과는 쓰지 않는다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))
        observeTwice(world, whole("TitleScene", deactive = listOf(CONTINUE)))
        val rows = screens.findBySceneIdOrderByIdAsc(title).toList()
        assertThat(rows).hasSize(2)

        val questions = asked().sortedBy { it.payload.path("screen").path("screen_id").asText().toLong() }
        answer(world, questions.first(), name = "   ")
        answer(world, questions.last(), name = "가".repeat(ScreenNameService.MAX_NAME_LENGTH + 1))

        assertThat(screens.findById(rows.first().id!!)!!.name).isNull()
        assertThat(screens.findById(rows.last().id!!)!!.name).isNull()
    }

    /** 물어본 적 없는 `request_id` 로 온 답은 아무 화면에도 앉지 않는다. */
    @Test
    fun `모르는 request_id 의 답은 버린다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))
        val screen = screens.findBySceneIdOrderByIdAsc(title).toList().single()

        inboundRouter.handle(
            QaAgentEnvelope(
                messageId = UUID.randomUUID().toString(),
                type = ScreenNameFrames.NAME,
                qaTryId = world.qaTryId.toString(),
                correlationId = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                payload = objectMapper.readTree("""{"name":"남의 화면"}"""),
            )
        )

        assertThat(screens.findById(screen.id!!)!!.name).isNull()
    }

    // ---------- 이름이 없어도 런은 돈다 ----------

    /**
     * **물어볼 상대가 없어도 `screen` 적재는 그대로 끝난다.** 이름은 content map 의 전제가 아니다.
     *
     * agent 세션이 아직 안 붙은 상태(STARTING)를 만든다 — 런 초반에 실제로 있는 상태다.
     */
    @Test
    fun `물어볼 상대가 없어도 화면 적재가 계속된다`(): Unit = runBlocking {
        val world = newWorld(agentSessionId = null)
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))
        observeTwice(world, whole("TitleScene", deactive = listOf(CONTINUE)))

        assertThat(screens.findBySceneIdOrderByIdAsc(title).toList()).hasSize(2)
        assertThat(asked()).isEmpty()
    }

    // ---------- 픽스처 ----------

    private data class World(
        val gameInstanceId: Long,
        val gameBuildId: Long,
        val contentMapId: Long,
        val qaTryId: Long,
    )

    private val CONTINUE = "Canvas[2]/continue[1]"

    private fun asked() = recorder.sent.filter { it.type == ScreenNameFrames.REQUEST }

    private fun askedScreenIds() = asked().map { it.payload.path("screen").path("screen_id").asText() }

    /** agent 가 보내는 `SCREEN_NAME` 한 장을 그대로 흘려 넣는다. */
    private suspend fun answer(world: World, question: QaAgentEnvelope, name: String?, note: String? = null) {
        val payload = objectMapper.createObjectNode()
        payload.put("request_id", question.messageId)
        if (name == null) payload.putNull("name") else payload.put("name", name)
        if (note == null) payload.putNull("note") else payload.put("note", note)
        inboundRouter.handle(
            QaAgentEnvelope(
                messageId = UUID.randomUUID().toString(),
                type = ScreenNameFrames.NAME,
                qaTryId = world.qaTryId.toString(),
                correlationId = question.messageId,
                timestamp = Instant.now(),
                payload = payload,
            )
        )
    }

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

    private fun discriminatorOf(screenRef: JsonNode): List<Pair<String, Boolean>> =
        screenRef.path("discriminator")
            .map { it.path("selector").asText() to it.path("active").asBoolean() }

    /** 이 인스턴스로 나간 프레임들. SDK 대신 우리가 받아 본다. */
    private class SentFrames(private val frames: List<String>, private val objectMapper: ObjectMapper) {
        fun captureRequests(): List<JsonNode> = frames
            .map { objectMapper.readTree(it) }
            .filter { frame ->
                frame.path("actions").any { it.path("method").asText() == ScreenCaptureService.CAPTURE_SCREEN }
            }
    }

    /** 이 인스턴스에 SDK 가 붙은 것으로 만든다. 붙어 있어야 `capture_screen` 이 나간다. */
    private fun connect(world: World): SentFrames {
        val session = Mockito.mock(WebSocketSession::class.java)
        val frames = CopyOnWriteArrayList<String>()
        val instanceId = world.gameInstanceId.toString()
        val outbound = requireNotNull(sessionManager.register(instanceId, session))
        outbound.subscribe { frames.add(it) }
        connected += instanceId to session
        return SentFrames(frames, objectMapper)
    }

    /** `SessionManager` 는 싱글턴이라 붙여 둔 세션이 다음 테스트까지 남는다. */
    @AfterEach
    fun disconnect() {
        connected.forEach { (instanceId, session) -> sessionManager.removeSession(instanceId, session) }
        connected.clear()
    }

    private val connected = mutableListOf<Pair<String, WebSocketSession>>()

    /** SDK 가 답하는 `ACTION_RESULT` 한 장. 모양은 실측 프레임 그대로다. */
    private fun actionResult(request: JsonNode, captureId: String): String {
        val requestId = request.path("id").longValue()
        return """
            {"type":"ACTION_RESULT","id":9,"requestId":$requestId,"results":[
             {"id":$requestId,"success":true,"error":"","action":"capture_screen",
              "returnValue":{"captureId":"$captureId","mimeType":"image/jpeg",
                             "url":"https://example/$captureId.jpg"}}]}
        """.trimIndent()
    }

    /** SDK 가 ticket 을 받아 갔을 때 `QaCaptureService` 가 남기는 행 그대로. */
    private suspend fun newScreenshotLog(world: World, objectKey: String): QaLogEntity {
        val captureId = UUID.randomUUID().toString()
        val saved = qaLogs.save(
            QaLogEntity(
                qaTryId = world.qaTryId,
                messageId = captureId,
                direction = "SDK_TO_ORCHE",
                type = "SCREENSHOT",
                message = "전체 화면 을(를) 캡처했습니다.",
                payload = Json.of(
                    """{"captureId":"$captureId","objectKey":"$objectKey","contentType":"image/jpeg"}"""
                ),
            )
        )
        return requireNotNull(qaLogs.findById(saved.id!!))
    }

    private suspend fun newWorld(agentSessionId: String? = SESSION_ID): World {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(name = "screen-name-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
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
                evidenceDigest = "screen-name",
            )
        )
        val startedBy = newUser()
        val testRunId = testRuns.save(TestRunEntity(projectId = project.id!!, name = "런")).id!!
        val qaRun = qaRuns.save(
            QaRunEntity(
                testRunId = testRunId,
                gameInstanceId = instance.id!!,
                startedBy = startedBy,
                status = "RUNNING",
                startedAt = now,
            )
        )
        val scenario = testScenarios.save(TestScenarioEntity(projectId = project.id!!))
        val qaTry = qaTries.save(
            QaTryEntity(
                testScenarioId = scenario.id!!,
                gameInstanceId = instance.id!!,
                qaRunId = qaRun.id,
                startedBy = startedBy,
                agentSessionId = agentSessionId,
                status = "RUNNING",
                startedAt = now,
            )
        )
        return World(instance.id!!, build.id!!, contentMap.id!!, qaTry.id!!)
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

    private fun newUser(): Long =
        db.sql(
            "INSERT INTO app_user (display_name, nickname, user_tag) " +
                "VALUES ('screen-name', 'screen-name-' || gen_random_uuid(), '0000') RETURNING id"
        )
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().block()!!
}
