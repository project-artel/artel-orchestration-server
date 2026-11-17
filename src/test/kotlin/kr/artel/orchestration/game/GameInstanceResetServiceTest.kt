package kr.artel.orchestration.game

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.common.error.ApiException
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.game.service.GameInstanceResetService
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaRunEntity
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.support.deleteQaRuns
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
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
 * `reset_game` 을 QA 세션 밖에서 여는 문(ARTEL-803).
 *
 * [kr.artel.orchestration.contentmap.scan.ContentMapScanService] 가 세운 전례(붙어 있는지는
 * `SessionManager` 가 안다, 안 붙어 있으면 409)를 따르는지, 그리고 이 이슈가 새로 정하는 판단(활성
 * QA 런·시도가 있으면 어느 것이 인스턴스를 쥐고 있는지 말하며 거절한다)이 코드로 옮겨졌는지를 본다.
 */
@ActiveProfiles("test")
@SpringBootTest
class GameInstanceResetServiceTest {

    @Autowired private lateinit var reset: GameInstanceResetService
    @Autowired private lateinit var sessionManager: SessionManager
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var members: ProjectMemberRepository
    @Autowired private lateinit var gameInstances: GameInstanceRepository
    @Autowired private lateinit var qaRuns: QaRunRepository
    @Autowired private lateinit var qaTries: QaTryRepository
    @Autowired private lateinit var testRuns: TestRunRepository
    @Autowired private lateinit var testScenarios: TestScenarioRepository
    @Autowired private lateinit var db: DatabaseClient

    private val objectMapper = ObjectMapper()

    /**
     * 이 클래스가 만든 `qa_run` 을 스위트의 다음 클래스에 넘기지 않는다(ARTEL-1163).
     * 리액티브 트랜잭션은 롤백되지 않고 스위트가 DB 하나를 공유하므로, 남은 `qa_run` 은 뒤에 오는
     * 클래스의 `DELETE FROM game_instance` · `test_run` · `app_user` 를
     * `qa_run_game_instance_id_fkey` · `qa_run_test_run_id_fkey` · `qa_run_started_by_fkey` 로 막는다.
     */
    @AfterEach
    fun removeQaRuns() {
        db.deleteQaRuns()
    }

    @Test
    fun `붙어 있는 인스턴스에 reset_game 내장 치트를 인자 없이 호출한다`(): Unit = runBlocking {
        val userId = newUser()
        val projectId = newProject(userId)
        val instanceId = newInstance(projectId)
        val outbound = requireNotNull(
            sessionManager.register(instanceId.toString(), mockSession())
        )

        val response = reset.reset(userId, projectId, instanceId)

        assertThat(response).isNotNull()
        assertThat(response!!.gameInstanceId).isEqualTo(instanceId.toString())

        StepVerifier.create(outbound)
            .assertNext { json ->
                val sent = objectMapper.readTree(json)
                assertThat(sent["type"].asText()).isEqualTo("ACTION")
                val action = sent["actions"][0]
                assertThat(action["method"].asText()).isEqualTo("invoke_cheat")
                assertThat(action["params"][0]["name"].asText()).isEqualTo("reset_game")
                assertThat(action["params"][0].has("args")).isFalse()
            }
            .thenCancel()
            .verify(Duration.ofSeconds(5))

        detach(instanceId)
    }

    @Test
    fun `게임이 안 붙어 있으면 409 다`(): Unit = runBlocking {
        val userId = newUser()
        val projectId = newProject(userId)
        val instanceId = newInstance(projectId)   // 행은 있지만 세션이 없다

        assertThatThrownBy { runBlocking { reset.reset(userId, projectId, instanceId) } }
            .isInstanceOf(ConflictException::class.java)
            .extracting { (it as ApiException).code }
            .isEqualTo("game_instance_not_connected")
    }

    /** 참여자가 아닌 사용자에게는 존재 여부조차 알리지 않는다 — null 로 돌아오고 컨트롤러가 404 로 옮긴다. */
    @Test
    fun `참여자가 아니면 null 이다`(): Unit = runBlocking {
        val owner = newUser()
        val stranger = newUser()
        val projectId = newProject(owner)
        val instanceId = newInstance(projectId)
        sessionManager.register(instanceId.toString(), mockSession())

        assertThat(reset.reset(stranger, projectId, instanceId)).isNull()

        detach(instanceId)
    }

