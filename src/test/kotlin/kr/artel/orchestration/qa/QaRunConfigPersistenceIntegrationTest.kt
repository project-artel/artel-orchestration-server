package kr.artel.orchestration.qa

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaTryPersistenceService
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
        gameInstanceRepository.deleteAll()
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
        assertThat(objectMapper.readTree(running.runConfig.asString()).isEmpty).isTrue()
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

    // ----------------------------------------------------------------- seeding

    private suspend fun seedStartingQaTry(): QaTryEntity {
        val owner = signIn()
        val ownerId = owner.userId.toLong()
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
            TestScenarioEntity(projectId = project.id!!, payload = Json.of("{}"))
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
        return qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenario.id!!,
                gameInstanceId = instance.id!!,
                startedBy = ownerId,
                status = "STARTING",
                startedAt = now
            )
        )!!
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
