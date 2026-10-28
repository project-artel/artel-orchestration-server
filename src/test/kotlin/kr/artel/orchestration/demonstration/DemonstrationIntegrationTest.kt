package kr.artel.orchestration.demonstration

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.ForbiddenException
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.FakeDocumentStorage
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.qa.entity.QaRunEntity
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.entity.TestCaseVersionEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.repository.TestCaseVersionRepository
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
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.Disposable
import java.time.Instant
import java.util.UUID
import java.util.Base64

@ActiveProfiles("test")
@SpringBootTest(properties = ["artel.demonstration.enabled=true", "artel.demonstration.qa-enabled=true"])
class DemonstrationIntegrationTest {
    @TestConfiguration
    class StorageConfig {
        @Bean @Primary fun demonstrationStorage(): DocumentStorage = FakeDocumentStorage()
    }

    @Autowired private lateinit var service: DemonstrationService
    @Autowired private lateinit var controlSocket: DemonstrationWebSocketHandler
    @Autowired private lateinit var guard: DemonstrationGuard
    @Autowired private lateinit var repo: DemonstrationRepository
    @Autowired private lateinit var procedures: VerificationProcedureService
    @Autowired private lateinit var evidence: DemonstrationEvidenceService
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var runtime: DemonstrationRuntime
    @Autowired private lateinit var sessions: SessionManager
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var members: ProjectMemberRepository
    @Autowired private lateinit var builds: GameBuildRepository
    @Autowired private lateinit var instances: GameInstanceRepository
    @Autowired private lateinit var cases: TestCaseRepository
    @Autowired private lateinit var versions: TestCaseVersionRepository
    @Autowired private lateinit var scenarios: TestScenarioRepository
    @Autowired private lateinit var testRuns: TestRunRepository
    @Autowired private lateinit var qaRuns: QaRunRepository
    @Autowired private lateinit var storage: DocumentStorage
    private val worlds = mutableListOf<World>()
    private val runIds = mutableListOf<Long>()

    @AfterEach
    fun cleanup(): Unit = runBlocking {
        for (id in runIds) qaRuns.deleteById(id)
        for (world in worlds) {
            sessions.removeSession(world.instanceId.toString(), world.socket)
            runtime.disconnected(world.instanceId.toString(), world.socket.id)
            world.subscription.dispose()
            for (table in listOf("qa_procedure_usage", "verification_procedure", "demonstration_source_revision", "demonstration_event")) {
                val predicate = if (table == "qa_procedure_usage")
                    "procedure_id IN (SELECT id FROM verification_procedure WHERE session_id IN (SELECT id FROM demonstration_session WHERE project_id=:project))"
                else "session_id IN (SELECT id FROM demonstration_session WHERE project_id=:project)"
                repo.db.sql("DELETE FROM $table WHERE $predicate").bind("project", world.projectId).fetch().rowsUpdated().awaitSingle()
            }
            repo.db.sql("DELETE FROM demonstration_session WHERE project_id=:project")
                .bind("project", world.projectId).fetch().rowsUpdated().awaitSingle()
        }
        worlds.clear()
        runIds.clear()
        (storage as FakeDocumentStorage).clear()
    }

    @Test
    fun `동시에 시작한 시연 중 하나만 instance를 사용한다`(): Unit = runBlocking {
        val world = world()
        val results = coroutineScope {
            (1..2).map { async { runCatching { start(world) } } }.awaitAll()
        }
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.single { it.isFailure }.exceptionOrNull()).isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `사람 제어권이 남아 있으면 QA와 다른 generation 입력을 거절한다`(): Unit = runBlocking {
        val world = world()
        val row = start(world)
        conflict { guard.dispatch(world.instanceId, null) { "QA" } }
        conflict { guard.dispatch(world.instanceId, DemonstrationIdentity(row.id.toString(), row.generation)) { "before ACK" } }
        ready(world, row)
        assertThat(guard.dispatch(world.instanceId, DemonstrationIdentity(row.id.toString(), row.generation)) { "human" }).isEqualTo("human")
        conflict { guard.dispatch(world.instanceId, DemonstrationIdentity(row.id.toString(), row.generation + 1)) { "stale" } }
        service.stop(row.id, world.userId)
        conflict { guard.dispatch(world.instanceId, null) { "QA" } }
        repo.db.sql("UPDATE demonstration_session SET lease_expires_at=now()-interval '1 second' WHERE id=:id")
            .bind("id", row.id).fetch().rowsUpdated().awaitSingle()
        assertThat(guard.dispatch(world.instanceId, null) { "QA" }).isEqualTo("QA")
    }

