package kr.artel.orchestration.contentmap.capture

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
import kr.artel.orchestration.contentmap.observe.ScreenObservationService
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.contentmap.repository.ScreenRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaLogEntity
import kr.artel.orchestration.qa.entity.QaRunEntity
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.socket.WebSocketSession
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 새 화면을 만든 자리에서 capture 를 찍어 화면에 묶는다 (ARTEL-456).
 *
 * 이 파일이 지키는 것은 셋이다.
 *
 * 1. **처음 앉힐 때만 청구한다.** `ScreenRepository.observe` 는 upsert 라 새로 만든 것과 다시 본
 *    것을 구분하지 못한다. 그 구분이 무너지면 화면을 볼 때마다 다시 찍혀서 "처음 것만 남긴다"
 *    가 그 자리에서 무너진다 — 이 이슈에서 틀리기 가장 쉬운 자리라 첫 테스트가 그것이다
 * 2. **결과가 그 화면 행에 남는다.** `image_object_key` 와 `image_captured_at` 이 채워지고,
 *    두 번째 결과가 첫 그림을 덮지 않는다
 * 3. **실패해도 화면은 남는다.** 붙은 SDK 가 없어도, 게임이 못 찍었다고 답해도 화면 행은 그대로다
 *
 * 하나 더 있다. 이 라우터는 `ACTION_RESULT` 를 QA 브리지에서 갈라 가져오는데 `capture_screen` 은
 * **agent 도 보내는** 이름이라, agent 가 시킨 capture 의 결과를 건드리지 않는다는 것이 계약이다.
 * 마지막 테스트가 그것이다.
 */
@ActiveProfiles("test")
@SpringBootTest
class ScreenCaptureTest {

    @Autowired private lateinit var observation: ScreenObservationService
    @Autowired private lateinit var captureResults: ScreenCaptureResultRouter
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
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var db: DatabaseClient

