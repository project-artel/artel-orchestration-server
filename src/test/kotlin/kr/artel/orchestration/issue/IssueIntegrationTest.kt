package kr.artel.orchestration.issue

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.issue.repository.IssueRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaAgentEnvelope
import kr.artel.orchestration.qa.service.QaAgentInboundRouter
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * issue 도메인 통합 테스트: 저장은 QA 인바운드 라우터([QaAgentInboundRouter])로, 조회는 엔드유저
 * GET 엔드포인트(JWT + 프로젝트 멤버)로 검증한다. payload(JSONB) 때문에 실제 PostgreSQL을 쓴다.
 *
 * 검증: ISSUE 프레임 저장(severity/title/detail), 재전송 멱등, 잘못된 severity는 저장 안 함,
 * 멤버 조회 성공/비멤버 404.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IssueIntegrationTest {

    @LocalServerPort private val port: Int = 0

    @Autowired private lateinit var inboundRouter: QaAgentInboundRouter
    @Autowired private lateinit var issueRepository: IssueRepository
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper

    /**
     * 리액티브 트랜잭션은 테스트 롤백이 안 되고 실 DB를 공유하므로 FK 순서대로 직접 비운다.
     * 테스트 후에도 반드시 비운다 — qa_try는 game_instance/test_scenario를 하드 FK로 참조하므로,
     * 남겨두면 이 도메인을 모르는 다른 테스트의 game_instance/project 삭제가 FK로 깨진다.
     */
    @BeforeEach
    @AfterEach
    fun clean() {
        issueRepository.deleteAll().block()
        qaTryRepository.deleteAll().block()
        gameInstanceRepository.deleteAll().block()
        testScenarioRepository.deleteAll().block()
        projectMemberRepository.deleteAll().block()
        projectRepository.deleteAll().block()
        identityRepository.deleteAll().block()
        appUserRepository.deleteAll().block()
    }

    @Test
    fun `persists an agent-reported issue with severity, title and full payload as detail`() {
        val owner = signIn("42", "octocat")
        val qaTryId = seedRunningQaTry(owner)

        deliver(
            qaTryId,
            messageId = UUID.randomUUID().toString(),
            severity = "MAJOR",
            title = "Save button does nothing on the pause menu",
            extra = """"detail":"Clicking Save shows no feedback and no file is written","step":3"""
        )

        val issues = issueRepository.findByQaTryId(qaTryId, 50).collectList().block(TIMEOUT)!!
        assertThat(issues).hasSize(1)
        val issue = issues.single()
        assertThat(issue.severity).isEqualTo("MAJOR")
        assertThat(issue.title).isEqualTo("Save button does nothing on the pause menu")
        val detail = objectMapper.readTree(issue.detail.asString())
        assertThat(detail["step"].asInt()).isEqualTo(3)
        assertThat(detail["detail"].asText()).contains("no file is written")
    }

    @Test
    fun `is idempotent on message id so a re-delivered frame does not duplicate`() {
        val owner = signIn("42", "octocat")
        val qaTryId = seedRunningQaTry(owner)
        val messageId = UUID.randomUUID().toString()

        deliver(qaTryId, messageId, "CRITICAL", "Crash on level load")
        deliver(qaTryId, messageId, "CRITICAL", "Crash on level load")

        assertThat(issueRepository.findByQaTryId(qaTryId, 50).collectList().block(TIMEOUT)).hasSize(1)
    }

    @Test
    fun `drops a frame whose severity is not on the ladder without persisting`() {
        val owner = signIn("42", "octocat")
        val qaTryId = seedRunningQaTry(owner)

        deliver(qaTryId, UUID.randomUUID().toString(), severity = "SEVERE", title = "Unknown severity")

        assertThat(issueRepository.findByQaTryId(qaTryId, 50).collectList().block(TIMEOUT)).isEmpty()
    }

    @Test
    fun `lists issues for a member and hides them from a non-member`() {
        val owner = signIn("42", "octocat")
        val stranger = signIn("99", "hubot")
        val qaTryId = seedRunningQaTry(owner)
        deliver(qaTryId, UUID.randomUUID().toString(), "MINOR", "Typo on the title screen")

        val listed = get(jwtService.issue(owner), "/api/issues?qaTryId=$qaTryId")
        assertThat(listed).hasSize(1)
        assertThat(listed[0]["severity"].asText()).isEqualTo("MINOR")
        assertThat(listed[0]["title"].asText()).isEqualTo("Typo on the title screen")

        val status = statusOf { get(jwtService.issue(stranger), "/api/issues?qaTryId=$qaTryId") }
        assertThat(status).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `returns an empty list for a member whose run has no issues yet`() {
        val owner = signIn("42", "octocat")
        val qaTryId = seedRunningQaTry(owner)

        // 접근 가능하지만 이슈가 없는 실행은 404가 아니라 200 [] 이어야 한다
        // (비멤버의 404와 구분되는, listByQaTry가 Mono<List>인 이유).
        val listed = get(jwtService.issue(owner), "/api/issues?qaTryId=$qaTryId")
        assertThat(listed.isArray).isTrue()
        assertThat(listed).isEmpty()
    }

    // --- helpers ---

    private fun deliver(
        qaTryId: Long,
        messageId: String,
        severity: String,
        title: String,
        extra: String? = null
    ) {
        val payload = buildString {
            append("""{"severity":"$severity","title":${objectMapper.writeValueAsString(title)}""")
            if (extra != null) append(",$extra")
            append("}")
        }
        inboundRouter.handle(
            QaAgentEnvelope(
                messageId = messageId,
                type = "ISSUE",
                qaTryId = qaTryId.toString(),
                correlationId = UUID.randomUUID().toString(),
                timestamp = Instant.now(),
                payload = objectMapper.readTree(payload)
            )
        ).block(TIMEOUT)
    }

    private fun seedRunningQaTry(owner: AuthenticatedUser): Long {
        val ownerId = owner.userId.toLong()
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "issue-project", genre = "ACTION", createdAt = now, updatedAt = now)
        ).block(TIMEOUT)!!
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = project.id!!,
                appUserId = ownerId,
                role = ProjectRole.OWNER.name,
                createdAt = now
            )
        ).block(TIMEOUT)
        val scenario = testScenarioRepository.save(
            TestScenarioEntity(projectId = project.id!!, payload = Json.of("{}"))
        ).block(TIMEOUT)!!
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = project.id!!,
                name = "instance",
                platform = "UNITY",
                instanceKey = UUID.randomUUID().toString().replace("-", "").take(20),
                createdAt = now,
                updatedAt = now
            )
        ).block(TIMEOUT)!!
        val qaTry = qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenario.id!!,
                gameInstanceId = instance.id!!,
                startedBy = ownerId,
                status = "RUNNING",
                startedAt = now
            )
        ).block(TIMEOUT)!!
        return qaTry.id!!
    }

    private fun signIn(providerUserId: String, login: String): AuthenticatedUser =
        oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = login,
                displayName = login,
                avatarUrl = null,
                email = "$login@example.com"
            )
        ).block(TIMEOUT)!!

    private fun get(token: String, uri: String): JsonNode = objectMapper.readTree(
        WebClient.create("http://localhost:$port").get().uri(uri)
            .cookie("artel_access_token", token)
            .retrieve().bodyToMono(String::class.java).block(TIMEOUT)
    )

    private fun statusOf(call: () -> Any?): HttpStatus =
        try {
            call()
            HttpStatus.OK
        } catch (error: WebClientResponseException) {
            HttpStatus.valueOf(error.statusCode.value())
        }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
