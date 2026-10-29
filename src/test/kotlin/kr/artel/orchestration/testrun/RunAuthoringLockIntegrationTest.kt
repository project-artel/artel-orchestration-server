package kr.artel.orchestration.testrun

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.common.error.LockedException
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.support.testAppUser
import kr.artel.orchestration.testrun.dto.TestRunCreateRequest
import kr.artel.orchestration.testrun.service.RunAuthoringLocks
import kr.artel.orchestration.testrun.service.TestRunChatService
import kr.artel.orchestration.testrun.service.TestRunService
import kr.artel.orchestration.testscenario.dto.AuthoringStage
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import kr.artel.orchestration.testscenario.service.TestScenarioService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/** Artel Bot 이 시나리오를 고치는 동안의 런 잠금(ARTEL-1264). */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RunAuthoringLockIntegrationTest {

    @Autowired private lateinit var locks: RunAuthoringLocks
    @Autowired private lateinit var runService: TestRunService
    @Autowired private lateinit var chatService: TestRunChatService
    @Autowired private lateinit var scenarioService: TestScenarioService
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var memberRepository: ProjectMemberRepository
    @Autowired private lateinit var scenarioRepository: TestScenarioRepository
    @Autowired private lateinit var objectMapper: ObjectMapper

    @Test
    fun `보낸 순간 런의 TS 를 다 잠갔다가, AI 가 고친 시나리오만 남기고 런 구성은 막지 않는다`(): Unit = runBlocking {
        val f = fixture()
        val other = scenarioRepository.save(
            TestScenarioEntity(projectId = f.projectId, title = "다른 시나리오", steps = Json.of(objectMapper.writeValueAsString(emptyList<Any>()))),
        ).id!!
        runService.setScenarios(f.runId, f.userId, listOf(f.scenarioId, other))
        val key = "${f.userId}:${f.runId}"
        locks.hold(f.runId, f.projectId, f.userId, key)
        try {
            // 보낸 순간에는 무엇을 고칠지 몰라 런의 TS 전부가 잠긴다.
            assertThrows<LockedException> { runBlocking { scenarioService.testScenarioUpdate(f.userId, other, ScenarioDraft(title = "고르는 중")) } }
            // AI 가 하나를 고쳐 내보내면 그것만 남는다.
            locks.lockScenarios(key, listOf(f.scenarioId))
            // 요청한 본인의 손 편집도 막는다 — AI 결과가 끝에 통째로 덮는다.
            val error = assertThrows<LockedException> {
                runBlocking { scenarioService.testScenarioUpdate(f.userId, f.scenarioId, ScenarioDraft(title = "고침")) }
            }
            assertThat(error.code).isEqualTo(RunAuthoringLocks.RUN_LOCKED)
            assertThrows<LockedException> { runBlocking { scenarioService.testScenarioApprove(f.userId, f.scenarioId, ScenarioDraft(title = "승인")) } }
            // 같은 런의 다른 시나리오와 런 구성은 막지 않는다.
            assertThat(scenarioService.testScenarioUpdate(f.userId, other, ScenarioDraft(title = "옆 시나리오")).payload.title).isEqualTo("옆 시나리오")
            runService.setScenarios(f.runId, f.userId, listOf(other, f.scenarioId))
        } finally {
            locks.release(key)
        }
        val saved = scenarioService.testScenarioUpdate(f.userId, f.scenarioId, ScenarioDraft(title = "풀린 뒤"))
        assertThat(saved.payload.title).isEqualTo("풀린 뒤")
    }

    @Test
    fun `잠금은 어느 시나리오를 누가 언제부터 무엇을 하며 고치는지 알려 준다`(): Unit = runBlocking {
        val f = fixture()
        val other = member(f.projectId)
        val key = "${f.userId}:${f.runId}"
        locks.hold(f.runId, f.projectId, f.userId, key)
        assertThat(chatService.authoringLock(other, f.runId).scenarios.single().choosing).isTrue()
        // 새 TS 를 쓰는 흐름이면 있는 TS 는 고치지 않으므로 고르는 중 잠금이 풀린다.
        locks.stage(key, AuthoringStage.WRITING)
        assertThat(chatService.authoringLock(other, f.runId).scenarios).isEmpty()
        locks.lockScenarios(key, listOf(f.scenarioId))
        try {
            val seen = chatService.authoringLock(other, f.runId).scenarios.single()
            assertThat(seen.scenarioId).isEqualTo(f.scenarioId.toString())
            assertThat(seen.mine).isFalse()
            assertThat(seen.requestedBy).isEqualTo(f.userId.toString())
            assertThat(seen.requestedByName).isNotBlank()
            assertThat(seen.stage).isEqualTo(AuthoringStage.WRITING)
            assertThat(seen.choosing).isFalse()
            assertThat(chatService.authoringLock(f.userId, f.runId).scenarios.single().mine).isTrue()
            // 다른 사람의 AI 는 이 시나리오를 쓰지 못하고, 같은 세션의 AI 는 쓴다.
            assertThat(runBlocking { locks.heldByOther(f.scenarioId, "$other:${f.runId}") }).isTrue()
            assertThat(runBlocking { locks.heldByOther(f.scenarioId, key) }).isFalse()
        } finally {
            locks.release(key)
        }
        assertThat(chatService.authoringLock(other, f.runId).scenarios).isEmpty()
    }

    @Test
    fun `다른 런의 잠금은 이 런을 막지 않는다`(): Unit = runBlocking {
        val f = fixture()
        val g = fixture()
        val key = "${g.userId}:${g.runId}"
        locks.hold(g.runId, g.projectId, g.userId, key)
        try {
            val saved = scenarioService.testScenarioUpdate(f.userId, f.scenarioId, ScenarioDraft(title = "다른 런은 그대로"))
            assertThat(saved.payload.title).isEqualTo("다른 런은 그대로")
        } finally {
            locks.release(key)
        }
    }

    private data class Fixture(val projectId: Long, val userId: Long, val runId: Long, val scenarioId: Long)

    private suspend fun fixture(): Fixture {
        val now = Instant.now()
        val userId = appUserRepository.save(testAppUser("run-lock", now)).id!!
        val projectId = projectRepository.save(ProjectEntity(name = "run-lock", genre = "ACTION", createdAt = now, updatedAt = now)).id!!
        memberRepository.save(ProjectMemberEntity(projectId = projectId, appUserId = userId, role = "MEMBER", createdAt = now))
        val scenarioId = scenarioRepository.save(
            TestScenarioEntity(projectId = projectId, title = "시나리오", steps = Json.of(objectMapper.writeValueAsString(emptyList<Any>()))),
        ).id!!
        val run = runService.create(projectId, userId, TestRunCreateRequest(name = "런"))!!
        runService.setScenarios(run.id.toLong(), userId, listOf(scenarioId))
        return Fixture(projectId, userId, run.id.toLong(), scenarioId)
    }

    private suspend fun member(projectId: Long): Long {
        val now = Instant.now()
        val id = appUserRepository.save(testAppUser("run-lock-member", now)).id!!
        memberRepository.save(ProjectMemberEntity(projectId = projectId, appUserId = id, role = "MEMBER", createdAt = now))
        return id
    }
}
