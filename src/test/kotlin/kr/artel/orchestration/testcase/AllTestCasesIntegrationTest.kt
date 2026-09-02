package kr.artel.orchestration.testcase

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.support.testAppUser
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.testcase.dto.CaseGuard
import kr.artel.orchestration.testcase.dto.TestCaseCreateRequest
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.service.TestCaseService
import kr.artel.orchestration.testscenario.dto.AgentSessionOpenRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * TestCase 전량 목록(ARTEL-318) 통합 테스트. 서비스 레이어로 검증한다(HTTP/인증 우회).
 *
 * 이 목록이 지키는 약속은 세 가지이고, 셋 다 깨져도 코드는 잘 도는 것처럼 보인다:
 * 1. **전량이다** — 걸러지면 "존재를 몰라서 빠뜨리는" 실패가 그대로 남는다.
 * 2. **오름차순이다** — 순서가 흔들리면 Agent 프롬프트 캐시가 매 턴 깨진다(비용만 오르고 결과는 같다).
 * 3. **스텝을 쓸 수 있을 만큼 담는다** — 사전조건·기대결과가 빠지면 Agent가 고른 뒤 다시 가져와야 하고,
 *    그 왕복 횟수가 곧 새로운 상한이 된다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AllTestCasesIntegrationTest {

    @Autowired private lateinit var testCaseService: TestCaseService
    @Autowired private lateinit var testCaseRepository: TestCaseRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var objectMapper: ObjectMapper

    private suspend fun newUser(): Long {
        val now = Instant.now()
        return appUserRepository.save(testAppUser("catalog-user", now))
            .id!!
    }

    private suspend fun newProjectWithMember(): Pair<Long, Long> {
        val now = Instant.now()
        val userId = newUser()
        val projectId = projectRepository.save(
            ProjectEntity(name = "catalog-project", genre = "ACTION", createdAt = now, updatedAt = now)
        ).id!!
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = projectId, appUserId = userId, role = "MEMBER", createdAt = now)
        )
        return projectId to userId
    }

    private suspend fun createCase(projectId: Long, userId: Long, n: Int): Long =
        testCaseService.createTestCase(
            projectId,
            userId,
            TestCaseCreateRequest(
                scene = "로그인",
                step = "케이스 $n",
                precondition = "사전조건 $n",
                expectedValue = "기대결과 $n",
            )
        )!!.id.toLong()

    @Test
    fun `전량 목록은 프로젝트 케이스 전부를 id 오름차순으로 낸다`(): Unit = runBlocking {
        val (projectId, userId) = newProjectWithMember()
        val createdIds = (1..3).map { createCase(projectId, userId, it) }

        val catalog = testCaseService.getAllTestCases(projectId, userId)

        assertThat(catalog.items.map { it.id }).isEqualTo(createdIds.sorted())

        // 오름차순은 취향이 아니라 캐시 계약이다. 같은 데이터를 최신순으로 내는 list()와 대비해
        // 두 조회가 서로 다른 정렬을 의도적으로 쓴다는 사실을 못박는다.
        assertThat(testCaseService.listTestCases(projectId, userId).items.map { it.id.toLong() })
            .isEqualTo(createdIds.sortedDescending())
    }

    @Test
    fun `목록 한 줄은 스텝 작성에 필요한 만큼 담고 Agent 계약 이름으로 직렬화된다`(): Unit = runBlocking {
        val (projectId, userId) = newProjectWithMember()
        createCase(projectId, userId, 1)

        val entry = testCaseService.getAllTestCases(projectId, userId).items.single()
        val fields = objectMapper.readTree(objectMapper.writeValueAsString(entry))

        // 본문 두 필드가 빠지면 Agent가 고른 케이스로 스텝을 쓸 수 없어 왕복이 생긴다.
        // 반대로 여기 없는 컬럼(타임스탬프 등)이 새어 들어오면 세션당 부피만 는다.
        assertThat(fields.fieldNames().asSequence().toSet()).containsExactlyInAnyOrder(
            "id", "scene", "step", "precondition", "expected_value", "verification_status"
        )
        assertThat(fields["precondition"].asText()).isEqualTo("사전조건 1")
        assertThat(fields["expected_value"].asText()).isEqualTo("기대결과 1")
        // Agent가 이 값을 그대로 스텝의 case_id로 돌려주므로 숫자여야 한다(FE 응답과 달리 문자열이 아니다).
        assertThat(fields["id"].isNumber).isTrue()
        assertThat(fields["verification_status"].asText()).isEqualTo("DRAFT")
    }

    @Test
    fun `비참여자에게는 빈 목록을 준다`(): Unit = runBlocking {
        val (projectId, memberId) = newProjectWithMember()
        createCase(projectId, memberId, 1)
        val outsiderId = newUser()

        assertThat(testCaseService.getAllTestCases(projectId, outsiderId).items).isEmpty()
    }

    @Test
    fun `세션 오픈 본문은 전량 목록을 test_case_list 배열로 싣는다`(): Unit = runBlocking {
        val (projectId, userId) = newProjectWithMember()
        createCase(projectId, userId, 1)
        val items = testCaseService.getAuthoringCases(projectId, userId)

        val body = objectMapper.readTree(
            objectMapper.writeValueAsString(
                AgentSessionOpenRequest(
                    userInput = "로그인 흐름 짜줘",
                    testCaseList = items,
                    model = "openai/gpt-5.6-luna",
                    locale = "ko",
                    projectId = projectId,
                    runId = 1L,
                )
            )
        )

        assertThat(body["test_case_list"].isArray).isTrue()
        assertThat(body["test_case_list"].size()).isEqualTo(1)
        assertThat(body["test_case_list"][0]["id"].asLong()).isEqualTo(items.single().id)
    }

    @Test
    fun `저작 목록은 사전조건을 파싱해 정규화한 상태와 함께 나간다`(): Unit = runBlocking {
        // Agent 와 오케가 같은 문장을 각자 해석하면 어긋나고, 그 어긋남은 조용하다. 읽는 쪽을
        // 하나로 두고 그 결과를 실어 보낸다.
        val (projectId, userId) = newProjectWithMember()
        testCaseRepository.save(
            TestCaseEntity(
                projectId = projectId, scene = "Map_scene",
                step = "오른쪽으로 이동", expectedValue = "이동한다",
                precondition = "Map_scene 화면인 상태 / (MapMove.StagePosition >= 1 그리고 MapMove.position == 0)",
                metadata = Json.of("""{"source":{"state_after":"MapMove.position=1"}}"""),
            )
        )

        val case = testCaseService.getAuthoringCases(projectId, userId)
            .single { it.scene == "Map_scene" }

        assertThat(case.stateBefore).containsExactly(
            CaseGuard("StagePosition", ">=", "1"),
            CaseGuard("position", "==", "0"),
        )
        // 다음 케이스의 출발 상태가 이 값이다. 사전조건만 읽으면 0 인 채로 남는다.
        assertThat(case.stateAfter).containsEntry("position", "1")
        // 원문도 그대로 둔다 — 정규화가 놓치는 서술이 있고 그건 사람 말로만 있다.
        assertThat(case.precondition).contains("화면인 상태")
    }

    @Test
    fun `목록을 넘기지 않으면 빈 배열로 나가 기존 동작을 유지한다`() {
        // 되돌리기 경로다. Agent는 test_case_list가 비면 기존 검색 방식으로 동작하므로,
        // 이 기본값이 사라지면 롤백이 양쪽 동시 배포 없이는 불가능해진다.
        val body = objectMapper.readTree(
            objectMapper.writeValueAsString(
                AgentSessionOpenRequest(
                    userInput = "안녕",
                    model = "openai/gpt-5.6-luna",
                    locale = "ko",
                    projectId = 1L,
                    runId = 1L,
                )
            )
        )

        assertThat(body["test_case_list"].isArray).isTrue()
        assertThat(body["test_case_list"]).isEmpty()
    }
}