    /** 경로의 프로젝트가 그 인스턴스의 것과 다르면, 같은 사용자가 두 프로젝트 모두의 멤버여도 null 이다. */
    @Test
    fun `경로의 프로젝트가 다르면 초기화도 안 된다`(): Unit = runBlocking {
        val userId = newUser()
        val mine = newProject(userId)
        val other = newProject(userId)
        val instanceId = newInstance(mine)
        sessionManager.register(instanceId.toString(), mockSession())

        assertThat(reset.reset(userId, other, instanceId)).isNull()

        detach(instanceId)
    }

    /**
     * **판단**: 그 인스턴스에서 QA 런이 진행 중이면 초기화를 거절하고, 어느 런인지 id 로 말한다.
     * 에이전트가 같은 게임을 몰고 있는 동안 reset 이 씬을 다시 열면 그 런이 보던 상태와 서버가
     * 기록 중인 진행이 그 아래에서 어긋난다 — `GameInstanceResetService` KDoc 의 판단.
     */
    @Test
    fun `활성 QA 런이 있으면 그 id 를 말하며 거절한다`(): Unit = runBlocking {
        val userId = newUser()
        val projectId = newProject(userId)
        val instanceId = newInstance(projectId)
        sessionManager.register(instanceId.toString(), mockSession())
        val testRun = testRuns.save(TestRunEntity(projectId = projectId, name = "런"))
        val activeRun = qaRuns.save(
            QaRunEntity(
                testRunId = testRun.id!!,
                gameInstanceId = instanceId,
                startedBy = userId,
                status = "RUNNING",
                startedAt = Instant.now(),
            )
        )

        assertThatThrownBy { runBlocking { reset.reset(userId, projectId, instanceId) } }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("id=${activeRun.id}")
            .extracting { (it as ApiException).code }
            .isEqualTo("game_instance_qa_active")

        detach(instanceId)
    }

    /** 런 없이 도는 단독 QA 시도(하위호환 경로)도 같은 이유로 초기화를 막는다. */
    @Test
    fun `qa_run 없는 단독 QA 시도도 그 id 를 말하며 거절한다`(): Unit = runBlocking {
        val userId = newUser()
        val projectId = newProject(userId)
        val instanceId = newInstance(projectId)
        sessionManager.register(instanceId.toString(), mockSession())
        val scenario = testScenarios.save(TestScenarioEntity(projectId = projectId))
        val activeTry = qaTries.save(
            QaTryEntity(
                testScenarioId = scenario.id!!,
                gameInstanceId = instanceId,
                startedBy = userId,
                status = "RUNNING",
                startedAt = Instant.now(),
            )
        )

        assertThatThrownBy { runBlocking { reset.reset(userId, projectId, instanceId) } }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("id=${activeTry.id}")
            .extracting { (it as ApiException).code }
            .isEqualTo("game_instance_qa_active")

        detach(instanceId)
    }

    // ---------- 픽스처 ----------

    private suspend fun newUser(): Long =
        db.sql("INSERT INTO app_user (display_name, nickname, user_tag) VALUES ('console', 'console-' || gen_random_uuid(), '0000') RETURNING id")
            .map { row, _ -> row.get("id", Long::class.javaObjectType)!! }
            .one().block()!!

    private suspend fun newProject(userId: Long): Long {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(name = "reset-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
        )
        members.save(
            ProjectMemberEntity(projectId = project.id!!, appUserId = userId, role = "OWNER", createdAt = now)
        )
        return project.id
    }

    private suspend fun newInstance(projectId: Long, name: String = "instance"): Long {
        val now = Instant.now()
        return gameInstances.save(
            GameInstanceEntity(
                projectId = projectId,
                name = name,
                platform = "UNITY",
                lastConnectedAt = now,
                createdAt = now,
                updatedAt = now,
            )
        ).id!!
    }

    private fun detach(instanceId: Long) {
        sessionManager.getSession(instanceId.toString())?.let {
            sessionManager.removeSession(instanceId.toString(), it)
        }
    }

    private fun mockSession(): WebSocketSession =
        Mockito.mock(WebSocketSession::class.java).also { session ->
            Mockito.`when`(session.id).thenReturn("game-instance-reset-${System.nanoTime()}")
        }
}
