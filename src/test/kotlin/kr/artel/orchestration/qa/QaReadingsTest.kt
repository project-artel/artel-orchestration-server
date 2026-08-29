package kr.artel.orchestration.qa

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaRunEntity
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaReadingsService
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
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.test.StepVerifier
import java.time.Duration
import java.time.Instant

/**
 * **런이 판독을 켜고 끈다** (ARTEL-507).
 *
 * 판독 사슬은 세 레포에 다 들어와 있었는데도 한 줄도 흐르지 않았다 — `start_readings` 를 보내는
 * 쪽이 없었기 때문이다. 이 파일이 지키는 것은 그 보내는 쪽이고, 특히 **언제 끄지 않는가** 다.
 *
 * 끄는 조건을 한 곳에 모은 이유가 여기서 드러난다. 시도가 종단되는 자리가 넷인데(정상 종단,
 * 실패 두 경로, 취소) 각자 판단하면 시나리오가 여럿인 런에서 첫 시나리오가 끝나자마자 꺼지고,
 * 나머지는 판독 없이 돈다. 그 회귀는 로그를 보지 않으면 드러나지 않는다.
 */
@ActiveProfiles("test")
@SpringBootTest
class QaReadingsTest {

    @Autowired private lateinit var readings: QaReadingsService
    @Autowired private lateinit var sessionManager: SessionManager
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var gameInstances: GameInstanceRepository
    @Autowired private lateinit var tries: QaTryRepository
    @Autowired private lateinit var runs: QaRunRepository
    @Autowired private lateinit var scenarios: TestScenarioRepository
    @Autowired private lateinit var testRuns: TestRunRepository
    @Autowired private lateinit var db: DatabaseClient

    /**
     * 이 스위트가 남긴 행을 치운다.
     *
     * 없으면 `newTry` · `newRun` 이 만든 `qa_try` · `qa_run` 이 스위트 끝까지 살아남아, **뒤에 도는
     * 다른 클래스의** `DELETE FROM app_user` · `DELETE FROM game_instance` 가
     * `qa_try_started_by_fkey` 같은 제약으로 막힌다. 실패가 이 파일이 아니라 남의 파일에서 나므로
     * 원인을 찾기 어렵고, 클래스 실행 순서가 바뀔 때마다 피해자가 달라진다.
     *
     * 리액티브 트랜잭션은 롤백되지 않고 DB 를 공유하므로 FK 순서대로 직접 비운다.
     */
    @BeforeEach
    @AfterEach
    fun clean(): Unit = runBlocking {
        tries.deleteAll()
        runs.deleteAll()
        testRuns.deleteAll()
        gameInstances.deleteAll()
        scenarios.deleteAll()
        projects.deleteAll()
        db.sql("DELETE FROM app_user WHERE display_name = 'readings'").then().block()
    }

    private val objectMapper = ObjectMapper()

    /**
     * 런이 시작하면 `start_readings` 가 나간다.
     *
     * 액션 이름을 문자열로 단언하는 이유: ARTEL-417 이 SDK 쪽에 낸 이름과 맞춘 계약이고, 여기서
     * 바뀌면 상대편은 컴파일 오류 없이 조용히 못 알아듣는다. 판독이 안 나오는 것으로만 드러난다.
     */
    @Test
    fun `런이 시작하면 start_readings 가 나간다`(): Unit = runBlocking {
        val instanceId = newInstance()
        val outbound = requireNotNull(
            sessionManager.register(instanceId.toString(), Mockito.mock(WebSocketSession::class.java))
        )

        readings.start(instanceId)

        StepVerifier.create(outbound)
            .assertNext { json ->
                val sent = objectMapper.readTree(json)
                assertThat(sent["type"].asText()).isEqualTo("ACTION")
                val action = sent["actions"][0]
                assertThat(action["method"].asText()).isEqualTo("start_readings")
                // 파라미터는 없다. 무엇을 볼지는 근거가 이미 정했다.
                assertThat(action["params"]).isEmpty()
            }
            .thenCancel()
            .verify(Duration.ofSeconds(5))

        release(instanceId)
    }

    /**
     * 살아 있는 시도가 남아 있으면 끄지 않는다.
     *
     * **이것이 이 이슈에서 가장 틀리기 쉬운 자리다.** 시나리오가 여럿인 런은 시도 하나가 끝나도
     * 다음이 남아 있고, 그때 끄면 나머지 시나리오가 통째로 판독 없이 돈다.
     */
    @Test
    fun `살아 있는 시도가 남아 있으면 끄지 않는다`(): Unit = runBlocking {
        val instanceId = newInstance()
        val outbound = requireNotNull(
            sessionManager.register(instanceId.toString(), Mockito.mock(WebSocketSession::class.java))
        )
        newTry(instanceId, status = "RUNNING")

        readings.stopIfIdle(instanceId)

        // 아무것도 나가지 않았다는 것을 증명한다. 무언가 나갔다면 이 창에서 잡힌다.
        StepVerifier.create(outbound)
            .expectSubscription()
            .expectNoEvent(Duration.ofMillis(300))
            .thenCancel()
            .verify(Duration.ofSeconds(5))

        release(instanceId)
    }

