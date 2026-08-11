package kr.artel.orchestration.qa

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.knowledge.entity.KnowledgeMode
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.dto.CreateQaTryRequest
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaKnowledgeSettings
import kr.artel.orchestration.qa.service.QaTryPersistenceService
import kr.artel.orchestration.qa.service.toKnowledgeSettings
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID

/**
 * Agent 가 확정한 실행 설정이 qa_try 에 남는지 (ARTEL-239).
 *
 * 검증의 핵심은 두 가지다. 하나는 설정이 세션 부착과 **같은 문장**으로 들어간다는 것 —
 * 나눠 쓰면 런이 시작된 것처럼 보이면서 소속은 비어 있는 창이 생기고, 그 창을 나중에
 * 메워 줄 것은 아무것도 없다. 다른 하나는 설정을 모르는 Agent 라도 런은 RUNNING 에
 * 도달해야 한다는 것 — 집계에서 한 줄 빠지는 것과 실행이 멈추는 것은 무게가 다르다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class QaRunConfigPersistenceIntegrationTest {

    @Autowired private lateinit var persistence: QaTryPersistenceService
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var qaRunRepository: QaRunRepository
    @Autowired private lateinit var testRunRepository: TestRunRepository
    @Autowired private lateinit var qaLogRepository: QaLogRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper

    private val resolvedConfig =
        """
        {
          "model": "anthropic/claude-sonnet-5",
          "provider": "anthropic",
          "temperature": 0.2,
          "reasoning": {"effort": "high", "max_tokens": null},
          "reasoning_supported": true,
          "language": "ko",
          "prompt_version": "v3",
          "prompt_hashes": {"system": "9a5d7af1"},
          "agent_arch": "v2-tool-loop",
          "agent_fingerprint": "a3f1c9d2e8b0",
          "arch": {"vision": true, "tool_calls_per_step": 15},
          "tools": ["observe_scene", "report_step"],
          "git_sha": "ef4f539",
          "image_tag": "artel-agent-server:2026.08.05-ef4f539"
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
    fun `resolved settings land on the try with the session`(): Unit = runBlocking {
        val starting = seedStartingQaTry()

        val (running, _) = persistence.attachAndMarkRunning(
            starting,
            "session-1",
            objectMapper.readTree(resolvedConfig)
        )

        assertThat(running.status).isEqualTo("RUNNING")
        assertThat(running.agentSessionId).isEqualTo("session-1")
        // 승격 컬럼: 집계가 인덱스로 도는 자리.
        assertThat(running.model).isEqualTo("anthropic/claude-sonnet-5")
        assertThat(running.reasoningEffort).isEqualTo("high")
        assertThat(running.promptVersion).isEqualTo("v3")
        assertThat(running.agentArch).isEqualTo("v2-tool-loop")
        assertThat(running.agentFingerprint).isEqualTo("a3f1c9d2e8b0")
    }

    @Test
    fun `the whole snapshot survives, not just the promoted axes`(): Unit = runBlocking {
        val starting = seedStartingQaTry()

        val (running, _) = persistence.attachAndMarkRunning(
            starting,
            "session-1",
            objectMapper.readTree(resolvedConfig)
        )

        // 컬럼은 사본이고 진실은 이쪽이다. 나중에 축을 하나 더 승격하려면 여기서 꺼낸다.
        val stored = objectMapper.readTree(running.runConfig.asString())
        assertThat(stored.path("prompt_hashes").path("system").asText()).isEqualTo("9a5d7af1")
        assertThat(stored.path("arch").path("tool_calls_per_step").asInt()).isEqualTo(15)
        assertThat(stored.path("git_sha").asText()).isEqualTo("ef4f539")
        assertThat(stored.path("tools").size()).isEqualTo(2)
    }

    @Test
    fun `an agent that reports nothing still produces a running try`(): Unit = runBlocking {
        val starting = seedStartingQaTry()

        val (running, _) = persistence.attachAndMarkRunning(starting, "session-1", null)

        assertThat(running.status).isEqualTo("RUNNING")
        assertThat(running.agentSessionId).isEqualTo("session-1")
        assertThat(running.model).isNull()
        assertThat(running.agentFingerprint).isNull()
        // Agent 가 아무것도 말하지 않아도 knowledge_mode 는 남는다 — Orchestration 이 집행하는
        // 값이라 Agent 의 침묵과 무관하고, 빠지면 그 런이 어느 arm 이었는지 알 수 없다(ARTEL-256).
        val stored = objectMapper.readTree(running.runConfig.asString())
        assertThat(stored.fieldNames().asSequence().toList()).containsExactly("knowledge_mode")
        assertThat(stored.path("knowledge_mode").asText()).isEqualTo("learning")
    }

    // ---------------------------------------------------- 지식 스코프·모드 (ARTEL-256)

    /**
     * 스코프와 모드는 **런이 만들어지는 순간** 기록된다. 세션 부착을 기다리면 그 사이 try 는 이미
     * STARTING 이라 라우터가 프레임을 받는데, 그 창에서 모드가 비어 있으면 `frozen` 으로 돌린 arm 이
     * 지식창고에 쓸 수 있다.
     */
    @Test
    fun `지식 스코프와 모드는 세션 부착 전에 이미 기록된다`(): Unit = runBlocking {
        val ids = seedIds()
        val (starting, _) = persistence.createStarting(
            ids.scenarioId,
            ids.instanceId,
            ids.ownerId,
            QaKnowledgeSettings(scopeId = 8_001L, mode = KnowledgeMode.FROZEN)
        )

        assertThat(starting.status).isEqualTo("STARTING")
        assertThat(starting.knowledgeScopeId).isEqualTo(8_001L)
        assertThat(objectMapper.readTree(starting.runConfig.asString()).path("knowledge_mode").asText())
            .isEqualTo("frozen")
    }

    /**
     * Agent 스냅샷이 run_config 를 통째로 덮어써도 모드는 살아남아야 한다.
     *
     * 모드를 **호출자가 다시 주지 않는다** — `attachAndMarkRunning` 이 STARTING 행에서 옮겨 온다.
     * 파라미터로 받으면 호출자가 createStarting 때와 다른 값을 넘길 수 있고, 그러면 게이트가 이미
     * 집행된 뒤에 기록만 바뀐다.
     */
    @Test
    fun `Agent 스냅샷을 덮어써도 knowledge_mode 는 남는다`(): Unit = runBlocking {
        val ids = seedIds()
        val knowledge = QaKnowledgeSettings(scopeId = 8_002L, mode = KnowledgeMode.OFF)
        val (starting, _) =
            persistence.createStarting(ids.scenarioId, ids.instanceId, ids.ownerId, knowledge)

        val (running, _) = persistence.attachAndMarkRunning(
            starting,
            "session-1",
            objectMapper.readTree(resolvedConfig)
        )

        val stored = objectMapper.readTree(running.runConfig.asString())
        assertThat(stored.path("knowledge_mode").asText()).isEqualTo("off")
        // Agent 스냅샷도 그대로 있다 — 얹는 것이지 갈아치우는 것이 아니다.
        assertThat(stored.path("agent_fingerprint").asText()).isEqualTo("a3f1c9d2e8b0")
        assertThat(running.knowledgeScopeId).isEqualTo(8_002L)
    }

    /** 요청이 지식 설정을 주지 않으면 운영 런이고 지금까지의 동작(learning)이다. */
    @Test
    fun `지식 설정이 없으면 운영 런에 learning 이다`(): Unit = runBlocking {
        val ids = seedIds()
        val (starting, _) =
            persistence.createStarting(ids.scenarioId, ids.instanceId, ids.ownerId, QaKnowledgeSettings())

        assertThat(starting.knowledgeScopeId).isNull()
        assertThat(objectMapper.readTree(starting.runConfig.asString()).path("knowledge_mode").asText())
            .isEqualTo("learning")
    }

    /**
     * 잘못된 모드 토큰은 요청 단계에서 거절한다. 조용히 기본값으로 떨어지면 대조군으로 돌린 arm 이
     * 사실은 학습을 하고, 그 오염은 결과가 그럴듯해서 실험이 끝날 때까지 드러나지 않는다.
     */
    @Test
    fun `잘못된 지식 설정은 요청 단계에서 거절된다`() {
        val base = CreateQaTryRequest(testScenarioId = "1", gameInstanceId = "2")

        assertThat(base.toKnowledgeSettings()).isEqualTo(QaKnowledgeSettings())
        assertThat(base.copy(knowledgeScopeId = "42", knowledgeMode = "FROZEN").toKnowledgeSettings())
            .isEqualTo(QaKnowledgeSettings(scopeId = 42L, mode = KnowledgeMode.FROZEN))

        assertThatThrownBy { base.copy(knowledgeMode = "learnign").toKnowledgeSettings() }
            .isInstanceOf(BadRequestException::class.java)
        assertThatThrownBy { base.copy(knowledgeScopeId = "not-a-number").toKnowledgeSettings() }
            .isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `a reasoning-less model records the model without an effort`(): Unit = runBlocking {
        val starting = seedStartingQaTry()

        val (running, _) = persistence.attachAndMarkRunning(
            starting,
            "session-1",
            objectMapper.readTree(
                """{"model":"openai/gpt-4o","reasoning":null,"reasoning_supported":false}"""
            )
        )

        assertThat(running.model).isEqualTo("openai/gpt-4o")
        // NULL 이 "미지정"인지 "모델이 지원 안 함"인지는 컬럼이 답하지 않는다. 스냅샷이 답한다.
        assertThat(running.reasoningEffort).isNull()
        assertThat(
            objectMapper.readTree(running.runConfig.asString()).path("reasoning_supported").asBoolean()
        ).isFalse()
    }

    @Test
    fun `attaching twice is refused, so settings cannot be overwritten mid-run`(): Unit = runBlocking {
        val starting = seedStartingQaTry()
        persistence.attachAndMarkRunning(starting, "session-1", objectMapper.readTree(resolvedConfig))

        runCatching {
            persistence.attachAndMarkRunning(starting, "session-2", objectMapper.createObjectNode())
        }

        val stored = qaTryRepository.findAll().toList().single()
        assertThat(stored.agentSessionId).isEqualTo("session-1")
        assertThat(stored.agentFingerprint).isEqualTo("a3f1c9d2e8b0")
    }

    @Test
    fun `createRunStarting은 qa_run 하나와 시나리오당 PENDING qa_try N개를 적재한다`(): Unit = runBlocking {
        val owner = signIn()
        val ownerId = owner.userId.toLong()
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "run-p", genre = "ACTION", createdAt = now, updatedAt = now)
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
        val run = testRunRepository.save(TestRunEntity(projectId = project.id!!, name = "런"))!!
        val s1 = testScenarioRepository.save(TestScenarioEntity(projectId = project.id!!))!!
        val s2 = testScenarioRepository.save(TestScenarioEntity(projectId = project.id!!))!!

        val started = persistence.createRunStarting(run.id!!, instance.id!!, ownerId, listOf(s1.id!!, s2.id!!))

        // 부모 런은 STARTING, 자식 qa_try는 시나리오당 PENDING — 2개 PENDING이 활성 유니크
        // (uk_qa_try_active_instance)를 위반하지 않는다(적재 성공 자체가 증거).
        assertThat(started.qaRun.status).isEqualTo("STARTING")
        assertThat(started.qaRun.testRunId).isEqualTo(run.id)
        assertThat(started.tries.map { it.status }).containsExactly("PENDING", "PENDING")
        assertThat(started.tries.map { it.testScenarioId }).containsExactly(s1.id, s2.id)
        assertThat(started.tries.all { it.qaRunId == started.qaRun.id }).isTrue()
        assertThat(qaTryRepository.findById(started.tries[0].id!!)!!.status).isEqualTo("PENDING")
    }

    // ----------------------------------------------------------------- seeding

    private data class SeedIds(val ownerId: Long, val scenarioId: Long, val instanceId: Long)

    /** 런 하나를 만들 수 있는 최소 배경(사용자·프로젝트·시나리오·인스턴스). */
    private suspend fun seedIds(): SeedIds {
        val ownerId = signIn().userId.toLong()
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "run-config-project", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = project.id!!,
                appUserId = ownerId,
                role = ProjectRole.OWNER.name,
                createdAt = now
            )
        )
        val scenario = testScenarioRepository.save(
            TestScenarioEntity(projectId = project.id!!)
        )!!
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = project.id!!,
                name = "instance",
                platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now
            )
        )!!
        return SeedIds(ownerId = ownerId, scenarioId = scenario.id!!, instanceId = instance.id!!)
    }

    /**
     * 실제 생성 경로로 STARTING 런을 만든다.
     *
     * 엔티티를 직접 save 하지 않는 것은 `createStarting` 이 run_config 에 knowledge_mode 를 미리
     * 심기 때문이다(ARTEL-256). 직접 save 하면 실제 런에는 없는 "모드가 비어 있는 STARTING 행"을
     * 만들어 놓고 그 위에서 부착을 검증하게 된다.
     */
    private suspend fun seedStartingQaTry(): QaTryEntity {
        val ids = seedIds()
        val (starting, _) =
            persistence.createStarting(ids.scenarioId, ids.instanceId, ids.ownerId, QaKnowledgeSettings())
        return starting
    }

    private suspend fun signIn(): AuthenticatedUser =
        oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = "239",
                login = "runconfig",
                displayName = "runconfig",
                avatarUrl = null,
                email = "runconfig@example.com"
            )
        )!!
}