    @Test
    fun `QA가 실행 중인 instance에서는 시연을 시작할 수 없다`(): Unit = runBlocking {
        val world = world()
        val testRun = testRuns.save(TestRunEntity(projectId = world.projectId, name = "시연 충돌 검증"))
        runIds += qaRuns.save(QaRunEntity(testRunId = testRun.id!!, gameInstanceId = world.instanceId,
            startedBy = world.userId, status = "RUNNING", startedAt = Instant.now())).id!!
        conflict { start(world) }
        assertThat(repo.list(world.projectId, world.caseId)).isEmpty()
    }

    @Test
    fun `SDK 정상 결과는 빈 error를 허용하고 동시 중복 결과를 한번만 기록한다`(): Unit = runBlocking {
        val world = world()
        val row = start(world)
        val requestId = repo.nextRequestId()
        repo.append(row.id, "INPUT", "{}", requestId, 1)
        val payload = result(requestId, true)
        assertThat(service.actionResult(world.instanceId + 100000, payload)).isFalse()
        coroutineScope { (1..2).map { async { service.actionResult(world.instanceId, payload) } }.awaitAll() }
        assertThat(repo.command(world.instanceId, requestId)!!.commandStatus).isEqualTo("APPLIED")
        assertThat(repo.events(row.id).filter { it.kind == "ACTION_RESULT" }).hasSize(1)
    }

    @Test
    fun `실패와 불완전한 SDK 결과를 적용 성공으로 기록하지 않는다`(): Unit = runBlocking {
        val world = world()
        val row = start(world)
        val failedId = repo.nextRequestId()
        val unknownId = repo.nextRequestId()
        repo.append(row.id, "INPUT", "{}", failedId, 1)
        repo.append(row.id, "INPUT", "{}", unknownId, 2)
        service.actionResult(world.instanceId, result(failedId, false))
        service.actionResult(world.instanceId, """{"requestId":$unknownId,"results":[{"id":$unknownId}]}""")
        assertThat(repo.command(world.instanceId, failedId)!!.commandStatus).isEqualTo("FAILED")
        assertThat(repo.command(world.instanceId, unknownId)!!.commandStatus).isEqualTo("UNKNOWN")
    }

    @Test
    fun `다른 SDK 연결과 generation의 ACK는 제어권을 열지 않는다`(): Unit = runBlocking {
        val world = world()
        val row = start(world)
        service.controlResult(world.instanceId, "old-connection",
            DemonstrationControlResult(row.id.toString(), row.generation, "ACQUIRE", true))
        service.controlResult(world.instanceId, world.socket.id,
            DemonstrationControlResult(row.id.toString(), row.generation + 1, "ACQUIRE", true))
        assertThat(repo.find(row.id)!!.controlReady).isFalse()
        ready(world, row)
        assertThat(repo.find(row.id)!!.controlReady).isTrue()
    }

    @Test
    fun `다른 project member는 시연을 변경하거나 같은 입력 sequence를 재전송할 수 없다`(): Unit = runBlocking {
        val world = world()
        val other = world()
        members.save(ProjectMemberEntity(projectId = world.projectId, appUserId = other.userId,
            role = "MEMBER", createdAt = Instant.now()))
        val row = start(world)
        ready(world, row)
        assertThat(runCatching { service.stop(row.id, other.userId) }.exceptionOrNull())
            .isInstanceOf(ForbiddenException::class.java)
        val command = DemonstrationInput("INPUT", 1, "key_down", listOf(mapper.valueToTree("Space")))
        service.input(row.id, world.userId, command)
        conflict { service.input(row.id, world.userId, command) }
        assertThat(repo.events(row.id).filter { it.kind == "INPUT" }).hasSize(1)
    }

    @Test
    fun `seal 뒤 늦게 온 결과는 원본 hash와 UNKNOWN 판정을 바꾸지 않는다`(): Unit = runBlocking {
        val world = world()
        val row = start(world)
        val requestId = repo.nextRequestId()
        repo.append(row.id, "INPUT", "{}", requestId, 1)
        val sealed = seal(world, row)
        assertThat(repo.command(world.instanceId, requestId)!!.commandStatus).isEqualTo("UNKNOWN")
        assertThat(service.actionResult(world.instanceId, result(requestId, true))).isTrue()
        assertThat(repo.find(row.id)!!.sourceHash).isEqualTo(sealed.sourceHash)
        assertThat(repo.command(world.instanceId, requestId)!!.commandStatus).isEqualTo("UNKNOWN")
        assertThat(repo.events(row.id).last().kind).isEqualTo("LATE_RESULT")
        assertThat(service.settle(row.id).sourceHash).isEqualTo(sealed.sourceHash)
    }

