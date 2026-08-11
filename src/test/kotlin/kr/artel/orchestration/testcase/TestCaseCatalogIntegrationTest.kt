package kr.artel.orchestration.testcase

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.entity.AppUserEntity
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testcase.dto.TestCaseCreateRequest
import kr.artel.orchestration.testcase.service.TestCaseService
import kr.artel.orchestration.testscenario.dto.AgentSessionOpenRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * TestCase 전량 카탈로그(ARTEL-318) 통합 테스트. 서비스 레이어로 검증한다(HTTP/인증 우회).
 *
 * 이 카탈로그가 지키는 약속은 세 가지이고, 셋 다 깨져도 코드는 잘 도는 것처럼 보인다:
 * 1. **전량이다** — 걸러지면 "존재를 몰라서 빠뜨리는" 실패가 그대로 남는다.
 * 2. **오름차순이다** — 순서가 흔들리면 Agent 프롬프트 캐시가 매 턴 깨진다(비용만 오르고 결과는 같다).
 * 3. **본문이 없다** — 필드가 새어 들어오면 세션당 부피가 조용히 몇 배가 된다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TestCaseCatalogIntegrationTest {

    @Autowired private lateinit var testCaseService: TestCaseService
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var objectMapper: ObjectMapper

    private suspend fun newUser(): Long {
        val now = Instant.now()
        return appUserRepository.save(AppUserEntity(displayName = "catalog-user", createdAt = now, updatedAt = now))
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
        testCaseService.create(
            projectId,
            userId,
            TestCaseCreateRequest(
                category = "로그인",
                title = "케이스 $n",
                precondition = "사전조건 $n",
                expected = "기대결과 $n",
            )
        )!!.id.toLong()

    @Test
    fun `카탈로그는 프로젝트 케이스 전량을 id 오름차순으로 낸다`(): Unit = runBlocking {
        val (projectId, userId) = newProjectWithMember()
        val createdIds = (1..3).map { createCase(projectId, userId, it) }

        val catalog = testCaseService.getTestCaseCatalog(projectId, userId)

        assertThat(catalog.items.map { it.id }).isEqualTo(createdIds.sorted())

        // 오름차순은 취향이 아니라 캐시 계약이다. 같은 데이터를 최신순으로 내는 list()와 대비해
        // 두 조회가 서로 다른 정렬을 의도적으로 쓴다는 사실을 못박는다.
        assertThat(testCaseService.list(projectId, userId, null, null).items.map { it.id.toLong() })
            .isEqualTo(createdIds.sortedDescending())
    }

    @Test
    fun `카탈로그 한 줄에는 본문이 없고 Agent 계약 이름으로 직렬화된다`(): Unit = runBlocking {
        val (projectId, userId) = newProjectWithMember()
        createCase(projectId, userId, 1)

        val entry = testCaseService.getTestCaseCatalog(projectId, userId).items.single()
        val fields = objectMapper.readTree(objectMapper.writeValueAsString(entry))

        assertThat(fields.fieldNames().asSequence().toSet())
            .containsExactlyInAnyOrder("id", "category", "title", "verification_status")
        // Agent가 이 값을 그대로 스텝의 case_id로 돌려주므로 숫자여야 한다(FE 응답과 달리 문자열이 아니다).
        assertThat(fields["id"].isNumber).isTrue()
        assertThat(fields["verification_status"].asText()).isEqualTo("DRAFT")
    }

    @Test
    fun `비참여자에게는 빈 카탈로그를 준다`(): Unit = runBlocking {
        val (projectId, memberId) = newProjectWithMember()
        createCase(projectId, memberId, 1)
        val outsiderId = newUser()

        assertThat(testCaseService.getTestCaseCatalog(projectId, outsiderId).items).isEmpty()
    }

    @Test
    fun `세션 오픈 본문은 카탈로그를 case_catalog 배열로 싣는다`(): Unit = runBlocking {
        val (projectId, userId) = newProjectWithMember()
        createCase(projectId, userId, 1)
        val items = testCaseService.getTestCaseCatalog(projectId, userId).items

        val body = objectMapper.readTree(
            objectMapper.writeValueAsString(
                AgentSessionOpenRequest(
                    userInput = "로그인 흐름 짜줘",
                    caseCatalog = items,
                    model = "openai/gpt-5.6-luna",
                    locale = "ko",
                    projectId = projectId,
                    runId = 1L,
                )
            )
        )

        assertThat(body["case_catalog"].isArray).isTrue()
        assertThat(body["case_catalog"].size()).isEqualTo(1)
        assertThat(body["case_catalog"][0]["id"].asLong()).isEqualTo(items.single().id)
    }

    @Test
    fun `카탈로그를 넘기지 않으면 빈 배열로 나가 기존 동작을 유지한다`() {
        // 되돌리기 경로다. Agent는 case_catalog가 비면 기존 검색 방식으로 동작하므로,
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

        assertThat(body["case_catalog"].isArray).isTrue()
        assertThat(body["case_catalog"]).isEmpty()
    }
}
