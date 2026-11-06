package kr.artel.orchestration.testrun

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.support.testAppUser
import kr.artel.orchestration.testcase.dto.TestCaseCreateRequest
import kr.artel.orchestration.testcase.service.TestCaseService
import kr.artel.orchestration.testrun.dto.TestRunCreateRequest
import kr.artel.orchestration.testrun.service.TestRunService
import kr.artel.orchestration.testscenario.dto.ScenarioStep
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * **현재 시나리오에 한정된 커버리지**(ARTEL-903).
 *
 * 저작 중에는 씬별 집계(`TurnBattleScene 8/29`)가 대화로 나갔다. 씬이 저작의 단위였던 시절의
 * 값인데, 시나리오는 여러 씬을 지나는 흐름이라 그 비율로는 다음에 무엇을 할지 정할 수 없다.
 * 그 안내를 걷어내고 이 조회로 옮겼다 — 단위는 사용자가 만든 것, 곧 시나리오다.
 *
 * 프로젝트 전량을 재는 `TestCaseService.coverage` 와 축이 다르다는 것이 이 검사의 요점이다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RunCoverageIntegrationTest {

    @Autowired private lateinit var testCaseService: TestCaseService
    @Autowired private lateinit var runService: TestRunService
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var memberRepository: ProjectMemberRepository
    @Autowired private lateinit var scenarioRepository: TestScenarioRepository
    @Autowired private lateinit var objectMapper: ObjectMapper

    @Test
    fun `런의 시나리오별로 담은 스텝과 케이스를 낸다`(): Unit = runBlocking {
        val (projectId, userId) = project()
        val a = case(projectId, userId)
        val b = case(projectId, userId)
        case(projectId, userId) // 아무 시나리오도 담지 않은 케이스
        // 두 번째 스텝은 `bridge` 처럼 아무것도 검증하지 않는다 — 스텝 수와 케이스 수가 갈리는 자리다.
        val first = scenario(projectId, "앞 흐름", listOf(a, null))
        val second = scenario(projectId, "뒤 흐름", listOf(b))
        val run = runService.create(projectId, userId, TestRunCreateRequest(name = "런"))!!
        runService.setScenarios(run.id.toLong(), userId, listOf(first, second))

        val coverage = runService.coverage(run.id.toLong(), userId)!!

        assertThat(coverage.total).isEqualTo(3)
        assertThat(coverage.covered).isEqualTo(2)
        assertThat(coverage.uncovered).isEqualTo(1)
        assertThat(coverage.scenarios.map { it.title }).containsExactly("앞 흐름", "뒤 흐름")
        assertThat(coverage.scenarios.map { it.position }).containsExactly(0, 1)
        // 스텝 둘 중 케이스를 지고 있는 것은 하나다. 그 차이가 "옮겨 가기만 하는 스텝" 의 수다.
        assertThat(coverage.scenarios.first().steps).isEqualTo(2)
        assertThat(coverage.scenarios.first().cases).isEqualTo(1)
    }

    /**
     * **겹치는 케이스는 한 번만 센다.**
     *
     * 같은 조작도 상태가 다르면 다른 검증이라, 같은 케이스가 두 시나리오에 들어가는 것은 정상이다.
     * 그것을 2로 세면 런이 실제로 덮은 범위보다 커지고, 프로젝트 전량과 나란히 놓을 수 없게 된다.
     */
    @Test
    fun `두 시나리오가 같은 케이스를 담아도 런 합계는 한 번만 센다`(): Unit = runBlocking {
        val (projectId, userId) = project()
        val shared = case(projectId, userId)
        val run = runService.create(projectId, userId, TestRunCreateRequest(name = "런"))!!
        runService.setScenarios(
            run.id.toLong(), userId,
            listOf(
                scenario(projectId, "맥락 A", listOf(shared)),
                scenario(projectId, "맥락 B", listOf(shared)),
            ),
        )

        val coverage = runService.coverage(run.id.toLong(), userId)!!

        assertThat(coverage.scenarios.sumOf { it.cases }).isEqualTo(2) // 시나리오별로는 각 1건
        assertThat(coverage.covered).isEqualTo(1)                      // 런 합계는 서로 다른 1건
        assertThat(coverage.uncovered).isZero()
    }

    /** 씬으로 묶지 않는다 — 그 축이 이 조회가 대신하는 것이다. */
    @Test
    fun `응답에 씬 축이 없다`(): Unit = runBlocking {
        val (projectId, userId) = project()
        val run = runService.create(projectId, userId, TestRunCreateRequest(name = "런"))!!
        runService.setScenarios(
            run.id.toLong(), userId, listOf(scenario(projectId, "흐름", listOf(case(projectId, userId)))),
        )

        val fields = runService.coverage(run.id.toLong(), userId)!!.let {
            objectMapper.writeValueAsString(it)
        }

        assertThat(fields).doesNotContain("scene").doesNotContain("TitleScene")
    }

    @Test
    fun `비어 있는 런은 담은 것이 없다고 답한다`(): Unit = runBlocking {
        val (projectId, userId) = project()
        case(projectId, userId)
        val run = runService.create(projectId, userId, TestRunCreateRequest(name = "런"))!!

        val coverage = runService.coverage(run.id.toLong(), userId)!!

        assertThat(coverage.scenarios).isEmpty()
        assertThat(coverage.covered).isZero()
        assertThat(coverage.uncovered).isEqualTo(1)
    }

    /** 남의 프로젝트 런은 세어 주지 않는다 — 건수를 흘리는 것도 존재를 알리는 일이다. */
    @Test
    fun `비참여자에게는 답하지 않는다`(): Unit = runBlocking {
        val (projectId, userId) = project()
        val run = runService.create(projectId, userId, TestRunCreateRequest(name = "런"))!!
        runService.setScenarios(
            run.id.toLong(), userId, listOf(scenario(projectId, "흐름", listOf(case(projectId, userId)))),
        )

        assertThat(runService.coverage(run.id.toLong(), user())).isNull()
    }

    /** 프로젝트 전량과 **다른 축**이다. 런에 안 담긴 시나리오는 이 조회에 없다. */
    @Test
    fun `프로젝트 커버리지와 축이 다르다`(): Unit = runBlocking {
        val (projectId, userId) = project()
        val inRun = case(projectId, userId)
        val elsewhere = case(projectId, userId)
        val run = runService.create(projectId, userId, TestRunCreateRequest(name = "런"))!!
        runService.setScenarios(
            run.id.toLong(), userId, listOf(scenario(projectId, "이 런의 흐름", listOf(inRun))),
        )
        // 다른 런이 나머지 케이스를 담는다 — 프로젝트로 보면 전부 저작됐다.
        val other = runService.create(projectId, userId, TestRunCreateRequest(name = "다른 런"))!!
        runService.setScenarios(
            other.id.toLong(), userId, listOf(scenario(projectId, "남의 흐름", listOf(elsewhere))),
        )

        assertThat(testCaseService.coverage(projectId, userId).unauthored).isZero()
        // 이 런만 보면 하나가 비어 있다. 저작 중에 알고 싶은 것이 이쪽이다.
        val coverage = runService.coverage(run.id.toLong(), userId)!!
        assertThat(coverage.covered).isEqualTo(1)
        assertThat(coverage.uncovered).isEqualTo(1)
        assertThat(coverage.scenarios.map { it.title }).containsExactly("이 런의 흐름")
    }

    private suspend fun user(): Long =
        appUserRepository.save(testAppUser("run-coverage", Instant.now())).id!!

    private suspend fun project(): Pair<Long, Long> {
        val now = Instant.now()
        val userId = user()
        val projectId = projectRepository.save(
            ProjectEntity(name = "run-coverage", genre = "ACTION", createdAt = now, updatedAt = now)
        ).id!!
        memberRepository.save(
            ProjectMemberEntity(projectId = projectId, appUserId = userId, role = "MEMBER", createdAt = now)
        )
        return projectId to userId
    }

    private var seq = 0

    private suspend fun case(projectId: Long, userId: Long): Long =
        testCaseService.createTestCase(
            projectId, userId,
            TestCaseCreateRequest(
                scene = "TitleScene", step = "시작을 누른다 ${seq++}", expectedValue = "다음 화면",
            ),
        )!!.id.toLong()

    /** `null` 자리는 케이스를 지지 않는 스텝 — 코드가 메우는 `bridge` 가 그 모양이다. */
    private suspend fun scenario(projectId: Long, title: String, caseIds: List<Long?>): Long =
        scenarioRepository.save(
            TestScenarioEntity(
                projectId = projectId,
                title = title,
                steps = Json.of(
                    objectMapper.writeValueAsString(
                        caseIds.map { ScenarioStep(action = "확인", caseId = it) }
                    )
                ),
            )
        ).id!!
}