    /** 살아 있는 런이 남아 있어도 끄지 않는다 — 시도가 아직 PENDING 인 구간이 그렇다. */
    @Test
    fun `살아 있는 런이 남아 있으면 끄지 않는다`(): Unit = runBlocking {
        val instanceId = newInstance()
        val outbound = requireNotNull(
            sessionManager.register(instanceId.toString(), Mockito.mock(WebSocketSession::class.java))
        )
        newRun(instanceId, status = "RUNNING")

        readings.stopIfIdle(instanceId)

        StepVerifier.create(outbound)
            .expectSubscription()
            .expectNoEvent(Duration.ofMillis(300))
            .thenCancel()
            .verify(Duration.ofSeconds(5))

        release(instanceId)
    }

    /** 남은 것이 없으면 `stop_readings` 가 나간다. 종단된 시도가 있어도 마찬가지다. */
    @Test
    fun `남은 것이 없으면 stop_readings 가 나간다`(): Unit = runBlocking {
        val instanceId = newInstance()
        val outbound = requireNotNull(
            sessionManager.register(instanceId.toString(), Mockito.mock(WebSocketSession::class.java))
        )
        newTry(instanceId, status = "COMPLETED")

        readings.stopIfIdle(instanceId)

        StepVerifier.create(outbound)
            .assertNext { json ->
                val action = objectMapper.readTree(json)["actions"][0]
                assertThat(action["method"].asText()).isEqualTo("stop_readings")
            }
            .thenCancel()
            .verify(Duration.ofSeconds(5))

        release(instanceId)
    }

    /**
     * 붙어 있지 않아도 던지지 않는다.
     *
     * 판독은 관측 채널이지 런의 전제가 아니다. 여기서 던지면 런 생성이 통째로 되돌아가, 판독을
     * 낼 수 없는 게임에서는 QA 자체가 시작되지 않는다.
     */
    @Test
    fun `세션이 없어도 던지지 않는다`(): Unit = runBlocking {
        val instanceId = newInstance()

        readings.start(instanceId)
        readings.stopIfIdle(instanceId)
    }

    // ---------- 픽스처 ----------

    private fun newUser(): Long =
        db.sql("INSERT INTO app_user (display_name) VALUES ('readings') RETURNING id")
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().block()!!

    private suspend fun release(instanceId: Long) {
        sessionManager.getSession(instanceId.toString())
            ?.let { sessionManager.removeSession(instanceId.toString(), it) }
    }

    /** 인스턴스와 그 프로젝트. 프로젝트 id 는 시나리오·런의 FK 를 채우는 데 쓴다. */
    private suspend fun newInstance(): Long = newProjectAndInstance().second

    private suspend fun newProjectAndInstance(): Pair<Long, Long> {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(name = "readings-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
        )
        val instanceId = gameInstances.save(
            GameInstanceEntity(
                projectId = project.id!!,
                name = "instance",
                platform = "UNITY",
                lastConnectedAt = now,
                createdAt = now,
                updatedAt = now,
            )
        ).id!!
        return project.id to instanceId
    }

    private suspend fun newTry(gameInstanceId: Long, status: String) {
        val now = Instant.now()
        val projectId = gameInstances.findById(gameInstanceId)!!.projectId
        val scenarioId = scenarios.save(TestScenarioEntity(projectId = projectId)).id!!
        val userId = newUser()
        tries.save(
            QaTryEntity(
                testScenarioId = scenarioId,
                gameInstanceId = gameInstanceId,
                startedBy = userId,
                status = status,
                startedAt = now,
                completedAt = if (status == "RUNNING" || status == "STARTING") null else now,
            )
        )
    }

    private suspend fun newRun(gameInstanceId: Long, status: String) {
        val now = Instant.now()
        val projectId = gameInstances.findById(gameInstanceId)!!.projectId
        val testRunId = testRuns.save(TestRunEntity(projectId = projectId, name = "런")).id!!
        val userId = newUser()
        runs.save(
            QaRunEntity(
                testRunId = testRunId,
                gameInstanceId = gameInstanceId,
                startedBy = userId,
                status = status,
                startedAt = now,
            )
        )
    }
}