    @Test
    fun `승인된 절차는 같은 project TC 내용과 build에서만 사용한다`(): Unit = runBlocking {
        val world = world()
        val prepared = draft(world)
        val approved = procedures.approve(prepared.row.id, world.userId, 1)
        assertThat(approved.status).isEqualTo("APPROVED")
        val testCase = service.caseOf(world.projectId, world.caseId, world.buildId)
        assertThat(procedures.forCase(world.projectId, world.buildId, testCase)?.id).isEqualTo(approved.id)
        assertThat(procedures.forCase(world.projectId + 1, world.buildId, testCase)).isNull()
        assertThat(procedures.forCase(world.projectId, world.buildId + 1, testCase)).isNull()
        assertThat(procedures.forCase(world.projectId, world.buildId, testCase.copy(expected = "새 기대 결과"))).isNull()
        assertThat(procedures.forCase(world.projectId, world.buildId, testCase.copy(id = world.caseId + 1))).isNull()
        procedures.revoke(prepared.row.id, world.userId, 1)
        assertThat(procedures.forCase(world.projectId, world.buildId, testCase)).isNull()
    }

    @Test
    fun `초안 편집은 새 revision을 만들고 오래된 승인을 거절한다`(): Unit = runBlocking {
        val world = world()
        val prepared = draft(world)
        val edited = procedures.edit(prepared.row.id, world.userId,
            ProcedureEdit(1, prepared.procedure.copy(title = "구매 후 아이템 확인")))
        assertThat(edited.revision).isEqualTo(2)
        conflict { procedures.approve(prepared.row.id, world.userId, 1) }
        assertThat(procedures.approve(prepared.row.id, world.userId, 2).status).isEqualTo("APPROVED")
    }

    @Test
    fun `같은 TC와 build의 두 초안을 동시에 승인해도 활성 승인본은 하나다`(): Unit = runBlocking {
        val world = world()
        val first = draft(world)
        repo.db.sql("UPDATE demonstration_session SET lease_expires_at=now()-interval '1 second' WHERE id=:id")
            .bind("id", first.row.id).fetch().rowsUpdated().awaitSingle()
        val second = draft(world)
        coroutineScope {
            listOf(first, second).map { prepared ->
                async { procedures.approve(prepared.row.id, world.userId, 1) }
            }.awaitAll()
        }
        val statuses = listOf(repo.latest(first.row.id)!!.status, repo.latest(second.row.id)!!.status)
        assertThat(statuses).containsExactlyInAnyOrder("APPROVED", "SUPERSEDED")
        val activeProjectionCount = repo.db.sql("SELECT count(*) AS n FROM knowledge WHERE project_id=:id AND source='DEMONSTRATION' AND deleted_at IS NULL")
            .bind("id", world.projectId).map { row -> row.get("n", java.lang.Long::class.java)!!.toLong() }.one().awaitSingle()
        assertThat(activeProjectionCount).isEqualTo(1)
    }

    @Test
    fun `판정 근거를 추가하면 이전 승인과 source hash를 교체한다`(): Unit = runBlocking {
        val world = world()
        val prepared = draft(world)
        procedures.approve(prepared.row.id, world.userId, 1)
        evidence.annotate(prepared.row.id, world.userId,
            DemonstrationAnnotation("FAIL", "아이템 없음", "아이템 추가", "골드만 감소함", prepared.evidenceId))
        assertThat(repo.find(prepared.row.id)!!.sourceHash).isNotEqualTo(prepared.row.sourceHash)
        assertThat(repo.latest(prepared.row.id)!!.status).isEqualTo("SUPERSEDED")
        conflict { procedures.approve(prepared.row.id, world.userId, 1) }
        assertThat(procedures.forCase(world.projectId, world.buildId,
            service.caseOf(world.projectId, world.caseId, world.buildId))).isNull()
    }

