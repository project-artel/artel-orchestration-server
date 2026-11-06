package kr.artel.orchestration.testrun

import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testrun.service.TestRunChatService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP 를 쓰는 에이전트가 시나리오를 직접 짤 때 읽는 게임 정보. 콘솔의 저작 Agent 가 받는 것과 같은 TC 목록이어야 한다.
 */
@ActiveProfiles("test")
@SpringBootTest
class AuthoringContextIntegrationTest {

    @Autowired
    private lateinit var oauthUserService: OAuthUserService

    @Autowired
    private lateinit var chatService: TestRunChatService

    @Autowired
    private lateinit var runRepository: TestRunRepository




    @Autowired
    private lateinit var testCaseRepository: TestCaseRepository

    @Autowired
    private lateinit var projectRepository: ProjectRepository

    @Autowired
    private lateinit var projectMemberRepository: ProjectMemberRepository

    private val seq = AtomicLong(9300)

    private data class Fixture(val appUserId: Long, val projectId: Long, val runId: Long, val caseIds: List<Long>)

    private suspend fun fixture(): Fixture {
        val n = seq.incrementAndGet()
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github", providerUserId = "submit-$n", login = "submit-$n",
                displayName = "Submit", avatarUrl = null, email = null,
            )
        )
        val appUserId = user.userId.toLong()
        val now = Instant.now()
        val project = projectRepository.save(ProjectEntity(name = "submit-project", genre = "ACTION", createdAt = now, updatedAt = now))
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = project.id!!, appUserId = appUserId, role = "OWNER", createdAt = now)
        )
        val cases = listOf(
            TestCaseEntity(projectId = project.id, scene = "타이틀 화면", step = "게임 시작 버튼을 누른다", expectedValue = "캐릭터 선택 창이 열린다"),
            TestCaseEntity(projectId = project.id, scene = "캐릭터 선택 창", step = "Start 버튼을 누른다", expectedValue = "전투 필드로 넘어간다"),
        ).map { testCaseRepository.save(it).id!! }
        val runId = runRepository.save(TestRunEntity(projectId = project.id, name = "런")).id!!
        return Fixture(appUserId, project.id, runId, cases)
    }



    @Test
    fun `시나리오를 짤 재료는 콘솔의 저작과 같은 TC 목록이다`(): Unit = runBlocking {
        val f = fixture()

        val context = chatService.authoringContext(f.appUserId, f.runId)

        assertThat(context.cases.map { it.id }).containsExactlyInAnyOrderElementsOf(f.caseIds)
        assertThat(context.cases.map { it.step }).contains("게임 시작 버튼을 누른다", "Start 버튼을 누른다")
    }

}
