package kr.artel.orchestration.testcase

import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.support.testAppUser
import kr.artel.orchestration.testcase.controller.ReviewNoticeController
import kr.artel.orchestration.testcase.dto.ReviewNotice
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReviewNoticeIntegrationTest {
    @Autowired private lateinit var controller: ReviewNoticeController
    @Autowired private lateinit var users: AppUserRepository
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var members: ProjectMemberRepository
    @Autowired private lateinit var cases: TestCaseRepository
    @Autowired private lateinit var scenarios: TestScenarioRepository

    private suspend fun user(): Long = users.save(testAppUser("review-notice", Instant.now())).id!!

    private suspend fun project(name: String, vararg memberIds: Long): Long {
        val now = Instant.now()
        val id = projects.save(ProjectEntity(name = name, genre = "ACTION", createdAt = now, updatedAt = now)).id!!
        memberIds.forEach { members.save(ProjectMemberEntity(projectId = id, appUserId = it, role = "MEMBER", createdAt = now)) }
        return id
    }

    private suspend fun case(projectId: Long, requestedBy: Long?, status: String = "DRAFT") {
        cases.save(
            TestCaseEntity(
                projectId = projectId, scene = "광장", step = "문을 연다", expectedValue = "문이 열린다",
                reviewStatus = status, reviewRequestedBy = requestedBy,
            ),
        )
    }

    private suspend fun scenario(projectId: Long, requestedBy: Long?, status: String = "DRAFT") {
        scenarios.save(TestScenarioEntity(projectId = projectId, reviewStatus = status, reviewRequestedBy = requestedBy))
    }

    @Test
    fun `내가 요청한 대기 건을 프로젝트별로 Test Case 와 시나리오로 나눠 센다`(): Unit = runBlocking {
        val me = user()
        val other = user()
        val shared = project("공용", me, other)
        val scenariosOnly = project("시나리오만", me)

        case(shared, me)
        case(shared, me)
        scenario(shared, me)
        scenario(scenariosOnly, me)
        // 남이 요청한 건, 이미 승인된 건, 요청자가 없는 건은 내 알림이 아니다.
        case(shared, other)
        scenario(shared, other)
        case(shared, me, status = "READY")
        scenario(shared, me, status = "READY")
        case(shared, null)

        assertThat(controller.listReviewNotices(me).items).containsExactlyInAnyOrder(
            ReviewNotice(shared.toString(), "공용", 2, 1),
            ReviewNotice(scenariosOnly.toString(), "시나리오만", 0, 1),
        )
        assertThat(controller.listReviewNotices(other).items).containsExactly(
            ReviewNotice(shared.toString(), "공용", 1, 1),
        )
    }

    @Test
    fun `대기 건이 없거나 나갔거나 삭제된 프로젝트는 나오지 않는다`(): Unit = runBlocking {
        val me = user()
        project("대기 없음", me)

        // review-queue 가 404 라 열 수 없는 프로젝트다.
        val left = project("나간 프로젝트", user())
        case(left, me)

        val deleted = project("삭제된 프로젝트", me)
        case(deleted, me)
        projects.save(projects.findById(deleted)!!.copy(deletedAt = Instant.now()))

        assertThat(controller.listReviewNotices(me).items).isEmpty()
    }
}
