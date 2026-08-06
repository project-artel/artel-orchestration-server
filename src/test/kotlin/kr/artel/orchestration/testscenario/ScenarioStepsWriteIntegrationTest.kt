package kr.artel.orchestration.testscenario

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.entity.AppUserEntity
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testcase.dto.TestCaseCreateRequest
import kr.artel.orchestration.testcase.service.TestCaseService
import kr.artel.orchestration.testscenario.dto.ScenarioStepDto
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import kr.artel.orchestration.testscenario.service.ScenarioCasePlacement
import kr.artel.orchestration.testscenario.service.ScenarioCompositionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * 저작 Step 쓰기 경로(ARTEL-269). 조합 저장이 자리별 steps를 함께 넣고, 그게 조회에서 그대로
 * 돌아오는지 — 그리고 순서만 바꾸는 기존 경로는 steps를 캐리포워드하는지 — 를 서비스 레이어로
 * 검증한다. 이 경로가 없으면 case는 늘 빈 steps로 Agent에 나간다(254→261 파이프라인의 생산자).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ScenarioStepsWriteIntegrationTest {

    @Autowired private lateinit var compositionService: ScenarioCompositionService
    @Autowired private lateinit var testCaseService: TestCaseService
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository

    @Test
    fun `items 경로가 자리별 저작 Step을 저장하고 조회에서 그대로 돌아온다`(): Unit = runBlocking {
        val (projectId, userId) = projectWithMember()
        val c1 = case(projectId, userId, "로그인")
        val c2 = case(projectId, userId, "설정")
        val scenarioId = scenario(projectId)

        val saved = compositionService.setCasesWithSteps(
            scenarioId, userId,
            listOf(
                ScenarioCasePlacement(
                    c1, listOf(
                        ScenarioStepDto(kind = "setup", assert = false, intent = "타이틀로 이동", hint = "Esc"),
                        ScenarioStepDto(kind = "guide", intent = "시작 누르기", input = "keyboard"),
                        ScenarioStepDto(kind = "verify", intent = "홈 확인", observe = "홈 HUD"),
                    )
                ),
                // 빈 steps는 그 자리를 비워 둔다 — 캐리포워드로 되살아나지 않는다.
                ScenarioCasePlacement(c2, emptyList()),
            )
        )!!

        assertThat(saved.items.map { it.position }).containsExactly(0, 1)
        assertThat(saved.items[1].steps).isEmpty()

        // 조회 왕복: 종류·판정 플래그·의도·힌트가 그대로 보존된다.
        val fetched = compositionService.getCases(scenarioId, userId)!!
        val first = fetched.items[0].steps
        assertThat(first.map { it.kind }).containsExactly("setup", "guide", "verify")
        assertThat(first[0].assert).isFalse()
        assertThat(first[0].hint).isEqualTo("Esc")
        assertThat(first[1].input).isEqualTo("keyboard")
        assertThat(first[2].observe).isEqualTo("홈 HUD")
        assertThat(fetched.items[1].steps).isEmpty()
    }

    @Test
    fun `items 경로는 권위라 다시 쓰면 이전 steps를 대체하고 빈 목록은 비운다`(): Unit = runBlocking {
        val (projectId, userId) = projectWithMember()
        val c1 = case(projectId, userId, "A")
        val scenarioId = scenario(projectId)

        compositionService.setCasesWithSteps(
            scenarioId, userId,
            listOf(ScenarioCasePlacement(c1, listOf(ScenarioStepDto(kind = "guide", intent = "첫 저작"))))
        )
        // 같은 자리를 빈 steps로 다시 쓰면, 캐리포워드가 아니라 비운다.
        val recleared = compositionService.setCasesWithSteps(
            scenarioId, userId, listOf(ScenarioCasePlacement(c1, emptyList()))
        )!!

        assertThat(recleared.items.single().steps).isEmpty()
    }

    @Test
    fun `순서만 바꾸는 기존 경로는 저작한 steps를 자리째 캐리포워드한다`(): Unit = runBlocking {
        val (projectId, userId) = projectWithMember()
        val c1 = case(projectId, userId, "A")
        val c2 = case(projectId, userId, "B")
        val scenarioId = scenario(projectId)

        // 0번 자리에 c1을 저작 Step과 함께 저장.
        compositionService.setCasesWithSteps(
            scenarioId, userId,
            listOf(ScenarioCasePlacement(c1, listOf(ScenarioStepDto(kind = "guide", intent = "지켜질 스텝"))))
        )

        // 이후 caseIds만으로 저장(steps 미지정) — 같은 자리(0)의 c1 steps는 캐리포워드된다.
        val afterReorder = compositionService.setCases(scenarioId, userId, listOf(c1, c2))!!
        assertThat(afterReorder.items[0].steps.map { it.intent }).containsExactly("지켜질 스텝")
        assertThat(afterReorder.items[1].steps).isEmpty()

        // c1을 다른 자리로 옮기면(자리 사라짐) steps는 폐기된다.
        val moved = compositionService.setCases(scenarioId, userId, listOf(c2, c1))!!
        assertThat(moved.items.first { it.case.id == c1.toString() }.steps).isEmpty()
    }

    // --- seeding ---------------------------------------------------------------

    private suspend fun projectWithMember(): Pair<Long, Long> {
        val now = Instant.now()
        val userId = appUserRepository.save(
            AppUserEntity(displayName = "steps-user", createdAt = now, updatedAt = now)
        ).id!!
        val projectId = projectRepository.save(
            ProjectEntity(name = "steps-project", genre = "ACTION", createdAt = now, updatedAt = now)
        ).id!!
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = projectId, appUserId = userId, role = "OWNER", createdAt = now)
        )
        return projectId to userId
    }

    private suspend fun case(projectId: Long, userId: Long, title: String): Long =
        testCaseService.create(
            projectId, userId, TestCaseCreateRequest(category = "CONTROL", title = title, expected = "$title 결과")
        )!!.id.toLong()

    private suspend fun scenario(projectId: Long): Long =
        testScenarioRepository.save(TestScenarioEntity(projectId = projectId, payload = Json.of("{}"))).id!!
}
