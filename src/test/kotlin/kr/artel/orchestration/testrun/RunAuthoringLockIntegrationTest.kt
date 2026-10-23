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
import kr.artel.orchestration.testrun.dto.RunChatMessage
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
    fun `잠긴 동안 사람의 TS 저장 · 승인 · 런 구성 바꾸기를 423 으로 막고, 풀리면 된다`(): Unit = runBlocking {
        val f = fixture()
        locks.hold(f.runId, f.projectId, f.userId, "${f.userId}:${f.runId}")
        try {
            // 요청한 본인의 손 편집도 막는다 — AI 결과가 끝에 통째로 덮는다.
            val error = assertThrows<LockedException> {
                runBlocking { scenarioService.testScenarioUpdate(f.userId, f.scenarioId, ScenarioDraft(title = "고침")) }
            }
            assertThat(error.code).isEqualTo(RunAuthoringLocks.RUN_LOCKED)
            assertThrows<LockedException> { runBlocking { scenarioService.testScenarioApprove(f.userId, f.scenarioId, ScenarioDraft(title = "승인")) } }
            assertThrows<LockedException> { runBlocking { runService.setScenarios(f.runId, f.userId, emptyList()) } }
        } finally {
            locks.release("${f.userId}:${f.runId}")
        }
        val saved = scenarioService.testScenarioUpdate(f.userId, f.scenarioId, ScenarioDraft(title = "풀린 뒤"))
        assertThat(saved.payload.title).isEqualTo("풀린 뒤")
    }

    @Test
    fun `다른 사람은 잠긴 런에 챗봇 요청을 보내지 못하고, 잠금은 누가 언제 무엇을 하는지 알려 준다`(): Unit = runBlocking {
        val f = fixture()
        val other = member(f.projectId)
        locks.hold(f.runId, f.projectId, f.userId, "${f.userId}:${f.runId}")
        locks.stage("${f.userId}:${f.runId}", AuthoringStage.WRITING)
        try {
            val error = assertThrows<LockedException> {
                runBlocking { chatService.relay(other, f.runId, RunChatMessage(message = "로그인 넣어 줘")) }
            }
            assertThat(error.message).contains("Artel Bot")
            val seen = chatService.authoringLock(other, f.runId)
            assertThat(seen.locked).isTrue()
            assertThat(seen.mine).isFalse()
            assertThat(seen.requestedBy).isEqualTo(f.userId.toString())
            assertThat(seen.requestedByName).isNotBlank()
            assertThat(seen.stage).isEqualTo(AuthoringStage.WRITING)
            assertThat(chatService.authoringLock(f.userId, f.runId).mine).isTrue()
        } finally {
            locks.release("${f.userId}:${f.runId}")
        }
        assertThat(chatService.authoringLock(other, f.runId).locked).isFalse()
    }

    @Test
    fun `다른 런의 잠금은 이 런을 막지 않는다`(): Unit = runBlocking {
        val f = fixture()
        val g = fixture()
        locks.hold(g.runId, g.projectId, g.userId, "${g.userId}:${g.runId}")
        try {
            val saved = scenarioService.testScenarioUpdate(f.userId, f.scenarioId, ScenarioDraft(title = "다른 런은 그대로"))
            assertThat(saved.payload.title).isEqualTo("다른 런은 그대로")
        } finally {
            locks.release("${g.userId}:${g.runId}")
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