    /**
     * **이 이슈가 조용히 틀릴 수 있는 유일한 자리다.**
     *
     * 두 화면을 번갈아 보면 두 화면 모두 upsert 를 세 번씩 지난다. 그 중 새로 앉히는 것은 각각 한
     * 번뿐이므로 청구도 둘이어야 한다. `observe` 가 insert 와 update 를 가르지 못하면 여섯이 된다.
     */
    @Test
    fun `같은 화면을 다시 봐도 capture 를 다시 청구하지 않는다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)
        val sent = connect(world)

        repeat(3) {
            observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))
            observeTwice(world, whole("TitleScene", deactive = listOf(CONTINUE)))
        }

        assertThat(screens.findBySceneIdOrderByIdAsc(title).toList()).hasSize(2)
        // 화면 둘에 청구 둘. 관측 수가 아니라 화면 수를 따라간다.
        assertThat(sent.captureRequests()).hasSize(2)
        assertThat(sent.captureRequests().map { it.path("id").longValue() }).doesNotHaveDuplicates()
    }

    /** 결과가 돌아오면 그 화면 행이 그림을 갖는다. 이 칸이 지금 실측에서 전부 비어 있다. */
    @Test
    fun `capture 결과가 화면에 objectKey 와 시각을 남긴다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)
        val sent = connect(world)

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))
        val screen = screens.findBySceneIdOrderByIdAsc(title).toList().single()

        val screenshot = newScreenshotLog(world, "qa-captures/${world.qaTryId}/title.jpg")
        val handled = captureResults.handle(
            world.gameInstanceId,
            actionResult(sent.captureRequests().single(), screenshot.messageId!!),
        )

        assertThat(handled).isTrue()
        val stored = screens.findById(screen.id!!)!!
        assertThat(stored.imageObjectKey).isEqualTo("qa-captures/${world.qaTryId}/title.jpg")
        // ticket 을 받은 순간이 촬영 시각이다. 결과 프레임이 서버에 닿은 시각이 아니다.
        assertThat(stored.imageCapturedAt).isEqualTo(screenshot.createdAt)
    }

    /**
     * **처음 것만 남긴다.** 화면이 무엇인지 말하는 그림은 그 화면을 처음 만나 화면이라고 판정한
     * 순간의 것이다.
     *
     * 청구는 화면당 한 번뿐이지만 서버가 둘이면 각자 한 번씩 청구할 수 있다. 그때 나중 것이 앞
     * 그림을 덮지 않는다는 것을 SQL 이 강제한다.
     */
    @Test
    fun `두 번째 capture 결과가 첫 그림을 덮지 않는다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)
        val sent = connect(world)

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))
        val screen = screens.findBySceneIdOrderByIdAsc(title).toList().single()
        val request = sent.captureRequests().single()

        val first = newScreenshotLog(world, "qa-captures/${world.qaTryId}/first.jpg")
        captureResults.handle(world.gameInstanceId, actionResult(request, first.messageId!!))

        // 다른 서버가 같은 화면에 두 번째 그림을 묶으려 한 것과 같다. 막는 것은 SQL 이다.
        val attached = screens.attachImageIfAbsent(
            screen.id!!,
            "qa-captures/${world.qaTryId}/second.jpg",
            Instant.now(),
        )

        assertThat(attached).isZero()
        assertThat(screens.findById(screen.id!!)!!.imageObjectKey)
            .isEqualTo("qa-captures/${world.qaTryId}/first.jpg")
    }

    /**
     * **그림 없는 화면이 화면 없는 지도보다 낫다.**
     *
     * 붙은 SDK 가 없으면 보낼 곳이 없다. 그 사실이 화면 관측을 멈추게 해서는 안 된다.
     */
    @Test
    fun `붙은 SDK 가 없어도 화면 행은 남는다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))

        val screen = screens.findBySceneIdOrderByIdAsc(title).toList().single()
        assertThat(screen.imageObjectKey).isNull()
    }

    /** 게임이 못 찍었다고 답해도 마찬가지다. `capture_screen` 을 모르는 빌드가 여기로 온다. */
    @Test
    fun `게임이 capture 에 실패해도 화면 행은 남는다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)
        val sent = connect(world)

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))
        val screen = screens.findBySceneIdOrderByIdAsc(title).toList().single()

        val request = sent.captureRequests().single()
        val requestId = request.path("id").longValue()
        val failure = """
            {"type":"ACTION_RESULT","id":9,"requestId":$requestId,"results":[
             {"id":$requestId,"success":false,"error":"Unsupported method: capture_screen",
              "action":"capture_screen"}]}
        """.trimIndent()

        assertThat(captureResults.handle(world.gameInstanceId, failure)).isTrue()
        assertThat(screens.findById(screen.id!!)!!.imageObjectKey).isNull()
    }

    /**
     * **agent 도 `capture_screen` 을 보낸다.** 그래서 가르는 축이 action 이름일 수 없다.
     *
     * 우리가 청구한 적 없는 번호의 프레임은 건드리지 않고 `false` 로 돌려준다 — 그것이 QA 브리지로
     * 가야 agent 의 vision 이 멎지 않는다.
     */
    @Test
    fun `agent 가 시킨 capture 의 결과를 가로채지 않는다`(): Unit = runBlocking {
        val world = newWorld()

        val agentFrame = """
            {"type":"ACTION_RESULT","id":9,"requestId":424242,"results":[
             {"id":1,"success":true,"action":"capture_screen",
              "returnValue":{"captureId":"someone-elses","url":"https://example/x.jpg"}}]}
        """.trimIndent()

        assertThat(captureResults.handle(world.gameInstanceId, agentFrame)).isFalse()
    }

    // ---------- 픽스처 ----------

    private data class World(val gameInstanceId: Long, val contentMapId: Long, val qaTryId: Long)

    private val CONTINUE = "Canvas[2]/continue[1]"

    /** 이 인스턴스로 나간 프레임들. SDK 대신 우리가 받아 본다. */
    private class SentFrames(private val frames: List<String>, private val objectMapper: ObjectMapper) {
        fun captureRequests(): List<JsonNode> = frames
            .map { objectMapper.readTree(it) }
            .filter { frame ->
                frame.path("actions").any { it.path("method").asText() == ScreenCaptureService.CAPTURE_SCREEN }
            }
    }

    /**
     * 이 인스턴스에 SDK 가 붙은 것으로 만든다.
     *
     * `SessionManager.register` 가 돌려주는 것이 그 세션으로 나갈 메시지들이라, 그것을 구독하면
     * SDK 자리에서 프레임을 그대로 받아 볼 수 있다. 실제 소켓을 띄우지 않고도 "무엇이 나갔나" 를
     * 볼 수 있는 자리가 여기뿐이다.
     */
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
             "active":[$activeJson],"deactive":[$deactiveJson],"unwatchable":0}
        """.trimIndent()
    }

    private fun plain(selector: String) = """{"selector":"$selector","path":"$selector"}"""

    /**
     * SDK 가 답하는 `ACTION_RESULT` 한 장.
     *
     * 모양은 실측 프레임 그대로다 — 결과가 최상위가 아니라 `results[]` 에 실리고, `requestId` 가
     * 우리가 보낸 바깥 번호를 되돌려준다.
     */
    private fun actionResult(request: JsonNode, captureId: String): String {
        val requestId = request.path("id").longValue()
        return """
            {"type":"ACTION_RESULT","id":9,"requestId":$requestId,"results":[
             {"id":$requestId,"success":true,"error":"","action":"capture_screen",
              "returnValue":{"captureId":"$captureId","mimeType":"image/jpeg",
                             "url":"https://example/$captureId.jpg"}}]}
        """.trimIndent()
    }

    /**
     * SDK 가 ticket 을 받아 갔을 때 `QaCaptureService` 가 남기는 행 그대로.
     *
     * 이 행이 `objectKey` 를 아는 유일한 자리다. 라우터가 그것을 되짚어 읽으므로, key 규칙을
     * 테스트에서 다시 조립하지 않고 이 행에 적어 둔 값을 그대로 기대한다.
     */
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
        // `created_at` 은 DB 기본값이라 저장이 돌려준 객체에는 없다. 그 값이 곧 촬영 시각이므로
        // 다시 읽어서 준다.
        return requireNotNull(qaLogs.findById(saved.id!!))
    }

    private suspend fun newWorld(): World {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(name = "capture-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
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
                evidenceDigest = "screen-capture",
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
        // 화면 관측은 활성 `qa_run` 을 보고, capture 청구는 활성 `qa_try` 를 본다 — ticket 을 발급하는
        // `QaCaptureService` 가 그것을 보기 때문이다. 둘 다 있어야 실제 런과 같은 모양이 된다.
        val scenario = testScenarios.save(TestScenarioEntity(projectId = project.id!!))
        val qaTry = qaTries.save(
            QaTryEntity(
                testScenarioId = scenario.id!!,
                gameInstanceId = instance.id!!,
                qaRunId = qaRun.id,
                startedBy = startedBy,
                status = "RUNNING",
                startedAt = now,
            )
        )
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

    private fun newUser(): Long =
        db.sql("INSERT INTO app_user (display_name) VALUES ('capture') RETURNING id")
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().block()!!
}