    @Test
    fun `사라진 화면과 미확인 입력이 있는 초안은 승인하지 않는다`(): Unit = runBlocking {
        val world = world()
        val prepared = draft(world)
        storage.delete(prepared.objectKey).block()
        assertThat(runCatching { procedures.approve(prepared.row.id, world.userId, 1) }.exceptionOrNull())
            .isInstanceOf(ConflictException::class.java)
        (storage as FakeDocumentStorage).put(prepared.objectKey, demonstrationPng())
        val requestId = repo.nextRequestId()
        repo.append(prepared.row.id, "INPUT", "{}", requestId, 1)
        repo.db.sql("UPDATE demonstration_event SET command_status='UNKNOWN' WHERE request_id=:id")
            .bind("id", requestId).fetch().rowsUpdated().awaitSingle()
        conflict { procedures.approve(prepared.row.id, world.userId, 1) }
    }

    @Test
    fun `입력 ACK는 SDK 결과를 기다리고 FAILED도 다음 입력을 허용한다`(): Unit = runBlocking {
        val world = world()
        val row = start(world)
        ready(world, row)
        for ((index, success) in listOf(true, false).withIndex()) {
            val sequence = index.toLong() + 1
            val reply = async { controlSocket.inputReply(row.id, world.userId,
                DemonstrationInput("INPUT", sequence, "key_down", listOf(mapper.valueToTree("Space")))) }
            val event = withTimeout(1000) {
                var input: DemonstrationEvent? = null
                while (input == null) {
                    input = repo.events(row.id).lastOrNull { it.kind == "INPUT" && mapper.readTree(it.payloadJson).path("sequence").asLong() == sequence }
                    if (input == null) delay(10)
                }
                input
            }
            delay(80)
            assertThat(reply.isCompleted).isFalse()
            val requestId = mapper.readTree(event.payloadJson).path("requestId").asLong()
            service.actionResult(world.instanceId, result(requestId, success))
            assertThat(reply.await()).isEqualTo(DemonstrationReply("ACK", sequence, event.id))
            assertThat(repo.find(row.id)!!.status).isEqualTo("RECORDING")
            // 응답이 대기보다 먼저 기록된 경우도 이미 저장된 결과로 완료한다.
            service.awaitInputCompletion(row.id, world.userId, event.id)
        }
    }

    @Test
    fun `SDK가 응답하지 않으면 ACK 없이 UNKNOWN으로 남기고 제어를 종료한다`(): Unit = runBlocking {
        val world = world()
        val row = start(world)
        ready(world, row)
        conflict { controlSocket.inputReply(row.id, world.userId,
            DemonstrationInput("INPUT", 1, "key_down", listOf(mapper.valueToTree("Space")))) }
        val event = repo.events(row.id).single { it.kind == "INPUT" }
        val requestId = mapper.readTree(event.payloadJson).path("requestId").asLong()
        assertThat(repo.command(world.instanceId, requestId)!!.commandStatus).isEqualTo("UNKNOWN")
        assertThat(repo.find(row.id)!!.status).isEqualTo("INTERRUPTED")
        service.actionResult(world.instanceId, result(requestId, true))
        assertThat(repo.command(world.instanceId, requestId)!!.commandStatus).isEqualTo("UNKNOWN")
        assertThat(repo.events(row.id).last().kind).isEqualTo("LATE_RESULT")
    }

    @Test
    fun `UNKNOWN 결과와 만료된 lease는 완료 ACK 대신 제어를 종료한다`(): Unit = runBlocking {
        for (unknown in listOf(true, false)) {
            val world = world()
            val row = start(world)
            ready(world, row)
            val event = service.input(row.id, world.userId,
                DemonstrationInput("INPUT", 1, "key_down", listOf(mapper.valueToTree("Space"))))
            val requestId = mapper.readTree(event.payloadJson).path("requestId").asLong()
            if (unknown) service.actionResult(world.instanceId, """{"requestId":$requestId,"results":[]}""")
            else repo.db.sql("UPDATE demonstration_session SET lease_expires_at=now()-interval '1 second' WHERE id=:id")
                .bind("id", row.id).fetch().rowsUpdated().awaitSingle()
            conflict { service.awaitInputCompletion(row.id, world.userId, event.id) }
            assertThat(repo.command(world.instanceId, requestId)!!.commandStatus).isEqualTo("UNKNOWN")
            assertThat(repo.find(row.id)!!.status).isEqualTo("INTERRUPTED")
        }
    }

    private suspend fun conflict(action: suspend () -> Any?) {
        assertThat(runCatching { action() }.exceptionOrNull()).isInstanceOf(ConflictException::class.java)
    }

