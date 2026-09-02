package kr.artel.orchestration.contentmap

import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.contentmap.entity.Actionability
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.entity.CapabilityOrigin
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.ContentMapRoot
import kr.artel.orchestration.contentmap.entity.Interaction
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.entity.SceneOrigin
import kr.artel.orchestration.contentmap.observe.ScreenObservationService
import kr.artel.orchestration.contentmap.observe.ScreenSelectorFrames
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
import kr.artel.orchestration.qa.entity.QaRunEntity
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaAgentEnvelope
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
import java.util.concurrent.CopyOnWriteArrayList

private const val SESSION_ID = "screen-settled-session"

/**
 * 관측이 확정한 화면을 agent 에게 알린다 (ARTEL-668).
 *
 * 이 파일이 지키는 것은 넷이다.
 *
 * 1. **제안이 한 장도 안 나가는 런에서도 화면이 간다** — 이 이슈가 존재하는 이유다. 제안은
 *    `(scene, selector)` 마다 평생 한 번뿐이라, 이미 한 번 플레이한 빌드에서는 화면을 실어 나를
 *    프레임이 없었다
 * 2. **`pulse` 마다 보내지 않는다** — 실측 런의 `pulse` 가 14489 개다
 * 3. **화면을 못 가른 것도 사실대로 보낸다** — 목록이 비어 씬 전체가 화면 하나인 상태가 오류가
 *    아니라는 것은 ARTEL-654 가 정했고, agent 가 그것을 보고 목록을 고치는 것이 ARTEL-657 이다
 * 4. **못 보내도 런이 계속된다** — 통보는 관측의 곁가지다
 */
@ActiveProfiles("test")
@SpringBootTest
class ScreenSettledFrameTest {

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
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var gameInstances: GameInstanceRepository
    @Autowired private lateinit var testRuns: TestRunRepository
    @Autowired private lateinit var testScenarios: TestScenarioRepository
    @Autowired private lateinit var qaRuns: QaRunRepository
    @Autowired private lateinit var qaTries: QaTryRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var scenes: SceneRepository
    @Autowired private lateinit var capabilities: CapabilityRepository
    @Autowired private lateinit var screens: ScreenRepository
    @Autowired private lateinit var qaLogs: QaLogRepository
    @Autowired private lateinit var agentPort: QaAgentPort
    @Autowired private lateinit var db: DatabaseClient

    private val recorder: RecordingAgentPort get() = agentPort as RecordingAgentPort

    @BeforeEach
    fun clearSent() {
        recorder.sent.clear()
    }

    // ---------- 화면이 간다 ----------

