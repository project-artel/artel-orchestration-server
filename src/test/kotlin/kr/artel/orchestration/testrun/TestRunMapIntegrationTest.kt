package kr.artel.orchestration.testrun

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.event.ProjectChangeStreamManager
import kr.artel.orchestration.project.event.ProjectChangeTopic
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.support.testAppUser
import kr.artel.orchestration.testrun.dto.RunMapAnnotation
import kr.artel.orchestration.testrun.dto.RunMapFrame
import kr.artel.orchestration.testrun.dto.TestRunCreateRequest
import kr.artel.orchestration.testrun.dto.TestRunMapSaveRequest
import kr.artel.orchestration.testrun.repository.TestRunMapRepository
import kr.artel.orchestration.testrun.service.TestRunMapService
import kr.artel.orchestration.testrun.service.TestRunService
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/** 테스트 런 맵의 배치 · 주석 저장(ARTEL-1244). */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TestRunMapIntegrationTest {

    @Autowired private lateinit var mapService: TestRunMapService
    @Autowired private lateinit var mapRepository: TestRunMapRepository
    @Autowired private lateinit var runService: TestRunService
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var memberRepository: ProjectMemberRepository
    @Autowired private lateinit var scenarioRepository: TestScenarioRepository
    @Autowired private lateinit var streamManager: ProjectChangeStreamManager
    @Autowired private lateinit var objectMapper: ObjectMapper

    @Test
    fun `맵이 없으면 버전 0 과 빈 값을 준다`(): Unit = runBlocking {
        val f = fixture()
        val map = mapService.get(f.projectId, f.runId, f.userId)
        assertThat(map.version).isZero()
        assertThat(map.layout).isEmpty()
        assertThat(map.annotations).isEmpty()
    }

    @Test
    fun `저장하면 버전이 오르고 만든 사람이 붙는다`(): Unit = runBlocking {
        val f = fixture()
        val saved = mapService.save(
            f.projectId, f.runId, f.userId,
            TestRunMapSaveRequest(
                version = 0,
                layout = mapOf(f.scenarioId.toString() to RunMapFrame(x = 20.0, y = 40.0, folded = true)),
                annotations = listOf(note("n1", "TC 더 필요"), RunMapAnnotation(id = "p1", type = "pen", points = listOf(listOf(0.0, 0.0), listOf(5.0, 5.0)))),
            ),
        )
        assertThat(saved.version).isEqualTo(1)
        assertThat(saved.layout[f.scenarioId.toString()]).isEqualTo(RunMapFrame(20.0, 40.0, true))
        assertThat(saved.annotations.map { it.createdBy }).containsOnly(f.userId.toString())
        assertThat(saved.authors[f.userId.toString()]).isNotBlank()

        val again = mapService.get(f.projectId, f.runId, f.userId)
        assertThat(again.annotations.map { it.text }).contains("TC 더 필요")
    }

    @Test
    fun `있던 주석은 다른 사람이 고쳐도 만든 사람이 그대로다`(): Unit = runBlocking {
        val f = fixture()
        val other = member(f.projectId)
        val first = mapService.save(f.projectId, f.runId, f.userId, TestRunMapSaveRequest(version = 0, annotations = listOf(note("n1", "처음"))))
        val second = mapService.save(f.projectId, f.runId, other, TestRunMapSaveRequest(version = first.version, annotations = listOf(note("n1", "고침"), note("n2", "새것"))))
        val byId = second.annotations.associateBy { it.id }
        assertThat(byId["n1"]!!.createdBy).isEqualTo(f.userId.toString())
        assertThat(byId["n1"]!!.text).isEqualTo("고침")
        assertThat(byId["n2"]!!.createdBy).isEqualTo(other.toString())
        assertThat(second.updatedBy).isEqualTo(other.toString())
    }

    @Test
    fun `읽은 뒤 다른 사람이 먼저 저장하면 409`(): Unit = runBlocking {
        val f = fixture()
        mapService.save(f.projectId, f.runId, f.userId, TestRunMapSaveRequest(version = 0, annotations = listOf(note("a", "먼저"))))
        val error = assertThrows<ConflictException> {
            runBlocking { mapService.save(f.projectId, f.runId, f.userId, TestRunMapSaveRequest(version = 0, annotations = listOf(note("b", "나중")))) }
        }
        assertThat(error.code).isEqualTo("run_map_stale")
        assertThat(mapService.get(f.projectId, f.runId, f.userId).annotations.map { it.id }).containsExactly("a")
    }

    @Test
    fun `런에 없는 TS 의 자리는 버린다`(): Unit = runBlocking {
        val f = fixture()
        val saved = mapService.save(
            f.projectId, f.runId, f.userId,
            TestRunMapSaveRequest(version = 0, layout = mapOf(f.scenarioId.toString() to RunMapFrame(1.0, 1.0), "999999" to RunMapFrame(2.0, 2.0))),
        )
        assertThat(saved.layout.keys).containsExactly(f.scenarioId.toString())
    }

    @Test
    fun `모르는 type 이나 너무 긴 글은 400`(): Unit = runBlocking {
        val f = fixture()
        assertThrows<BadRequestException> {
            runBlocking { mapService.save(f.projectId, f.runId, f.userId, TestRunMapSaveRequest(version = 0, annotations = listOf(RunMapAnnotation(id = "x", type = "video", x = 0.0, y = 0.0)))) }
        }
        assertThrows<BadRequestException> {
            runBlocking { mapService.save(f.projectId, f.runId, f.userId, TestRunMapSaveRequest(version = 0, annotations = listOf(note("x", "가".repeat(2_001))))) }
        }
        assertThrows<BadRequestException> {
            runBlocking { mapService.save(f.projectId, f.runId, f.userId, TestRunMapSaveRequest(version = 0, annotations = listOf(note("x", "a"), note("x", "b")))) }
        }
    }

    @Test
    fun `휜 화살표 · 글을 넣은 도형 · 묶음 상자 · 크기를 바꾼 메모를 그대로 돌려준다`(): Unit = runBlocking {
        val f = fixture()
        val saved = mapService.save(
            f.projectId, f.runId, f.userId,
            TestRunMapSaveRequest(
                version = 0,
                annotations = listOf(
                    RunMapAnnotation(id = "a1", type = "arrow", x1 = 0.0, y1 = 0.0, x2 = 100.0, y2 = 0.0, cx = 50.0, cy = 40.0),
                    RunMapAnnotation(id = "r1", type = "rect", x = 0.0, y = 0.0, w = 80.0, h = 40.0, text = "로그인 구간"),
                    RunMapAnnotation(id = "s1", type = "section", x = 0.0, y = 0.0, w = 600.0, h = 400.0, text = "튜토리얼"),
                    RunMapAnnotation(id = "n1", type = "note", x = 0.0, y = 0.0, w = 260.0, h = 180.0, text = "크게", color = "b"),
                ),
            ),
        )
        val byId = saved.annotations.associateBy { it.id }
        assertThat(byId["a1"]!!.cx).isEqualTo(50.0)
        assertThat(byId["r1"]!!.text).isEqualTo("로그인 구간")
        assertThat(byId["s1"]!!.text).isEqualTo("튜토리얼")
        assertThat(byId["n1"]!!.w).isEqualTo(260.0)
        assertThrows<BadRequestException> {
            runBlocking { mapService.save(f.projectId, f.runId, f.userId, TestRunMapSaveRequest(version = saved.version, annotations = listOf(RunMapAnnotation(id = "a2", type = "arrow", x1 = 0.0, y1 = 0.0, x2 = 1.0, y2 = 1.0, cx = 5.0)))) }
        }
    }

    @Test
    fun `참여자가 아니거나 다른 프로젝트 경로면 404`(): Unit = runBlocking {
        val f = fixture()
        val stranger = appUserRepository.save(testAppUser("run-map-stranger", Instant.now())).id!!
        assertThrows<NotFoundException> { runBlocking { mapService.get(f.projectId, f.runId, stranger) } }
        val otherProject = fixture()
        assertThrows<NotFoundException> { runBlocking { mapService.get(otherProject.projectId, f.runId, f.userId) } }
    }

    @Test
    fun `런을 지우면 맵도 사라진다`(): Unit = runBlocking {
        val f = fixture()
        mapService.save(f.projectId, f.runId, f.userId, TestRunMapSaveRequest(version = 0, annotations = listOf(note("a", "메모"))))
        runService.delete(f.runId, f.userId, dropScenarios = false)
        assertThat(mapRepository.findById(f.runId)).isNull()
    }

    @Test
    fun `저장하면 프로젝트에 TEST_RUNS 변경을 알린다`(): Unit = runBlocking {
        val f = fixture()
        val next = async { withTimeout(5_000) { streamManager.stream(f.projectId).first() } }
        yield()
        mapService.save(f.projectId, f.runId, f.userId, TestRunMapSaveRequest(version = 0))
        assertThat(next.await()).isEqualTo(ProjectChangeTopic.TEST_RUNS)
    }

    private fun note(id: String, text: String) = RunMapAnnotation(id = id, type = "note", x = 10.0, y = 20.0, text = text, color = "y")

    private data class Fixture(val projectId: Long, val userId: Long, val runId: Long, val scenarioId: Long)

    private suspend fun fixture(): Fixture {
        val now = Instant.now()
        val userId = appUserRepository.save(testAppUser("run-map", now)).id!!
        val projectId = projectRepository.save(ProjectEntity(name = "run-map", genre = "ACTION", createdAt = now, updatedAt = now)).id!!
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
        val id = appUserRepository.save(testAppUser("run-map-member", now)).id!!
        memberRepository.save(ProjectMemberEntity(projectId = projectId, appUserId = id, role = "MEMBER", createdAt = now))
        return id
    }
}
