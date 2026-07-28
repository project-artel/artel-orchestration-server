package kr.artel.orchestration.qa

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.FakeDocumentStorage
import kr.artel.orchestration.project.config.StorageProperties
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * QA 캡처 업로드 서명 경로.
 *
 * 이 엔드포인트는 로그인 세션 없이 게임이 직접 부른다. 그래서 권한 판정은 JWT가 아니라
 * "그 인스턴스가 지금 QA 실행 중인가" 하나뿐이고, 그 판정이 이 테스트의 핵심이다.
 *
 * payload(JSONB)를 검증하므로 실제 PostgreSQL을 쓴다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QaCaptureIntegrationTest {

    @TestConfiguration
    class FakeStorageConfig {
        @Bean
        @Primary
        fun fakeDocumentStorage(): DocumentStorage = FakeDocumentStorage()
    }

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var storageProperties: StorageProperties
    @Autowired private lateinit var qaLogRepository: QaLogRepository
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService

    /** qa_try는 game_instance/test_scenario를 하드 FK로 참조하므로 테스트 후에도 반드시 비운다. */
    @BeforeEach
    @AfterEach
    fun clean() {
        qaLogRepository.deleteAll().block(TIMEOUT)
        qaTryRepository.deleteAll().block(TIMEOUT)
        gameInstanceRepository.deleteAll().block(TIMEOUT)
        testScenarioRepository.deleteAll().block(TIMEOUT)
        projectMemberRepository.deleteAll().block(TIMEOUT)
        projectRepository.deleteAll().block(TIMEOUT)
        identityRepository.deleteAll().block(TIMEOUT)
        appUserRepository.deleteAll().block(TIMEOUT)
    }

    @Test
    fun `issues upload and download urls for a game instance with a running QA`() {
        val seeded = seedRunningQaTry()

        val ticket = issueTicket(seeded.instanceKey, "image/jpeg", 120_000)

        assertThat(ticket["captureId"].asText()).isNotBlank()
        assertThat(ticket["uploadUrl"].asText()).contains("qa-captures/${seeded.qaTryId}/")
        assertThat(ticket["uploadUrl"].asText()).endsWith(".jpg?signature=test")
        assertThat(ticket["requiredHeaders"]["Content-Type"].asText()).isEqualTo("image/jpeg")
        assertThat(ticket["downloadUrl"].asText()).contains("qa-captures/${seeded.qaTryId}/")
    }

    /**
     * 런 데드라인(Agent RUN_DEADLINE_SECONDS=600) 안에 만료되면, 런 초반 캡처를 후반에 열지
     * 못한다. 그때의 증상은 "이미지를 받지 못했다"라는 모호한 실패라 원인을 찾기 어렵다.
     */
    @Test
    fun `keeps the download url alive past the QA run deadline`() {
        val seeded = seedRunningQaTry()

        val ticket = issueTicket(seeded.instanceKey, "image/png", 40_000)

        val issuedAt = Instant.parse(ticket["uploadExpiresAt"].asText())
            .minus(storageProperties.uploadUrlTtl)
        val downloadExpiresAt = Instant.parse(ticket["downloadExpiresAt"].asText())
        assertThat(Duration.between(issuedAt, downloadExpiresAt))
            .isGreaterThan(StorageProperties.MIN_CAPTURE_DOWNLOAD_URL_TTL)
    }

    /** 떠도는 게임이 스토리지에 쓰지 못하게 한다. 요청은 옳으니 404가 아니라 409다. */
    @Test
    fun `refuses a game instance with no running QA`() {
        val seeded = seedRunningQaTry(status = "COMPLETED")

        val error = ticketError(seeded.instanceKey, "image/jpeg", 120_000)

        assertThat(error.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    /**
     * 이 경로는 엔드유저 JWT로 막히지 않는다. 인스턴스를 순번 id로 지목하게 두면 남의 실행
     * 중인 QA 프리픽스에 쓰는 서명을 훑어서 받아낼 수 있으므로, 등록과 같은 자격증명을 쓴다.
     */
    @Test
    fun `refuses an instance key it does not know`() {
        seedRunningQaTry()

        val error = ticketError("0123456789abcdef0123", "image/jpeg", 120_000)

        assertThat(error.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `refuses a content type that is not a screen capture`() {
        val seeded = seedRunningQaTry()

        val error = ticketError(seeded.instanceKey, "application/pdf", 120_000)

        assertThat(error.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(error.responseBodyAsString).contains("application/pdf")
    }

    @Test
    fun `refuses a capture larger than the cap`() {
        val seeded = seedRunningQaTry()

        val error = ticketError(
            seeded.instanceKey,
            "image/jpeg",
            storageProperties.maxCaptureBytes + 1
        )

        assertThat(error.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    /**
     * 캡처 증거는 남되 payload는 작아야 한다. 이 행은 SSE로도 발행되므로, 여기에 바이트가
     * 실리면 캡처 한 장마다 DB와 스트림이 함께 부푼다.
     */
    @Test
    fun `records a small SCREENSHOT log row that points at the image`() {
        val seeded = seedRunningQaTry()

        val ticket = issueTicket(seeded.instanceKey, "image/jpeg", 120_000, targetId = 7)

        val logs = qaLogRepository.findAll().collectList().block(TIMEOUT)!!
        assertThat(logs).hasSize(1)
        val log = logs.single()
        assertThat(log.type).isEqualTo("SCREENSHOT")
        assertThat(log.messageId).isEqualTo(ticket["captureId"].asText())

        val payload = objectMapper.readTree(log.payload.asString())
        assertThat(payload["targetId"].asInt()).isEqualTo(7)
        assertThat(payload["objectKey"].asText()).startsWith("qa-captures/${seeded.qaTryId}/")
        assertThat(payload["url"].asText()).isEqualTo(ticket["downloadUrl"].asText())
        assertThat(log.payload.asString()!!.toByteArray(StandardCharsets.UTF_8).size)
            .isLessThan(2048)
    }

    // --- helpers ---

    private data class SeededRun(val instanceKey: String, val qaTryId: Long)

    private fun issueTicket(
        instanceKey: String,
        contentType: String,
        contentLength: Long,
        targetId: Int? = null
    ): JsonNode =
        client().post()
            .uri("/api/sdk/qa-captures/tickets")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body(instanceKey, contentType, contentLength, targetId))
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .block(TIMEOUT)!!

    private fun ticketError(
        instanceKey: String,
        contentType: String,
        contentLength: Long
    ): WebClientResponseException =
        runCatching { issueTicket(instanceKey, contentType, contentLength) }
            .exceptionOrNull() as WebClientResponseException

    private fun body(
        instanceKey: String,
        contentType: String,
        contentLength: Long,
        targetId: Int?
    ): Map<String, Any> = buildMap {
        put("instanceKey", instanceKey)
        put("contentType", contentType)
        put("contentLength", contentLength)
        targetId?.let { put("targetId", it) }
    }

    private fun client(): WebClient = WebClient.create("http://localhost:$port")

    private fun seedRunningQaTry(status: String = "RUNNING"): SeededRun {
        val owner = signIn()
        val ownerId = owner.userId.toLong()
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "capture-project", genre = "ACTION", createdAt = now, updatedAt = now)
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
        val instanceKey = UUID.randomUUID().toString().replace("-", "").take(20)
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = project.id!!,
                name = "instance",
                platform = "UNITY",
                instanceKey = instanceKey,
                createdAt = now,
                updatedAt = now
            )
        ).block(TIMEOUT)!!
        val qaTry = qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenario.id!!,
                gameInstanceId = instance.id!!,
                startedBy = ownerId,
                status = status,
                startedAt = now,
                completedAt = if (status == "RUNNING") null else now
            )
        ).block(TIMEOUT)!!
        return SeededRun(instanceKey = instanceKey, qaTryId = qaTry.id!!)
    }

    private fun signIn(): AuthenticatedUser =
        oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = "42",
                login = "octocat",
                displayName = "octocat",
                avatarUrl = null,
                email = "octocat@example.com"
            )
        ).block(TIMEOUT)!!

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