    /**
     * 확정한 화면과 그것을 가른 selector, 그리고 직전 화면이 실린다.
     *
     * 답하는 쪽은 이 셋으로 "지도가 나를 어디에 세워 두었나" 를 읽는다. 화면 번호만 실으면 무엇으로
     * 가른 화면인지 모른 채 번호만 읽게 되고, 목록을 고칠 판단이 서지 않는다.
     */
    @Test
    fun `화면이 굳으면 그 화면과 직전 화면이 실려 나간다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))
        observeTwice(world, whole("TitleScene", deactive = listOf(CONTINUE)))

        val rows = screens.findBySceneIdOrderByIdAsc(title).toList()
        assertThat(rows).hasSize(2)

        val settled = settledSent()
        assertThat(settled).hasSize(2)

        val first = settled.first().payload
        assertThat(first.path("scene").path("name").asText()).isEqualTo("TitleScene")
        assertThat(first.path("scene").path("scene_id").asText()).isEqualTo(title.toString())
        assertThat(first.path("current_screen").path("screen_id").asText())
            .isEqualTo(rows.first().id.toString())
        assertThat(discriminatorOf(first.path("current_screen"))).containsExactly(CONTINUE to true)
        // 런의 첫 화면이라 직전이 없다. 없는 것을 지어내면 answering 쪽이 오지 않은 전이를 읽는다.
        assertThat(first.hasNonNull("previous_screen")).isFalse

        val second = settled.last().payload
        assertThat(second.path("current_screen").path("screen_id").asText())
            .isEqualTo(rows.last().id.toString())
        assertThat(discriminatorOf(second.path("current_screen"))).containsExactly(CONTINUE to false)
        assertThat(second.path("previous_screen").path("screen_id").asText())
            .isEqualTo(rows.first().id.toString())
    }

    /**
     * **이 이슈가 존재하는 이유다.**
     *
     * 화면을 실어 나르던 프레임이 `SCREEN_SELECTOR_PROPOSAL` 하나였고, 그것은
     * `(scene, selector)` 마다 평생 한 번만 나간다. 그래서 이미 한 번 플레이한 빌드에서는 제안이
     * 한 장도 안 나가고 agent 는 런 내내 화면을 못 봤다.
     *
     * 여기서 그 빌드를 만든다 — 한 바퀴 돌려 물어볼 것을 전부 물어본 뒤, 기록을 지우고 같은 씬을
     * 다시 돈다. 두 번째 바퀴에서 제안은 0 이고 화면 통보는 그대로 나가야 한다.
     */
    @Test
    fun `제안이 한 장도 안 나가는 런에서도 화면이 간다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)

        // 첫 바퀴 — 목록 밖 selector 를 물어본다.
        playOneLap(world)
        assertThat(proposalsSent()).isNotEmpty
        assertThat(settledSent()).hasSize(2)

        // 두 번째 바퀴 — 같은 것을 그대로 다시 본다. `uk_screen_selector_proposal` 이 이미 물어본
        // 것을 막으므로 제안은 한 장도 안 나간다. 그것이 이미 플레이한 빌드의 상태다.
        recorder.sent.clear()
        playOneLap(world)

        assertThat(proposalsSent()).isEmpty()
        assertThat(settledSent()).hasSize(2)
        val rows = screens.findBySceneIdOrderByIdAsc(title).toList()
        assertThat(settledSent().map { it.payload.path("current_screen").path("screen_id").asText() })
            .containsExactly(rows.first().id.toString(), rows.last().id.toString())
    }

    /** `CONTINUE` 를 껐다 켠다. `UNKNOWN` 은 내내 켜져 있고 목록 밖이라 화면을 가르지 않는다. */
    private suspend fun playOneLap(world: World) {
        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE, UNKNOWN)))
        observeTwice(world, whole("TitleScene", active = listOf(UNKNOWN), deactive = listOf(CONTINUE)))
    }

    /**
     * **실측 런의 `pulse` 가 14489 개다.** 같은 화면에 머무는 동안 같은 말을 반복하면 agent 의
     * 컨텍스트가 그것으로 찬다.
     */
    @Test
    fun `화면이 안 바뀐 pulse 에서는 다시 안 보낸다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)

        repeat(8) { observation.observe(world.gameInstanceId, whole("TitleScene", active = listOf(CONTINUE))) }

        assertThat(screens.findBySceneIdOrderByIdAsc(title).toList()).hasSize(1)
        assertThat(settledSent()).hasSize(1)
    }

    /**
     * 목록이 비어 씬 전체가 화면 하나인 상태를 **그대로** 알린다.
     *
     * 오류로 보고 안 보내면 안 된다. 그것이 옳은 동작이라는 것은 ARTEL-654 가 정했고, agent 가
     * 목록을 고쳐야 한다는 것을 알아챌 유일한 신호가 바로 이 빈 `discriminator` 다 (ARTEL-657).
     */
    @Test
    fun `목록이 빈 씬은 화면 하나라고 그대로 알린다`(): Unit = runBlocking {
        val world = newWorld()
        val lobby = newScene(world, "LobbyScene")

        observeTwice(world, whole("LobbyScene", active = listOf(CONTINUE)))

        val settled = settledSent().single()
        assertThat(discriminatorOf(settled.payload.path("current_screen"))).isEmpty()
        assertThat(settled.payload.path("current_screen").path("screen_id").asText())
            .isEqualTo(screens.findBySceneIdOrderByIdAsc(lobby).toList().single().id.toString())
    }

    /** 타임라인에 남는다. "그때 지도가 뭐라고 했나" 를 되짚을 자리가 여기 말고 없다. */
    @Test
    fun `통보가 qa_log 에 남는다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))

        val logged = qaLogs.findPage(world.qaTryId, null, 100).toList()
            .filter { it.type == ScreenSelectorFrames.SETTLED }
        assertThat(logged).hasSize(1)
        assertThat(logged.single().direction).isEqualTo("ORCHE_TO_AGENT")
        // 답이 없는 통보다. correlation 을 달면 마침 같은 값을 기다리던 요청이 이것으로 풀린다.
        assertThat(settledSent().single().correlationId).isNull()
    }

    /**
     * 보낼 곳이 없어도 화면 기록은 그대로 돈다. 통보는 관측의 곁가지다.
     *
     * agent 세션이 아직 안 붙은 상태(STARTING)를 만든다 — 실제로 런 초반에 있는 상태이고, 그때
     * 화면 적재가 멈추면 그 구간의 관측이 통째로 사라진다.
     */
    @Test
    fun `보낼 곳이 없어도 화면 기록이 계속된다`(): Unit = runBlocking {
        val world = newWorld(agentSessionId = null)
        val title = newScene(world, "TitleScene")
        newCapability(world, title, CONTINUE)

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))
        observeTwice(world, whole("TitleScene", deactive = listOf(CONTINUE)))

        assertThat(screens.findBySceneIdOrderByIdAsc(title).toList()).hasSize(2)
        assertThat(settledSent()).isEmpty()
    }

    // ---------- 근거가 모르는 씬 ----------

    /**
     * **ARTEL-689 가 존재하는 이유다.**
     *
     * 지도가 `TitleScene` 하나만 아는데 `pulse` 가 `BonusScene` 을 대는 상황이다. 전에는
     * `ScreenObservationService.record` 가 씬 조회에서 조용히 돌아서서 씬도 화면도 프레임도 0 이었다.
     * 이제 씬이 `origin='observed'` 로 앉고 나머지 경로가 `evidence` 씬과 똑같이 돈다.
     *
     * 프레임 둘을 함께 보는 것은 이 씬에서 잃던 것이 행만이 아니었기 때문이다 — agent 는
     * `SCREEN_SETTLED` 로 자기가 어디 서 있는지 알고 `SCREEN_SELECTOR_PROPOSAL` 로 목록을 고친다.
     */
    @Test
    fun `지도에 없던 씬을 댄 pulse 가 씬과 화면과 프레임을 만든다`(): Unit = runBlocking {
        val world = newWorld()
        val title = newScene(world, "TitleScene")

        observeTwice(world, whole("BonusScene", active = listOf(CONTINUE)))

        val observed = scenes.findByContentMapIdAndName(world.contentMapId, "BonusScene")!!
        assertThat(observed.origin).isEqualTo(SceneOrigin.OBSERVED.wire)
        // 근거가 말한 적 없는 씬이라 이 셋은 비어 있는 것이 정상이다.
        assertThat(observed.capture).isNull()
        assertThat(observed.summary).isNull()
        assertThat(observed.walked).isFalse()
        // 원래 있던 씬은 그대로 `evidence` 다. 관측이 남의 출처를 덮지 않는다.
        assertThat(scenes.findById(title)!!.origin).isEqualTo(SceneOrigin.EVIDENCE.wire)

        val screen = screens.findBySceneIdOrderByIdAsc(observed.id!!).toList().single()

        val settled = settledSent().single()
        assertThat(settled.payload.path("scene").path("name").asText()).isEqualTo("BonusScene")
        assertThat(settled.payload.path("scene").path("scene_id").asText())
            .isEqualTo(observed.id.toString())
        assertThat(settled.payload.path("current_screen").path("screen_id").asText())
            .isEqualTo(screen.id.toString())

        // 목록이 빈 씬이라 `CONTINUE` 가 목록 밖이고, 그것을 넣을지 물어본다.
        assertThat(proposalsSent()).isNotEmpty
    }

    /**
     * 근거 문서가 한 번도 안 올라온 빌드다. **씬만 만들어서는 이 경우가 안 고쳐진다** — `content_map`
     * 행이 생기는 자리가 문서 등록 하나뿐이라, 지도가 없으면 씬을 만들 기회조차 없다(ARTEL-642 가
     * 연 길이 실제로 안 돌던 이유가 이것이다).
     *
     * 헤더 셋이 null 로 남는 것을 함께 본다. 관측은 그것을 말할 자격이 없고, 더미값을 넣으면 진짜
     * 헤더와 같은 칸에 앉는다(V63 4절).
     */
    @Test
    fun `지도가 없는 빌드에서 관측이 지도부터 세운다`(): Unit = runBlocking {
        val world = newWorld()
        contentMaps.deleteById(world.contentMapId)
        assertThat(contentMaps.findByGameBuildId(world.gameBuildId)).isNull()

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))

        val rooted = contentMaps.findByGameBuildId(world.gameBuildId)!!
        assertThat(rooted.rootedBy).isEqualTo(ContentMapRoot.OBSERVATION.wire)
        assertThat(rooted.schemaVersion).isNull()
        assertThat(rooted.evidenceDigest).isNull()
        assertThat(rooted.capture).isNull()

        val scene = scenes.findByContentMapIdAndName(rooted.id!!, "TitleScene")!!
        assertThat(scene.origin).isEqualTo(SceneOrigin.OBSERVED.wire)
        assertThat(screens.findBySceneIdOrderByIdAsc(scene.id!!).toList()).hasSize(1)
        assertThat(settledSent()).hasSize(1)
    }

    /**
     * `DontDestroyOnLoad` 는 Unity 가 씬 load 를 넘어 살아남는 오브젝트를 모아 두는 자리이고 아무도
     * 그리로 갈 수 없다. ARTEL-460 이 지도에서 그 행을 없앤 이유라, 관측이 도로 앉히면 안 된다.
     */
    @Test
    fun `DontDestroyOnLoad 는 씬으로 만들지 않는다`(): Unit = runBlocking {
        val world = newWorld()

        observeTwice(world, whole("DontDestroyOnLoad", active = listOf(CONTINUE)))

        assertThat(scenes.findByContentMapIdOrderByNameAsc(world.contentMapId).toList()).isEmpty()
        assertThat(settledSent()).isEmpty()
    }

    /**
     * 만들 수 없는 이름 셋이 와도 런이 그대로 이어진다. 옛 동작이 조용한 손실이었으니 새 동작이
     * 시끄러운 실패가 되면 안 된다.
     *
     * 255 자는 `scene.name VARCHAR(255)`(V40) 의 폭이다. 여기서 걸러 내지 않으면 `INSERT` 가
     * 던지고, 그 예외는 이유 없이 삼켜진다.
     */
    @Test
    fun `만들 수 없는 이름이 와도 다음 pulse 가 그대로 돈다`(): Unit = runBlocking {
        val world = newWorld()

        observeTwice(world, whole("", active = listOf(CONTINUE)))
        observeTwice(world, whole("x".repeat(256), active = listOf(CONTINUE)))
        observeTwice(world, whole("DontDestroyOnLoad", active = listOf(CONTINUE)))

        observeTwice(world, whole("TitleScene", active = listOf(CONTINUE)))

        assertThat(scenes.findByContentMapIdOrderByNameAsc(world.contentMapId).toList().map { it.name })
            .containsExactly("TitleScene")
        val title = scenes.findByContentMapIdAndName(world.contentMapId, "TitleScene")!!
        assertThat(screens.findBySceneIdOrderByIdAsc(title.id!!).toList()).hasSize(1)
        assertThat(settledSent()).hasSize(1)
    }

    // ---------- 픽스처 ----------

    private data class World(
        val gameInstanceId: Long,
        val gameBuildId: Long,
        val contentMapId: Long,
        val qaTryId: Long,
    )

    private val CONTINUE = "Canvas[2]/continue[1]"
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

    private fun settledSent() = recorder.sent.filter { it.type == ScreenSelectorFrames.SETTLED }

    private fun proposalsSent() = recorder.sent.filter { it.type == ScreenSelectorFrames.PROPOSAL }

    private fun discriminatorOf(screenRef: JsonNode): List<Pair<String, Boolean>> =
        screenRef.path("discriminator")
            .map { it.path("selector").asText() to it.path("active").asBoolean() }

    private suspend fun newWorld(agentSessionId: String? = SESSION_ID): World {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(name = "settled-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
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
                evidenceDigest = "screen-settled",
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
                agentSessionId = agentSessionId,
                status = "RUNNING",
                startedAt = now,
            )
        )!!
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
        db.sql("INSERT INTO app_user (display_name, nickname, user_tag) VALUES ('settled', 'settled-' || gen_random_uuid(), '0000') RETURNING id")
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().block()!!
}
