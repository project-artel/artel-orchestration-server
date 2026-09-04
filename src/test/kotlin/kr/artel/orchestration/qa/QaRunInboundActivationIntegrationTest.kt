package kr.artel.orchestration.qa

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.contentmap.entity.ContentMapMode
import kr.artel.orchestration.knowledge.entity.KnowledgeMode
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaAgentEnvelope
import kr.artel.orchestration.qa.service.QaAgentInboundRouter
import kr.artel.orchestration.qa.service.QaTryPersistenceService
import kr.artel.orchestration.qa.service.QaRunGates
import kr.artel.orchestration.qa.service.QaTryService
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
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID

/**
 * 런의 다음 시나리오가 보낸 첫 프레임에서 인바운드 라우터가 PENDING qa_try를 활성하는지
 * (ARTEL-259 시나리오 전환). 세션은 런 단위로 하나만 열리고, 두 번째 이후 시나리오의 qa_try는
 * 그 차례가 오기 전까지 PENDING으로 대기한다 — 그 프레임이 라우터의 activeTry에서 드롭되지 않고
 * 런의 세션 공통 run_config로 RUNNING이 되어야 한다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class QaRunInboundActivationIntegrationTest {

    @Autowired private lateinit var router: QaAgentInboundRouter
    @Autowired private lateinit var service: QaTryService
    @Autowired private lateinit var persistence: QaTryPersistenceService
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var qaRunRepository: QaRunRepository
    @Autowired private lateinit var qaLogRepository: QaLogRepository
    @Autowired private lateinit var testRunRepository: TestRunRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper

    private val RUNNING_LOG = "QA execution is running."

    private val resolvedConfig =
        """
        {
          "model": "anthropic/claude-sonnet-5",
          "reasoning": {"effort": "high", "max_tokens": null},
          "prompt_version": "v3",
          "agent_arch": "v2-tool-loop",
          "agent_fingerprint": "a3f1c9d2e8b0"
        }
        """.trimIndent()

    @AfterEach
    fun clean(): Unit = runBlocking { wipe() }

    @BeforeEach
    fun cleanAndSeed(): Unit = runBlocking { wipe() }

    private suspend fun wipe() {
        qaLogRepository.deleteAll()
        qaTryRepository.deleteAll()
        qaRunRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testRunRepository.deleteAll()
        testScenarioRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `두 번째 시나리오의 프레임이 PENDING qa_try를 런 설정으로 활성한다`(): Unit = runBlocking {
        val run = runningRun()
        // 실행은 순차다 — 시나리오 1이 끝나야(활성 유니크 때문에도) 시나리오 2가 활성된다.
        completeFirstScenario(run.firstTryId)
        val secondTryId = run.pendingTryId

        router.handle(logFrame(secondTryId, "scenario 2 turn"))

        // PENDING → RUNNING, 그리고 런의 세션 공통 설정이 그 try에 새겨진다(attribution).
        val activated = qaTryRepository.findById(secondTryId)!!
        assertThat(activated.status).isEqualTo("RUNNING")
        assertThat(activated.agentSessionId).isEqualTo("session-run-1")
        assertThat(activated.model).isEqualTo("anthropic/claude-sonnet-5")
        assertThat(activated.reasoningEffort).isEqualTo("high")
        assertThat(activated.promptVersion).isEqualTo("v3")
        assertThat(activated.agentArch).isEqualTo("v2-tool-loop")
        assertThat(activated.agentFingerprint).isEqualTo("a3f1c9d2e8b0")

        // 활성과 함께 RUNNING 상태 로그가 남고, 프레임 자체도 정상 라우팅되어 적재된다.
        val logs = qaLogRepository.findPage(secondTryId, null, 50).toList()
        assertThat(logs.any { it.type == "STATUS" && it.message == RUNNING_LOG }).isTrue()
        assertThat(logs.any { it.type == "LOG" && it.message == "scenario 2 turn" }).isTrue()
    }

    /**
     * **두 번째 시나리오가 활성될 때도 run 의 두 gate 가 살아남는다.**
     *
     * 활성은 런의 세션 공통 `run_config` 를 그 try 에 새기는데, 그 스냅샷은 Agent 가 확정한 설정이라
     * gate 가 없다. 다시 얹지 않으면 run 경로로 시작한 arm 의 gate 가 **두 번째 시나리오부터**
     * 사라진다 — 런 하나 안에서 arm 이 바뀌는 셈이고, 시나리오 1 만 보면 녹색이라 조용하다.
     */
    @Test
    fun `두 번째 시나리오가 활성돼도 run 의 gate 가 살아남는다`(): Unit = runBlocking {
        val run = runningRun(QaRunGates(knowledgeMode = KnowledgeMode.FROZEN, contentMapMode = ContentMapMode.OFF))
        completeFirstScenario(run.firstTryId)

        router.handle(logFrame(run.pendingTryId, "scenario 2 turn"))

        val activated = qaTryRepository.findById(run.pendingTryId)!!
        val stored = objectMapper.readTree(activated.runConfig.asString())
        assertThat(activated.status).isEqualTo("RUNNING")
        assertThat(stored.path("knowledge_mode").asText()).isEqualTo("frozen")
        assertThat(stored.path("content_map_mode").asText()).isEqualTo("off")
        // 런의 세션 공통 설정도 그대로 새겨진다 — gate 는 얹는 것이지 갈아치우는 것이 아니다.
        assertThat(stored.path("agent_fingerprint").asText()).isEqualTo("a3f1c9d2e8b0")
        assertThat(activated.model).isEqualTo("anthropic/claude-sonnet-5")
    }

    @Test
    fun `런이 이미 끝났으면 PENDING 프레임은 활성되지 않고 버려진다`(): Unit = runBlocking {
        val run = runningRun()
        val secondTryId = run.pendingTryId
        val now = Instant.now()
        // 런을 COMPLETED로 종단시킨다 — 이후 도착하는 지각 프레임은 새 시나리오를 열지 않는다.
        assertThat(qaRunRepository.transition(run.runId, "RUNNING", "COMPLETED", now, now)).isEqualTo(1)

        router.handle(logFrame(secondTryId, "late frame"))

        val stillPending = qaTryRepository.findById(secondTryId)!!
        assertThat(stillPending.status).isEqualTo("PENDING")
        assertThat(stillPending.agentSessionId).isNull()
        // 적재 시점의 "queued" 로그만 남고, 프레임(LOG)도 활성(RUNNING) 로그도 생기지 않는다.
        val logs = qaLogRepository.findPage(secondTryId, null, 50).toList()
        assertThat(logs.none { it.type == "LOG" }).isTrue()
        assertThat(logs.none { it.message == RUNNING_LOG }).isTrue()
    }

    @Test
    fun `이미 RUNNING인 첫 시나리오의 프레임은 재활성 없이 그대로 라우팅된다`(): Unit = runBlocking {
        val run = runningRun()

        router.handle(logFrame(run.firstTryId, "scenario 1 turn"))

        val logs = qaLogRepository.findPage(run.firstTryId, null, 50).toList()
        assertThat(logs.any { it.type == "LOG" && it.message == "scenario 1 turn" }).isTrue()
        // 이미 활성이라 activatePending을 타지 않는다 — RUNNING 로그는 최초 활성 때의 한 줄뿐.
        assertThat(logs.count { it.message == RUNNING_LOG }).isEqualTo(1)
    }

    @Test
    fun `getRun은 런과 시나리오별 try를 순서대로 돌려주고 비멤버에겐 안 보인다`(): Unit = runBlocking {
        val run = runningRun()

        val response = service.getRun(run.runId, run.ownerId)!!
        assertThat(response.id).isEqualTo(run.runId.toString())
        assertThat(response.status).isEqualTo("RUNNING")
        // 적재 순서(=시나리오 순서)대로, 첫 시나리오는 활성(RUNNING)·나머진 대기(PENDING).
        assertThat(response.tries.map { it.id })
            .containsExactly(run.firstTryId.toString(), run.pendingTryId.toString())
        assertThat(response.tries.map { it.status }).containsExactly("RUNNING", "PENDING")

        // 프로젝트 비멤버에게는 런이 존재하지 않는 것과 같다(null → 컨트롤러에서 404).
        val stranger = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github", providerUserId = "9999", login = "stranger",
                displayName = "stranger", avatarUrl = null, email = "stranger@example.com"
            )
        )!!
        assertThat(service.getRun(run.runId, stranger.userId.toLong())).isNull()
    }

    @Test
    fun `모든 시나리오 try가 끝나면 qa_run이 COMPLETED로 닫혀 인스턴스가 풀린다`(): Unit = runBlocking {
        val run = runningRun()

        // 시나리오 1 종단 — 아직 시나리오 2가 PENDING이라 런은 RUNNING을 유지한다.
        router.handle(terminalStatusFrame(run.firstTryId))
        assertThat(qaRunRepository.findById(run.runId)!!.status).isEqualTo("RUNNING")

        // 시나리오 2 활성(첫 프레임) 후 종단 — 이제 모든 try가 종단이라 런이 닫힌다.
        router.handle(logFrame(run.pendingTryId, "s2 turn"))
        router.handle(terminalStatusFrame(run.pendingTryId))

        val closed = qaRunRepository.findById(run.runId)!!
        assertThat(closed.status).isEqualTo("COMPLETED")
        assertThat(closed.completedAt).isNotNull()
        // 재실행 가드는 STARTING/RUNNING만 센다 — 이제 이 인스턴스로 다시 런을 시작할 수 있다.
        assertThat(qaRunRepository.findActiveByGameInstanceId(run.instanceId)).isNull()
    }

    @Test
    fun `cancelRun은 미종단 try를 정리하고 qa_run을 CANCELLED로 닫아 인스턴스를 푼다`(): Unit = runBlocking {
        val run = runningRun()
        // 활성 try(Agent 세션 필요)의 취소 네트워크 경로를 피하려 시나리오 1은 미리 종료해 둔다.
        completeFirstScenario(run.firstTryId)

        service.cancelRun(run.runId, run.ownerId)

        val cancelled = qaRunRepository.findById(run.runId)!!
        assertThat(cancelled.status).isEqualTo("CANCELLED")
        // 아직 안 돈 PENDING 시나리오는 미종단 정리로 FAILED가 된다.
        assertThat(qaTryRepository.findById(run.pendingTryId)!!.status).isEqualTo("FAILED")
        assertThat(qaRunRepository.findActiveByGameInstanceId(run.instanceId)).isNull()
    }

    // ----------------------------------------------------------------- helpers

    /** 시나리오 1을 정상 종료시킨다 — 활성 유니크(uk_qa_try_active_instance)를 비워 2가 활성될 자리를 낸다. */
    private suspend fun completeFirstScenario(firstTryId: Long) {
        val now = Instant.now()
        assertThat(qaTryRepository.transition(firstTryId, "RUNNING", "COMPLETED", now, now)).isEqualTo(1)
    }

    @Test
    fun `TOOL 과 TOOL_RESULT 프레임이 거절 없이 적재되고 payload 가 그대로 통과한다`(): Unit = runBlocking {
        val run = runningRun()
        val call = toolFrame(run.firstTryId)

        router.handle(call)
        router.handle(toolResultFrame(run.firstTryId, call.messageId))

        val logs = qaLogRepository.findPage(run.firstTryId, null, 50).toList()

        val tool = logs.single { it.type == "TOOL" }
        assertThat(tool.direction).isEqualTo("AGENT_TO_ORCHE")
        // 표시용 message 는 tool 이름이다. 라우터의 non-blank 가드를 지나는 것이 이 필드다.
        assertThat(tool.message).isEqualTo("search_knowledge")
        // payload 는 가공 없이 jsonb 로 통과한다 — 인자를 읽는 것은 화면의 몫이다.
        assertThat(objectMapper.readTree(tool.payload.asString()).path("args").path("query").asText())
            .isEqualTo("보스전 진입 조건")

        val result = logs.single { it.type == "TOOL_RESULT" }
        // 답은 자기 호출의 messageId 를 correlation 으로 들고 온다. 화면이 이것으로 짝짓는다.
        assertThat(result.correlationId).isEqualTo(call.messageId)

        // 모르는 타입이면 라우터가 ERROR 를 남긴다. 그 자리가 비어야 통과한 것이다.
        assertThat(logs.none { it.type == "ERROR" }).isTrue()
    }

    private fun toolFrame(qaTryId: Long) =
        QaAgentEnvelope(
            messageId = UUID.randomUUID().toString(),
            type = "TOOL",
            qaTryId = qaTryId.toString(),
            timestamp = Instant.now(),
            payload = objectMapper.readTree(
                """
                {
                  "message": "search_knowledge",
                  "tool": "search_knowledge",
                  "tool_call_id": "call_abc123",
                  "args": {"step": 2, "query": "보스전 진입 조건"},
                  "step": 2
                }
                """.trimIndent()
            )
        )

    private fun toolResultFrame(qaTryId: Long, correlationId: String) =
        QaAgentEnvelope(
            messageId = UUID.randomUUID().toString(),
            type = "TOOL_RESULT",
            qaTryId = qaTryId.toString(),
            correlationId = correlationId,
            timestamp = Instant.now(),
            payload = objectMapper.readTree(
                """
                {
                  "message": "search_knowledge",
                  "tool": "search_knowledge",
                  "tool_call_id": "call_abc123",
                  "content": "2 entries found.",
                  "step": 2
                }
                """.trimIndent()
            )
        )

    private fun logFrame(qaTryId: Long, message: String) =
        QaAgentEnvelope(
            messageId = UUID.randomUUID().toString(),
            type = "LOG",
            qaTryId = qaTryId.toString(),
            timestamp = Instant.now(),
            payload = objectMapper.readTree("""{"message":"$message"}""")
        )

    private data class RunningRun(
        val runId: Long,
        val firstTryId: Long,
        val pendingTryId: Long,
        val ownerId: Long,
        val instanceId: Long
    )

    /** qa_run RUNNING + 첫 시나리오 RUNNING(활성) + 두 번째 시나리오 PENDING(대기)인 런을 만든다. */
    private suspend fun runningRun(gates: QaRunGates = QaRunGates()): RunningRun {
        val owner = signIn()
        val ownerId = owner.userId.toLong()
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "act-p", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = project.id!!, appUserId = ownerId, role = ProjectRole.OWNER.name, createdAt = now
            )
        )
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = project.id!!, name = "inst", platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(), createdAt = now, updatedAt = now
            )
        )!!
        val testRun = testRunRepository.save(TestRunEntity(projectId = project.id!!, name = "런"))!!
        val s1 = testScenarioRepository.save(TestScenarioEntity(projectId = project.id!!))!!
        val s2 = testScenarioRepository.save(TestScenarioEntity(projectId = project.id!!))!!

        val started =
            persistence.createRunStarting(testRun.id!!, instance.id!!, ownerId, listOf(s1.id!!, s2.id!!), gates)
        val firstTryId = started.tries.first().id!!
        val pendingTryId = started.tries[1].id!!
        persistence.attachRunAndMarkRunning(
            started.qaRun, firstTryId, "session-run-1", objectMapper.readTree(resolvedConfig)
        )
        return RunningRun(started.qaRun.id!!, firstTryId, pendingTryId, ownerId, instance.id!!)
    }

    private fun terminalStatusFrame(qaTryId: Long) =
        QaAgentEnvelope(
            messageId = UUID.randomUUID().toString(),
            type = "STATUS",
            qaTryId = qaTryId.toString(),
            timestamp = Instant.now(),
            payload = objectMapper.readTree(
                """{"status":"COMPLETED","result":"PASSED","message":"scenario done"}"""
            )
        )

    private suspend fun signIn(): AuthenticatedUser =
        oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = "259",
                login = "runact",
                displayName = "runact",
                avatarUrl = null,
                email = "runact@example.com"
            )
        )!!
}