    private fun result(id: Long, success: Boolean): String =
        """{"type":"ACTION_RESULT","requestId":$id,"results":[{"id":$id,"success":$success,"error":"${if (success) "" else "input rejected"}"}]}"""

    private suspend fun start(world: World) = service.create(world.projectId, world.userId,
        DemonstrationStart(world.caseId, world.scenarioId, world.instanceId))

    private suspend fun ready(world: World, row: DemonstrationRow) = service.controlResult(world.instanceId,
        world.socket.id, DemonstrationControlResult(row.id.toString(), row.generation, "ACQUIRE", true))

    private suspend fun seal(world: World, row: DemonstrationRow): DemonstrationRow {
        service.stop(row.id, world.userId)
        repo.db.sql("UPDATE demonstration_session SET stopped_at=now()-interval '11 seconds' WHERE id=:id")
            .bind("id", row.id).fetch().rowsUpdated().awaitSingle()
        return service.settle(row.id)
    }

    private data class Prepared(val row: DemonstrationRow, val evidenceId: Long, val objectKey: String, val procedure: VerificationProcedure)

    private suspend fun draft(world: World): Prepared {
        val row = start(world)
        val image = evidence.upload(row.id, world.userId, EvidenceUpload(
            "data:image/png;base64," + Base64.getEncoder().encodeToString(demonstrationPng()), Instant.now(), 1, 1))
        val key = mapper.readValue(image.payloadJson, DemonstrationEvidence::class.java).objectKey
        val annotation = repo.append(row.id, "ANNOTATION", mapper.writeValueAsString(
            DemonstrationAnnotation("FAIL", "아이템 없음", "아이템 추가", "골드만 감소함", image.id)))
        val sealed = seal(world, row)
        val procedure = VerificationProcedure("구매 검증", listOf("골드가 충분함"),
            listOf(ProcedureStep("구매", "구매 버튼", "구매 완료", listOf(image.id))),
            listOf(ProcedureAssertion("아이템 추가", "골드만 감소", "구매 후", listOf(annotation.id))),
            "FAIL", listOf("사용자가 아이템이 없다고 설명함"), emptyList(), emptyList())
        repo.insertProcedure(row.id, 1, sealed.sourceHash!!, mapper.writeValueAsString(procedure))
        return Prepared(sealed, image.id, key, procedure)
    }

    private data class World(val userId: Long, val projectId: Long, val buildId: Long, val instanceId: Long,
        val caseId: Long, val scenarioId: Long, val socket: WebSocketSession, val subscription: Disposable)

    private suspend fun world(): World {
        val now = Instant.now()
        val userId = repo.db.sql("INSERT INTO app_user(display_name,nickname,user_tag) VALUES('시연 테스트','demo-'||gen_random_uuid(),'0000') RETURNING id")
            .map { row -> row.get("id", java.lang.Long::class.java)!!.toLong() }.one().awaitSingle()
        val projectId = projects.save(ProjectEntity(name = "demo-${UUID.randomUUID()}", genre = "ACTION", createdAt = now, updatedAt = now)).id!!
        members.save(ProjectMemberEntity(projectId = projectId, appUserId = userId, role = "OWNER", createdAt = now))
        val buildId = builds.save(GameBuildEntity(projectId = projectId, version = "1", createdAt = now, updatedAt = now)).id!!
        val instanceId = instances.save(GameInstanceEntity(projectId = projectId, name = "시연 게임", platform = "UNITY",
            lastGameBuildId = buildId, createdAt = now, updatedAt = now)).id!!
        val testCase = cases.save(TestCaseEntity(projectId = projectId, scene = "Shop", step = "구매", expectedValue = "아이템 추가"))
        versions.save(TestCaseVersionEntity.snapshotOf(testCase, buildId))
        val scenarioId = scenarios.save(TestScenarioEntity(projectId = projectId,
            steps = Json.of("""[{"action":"구매","case_id":${testCase.id}}]"""))).id!!
        val socket = Mockito.mock(WebSocketSession::class.java)
        Mockito.`when`(socket.id).thenReturn("sdk-${UUID.randomUUID()}")
        val subscription = sessions.register(instanceId.toString(), socket)!!.subscribe()
        runtime.announce(instanceId.toString(), socket.id, DemonstrationCapabilities(1,
            listOf("move_mouse", "mouse_down", "mouse_up", "key_down", "key_up", "enter_text")))
        return World(userId, projectId, buildId, instanceId, testCase.id!!, scenarioId, socket, subscription).also(worlds::add)
    }
}
