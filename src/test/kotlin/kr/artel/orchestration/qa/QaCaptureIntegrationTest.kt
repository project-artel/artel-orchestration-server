package kr.artel.orchestration.qa

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.JwtService
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
import org.springframework.http.HttpHeaders
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
 * 이 엔드포인트는 게임이 SDK 토큰으로 직접 부른다. 권한 판정이 두 겹이라 그 둘이 이 테스트의
 * 핵심이다. 토큰의 사용자가 그 인스턴스의 프로젝트 참여자인가, 그리고 그 인스턴스가 지금 QA
 * 실행 중인가.
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
    @Autowired private lateinit var jwtService: JwtService

    /** qa_try는 game_instance/test_scenario를 하드 FK로 참조하므로 테스트 후에도 반드시 비운다. */
    @BeforeEach
    @AfterEach
    fun clean(): Unit = runBlocking {
        qaLogRepository.deleteAll()
        qaTryRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testScenarioRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `issues upload and download urls for a game instance with a running QA`(): Unit = runBlocking {
        val seeded = seedRunningQaTry()

        val ticket = issueTicket(seeded.sdkToken, seeded.instanceId, "image/jpeg", 120_000)

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
    fun `keeps the download url alive past the QA run deadline`(): Unit = runBlocking {
        val seeded = seedRunningQaTry()

        val ticket = issueTicket(seeded.sdkToken, seeded.instanceId, "image/png", 40_000)

        val issuedAt = Instant.parse(ticket["uploadExpiresAt"].asText())
            .minus(storageProperties.uploadUrlTtl)
        val downloadExpiresAt = Instant.parse(ticket["downloadExpiresAt"].asText())
        assertThat(Duration.between(issuedAt, downloadExpiresAt))
            .isGreaterThan(StorageProperties.MIN_CAPTURE_DOWNLOAD_URL_TTL)
    }

    /** 떠도는 게임이 스토리지에 쓰지 못하게 한다. 요청은 옳으니 404가 아니라 409다. */
    @Test
    fun `refuses a game instance with no running QA`(): Unit = runBlocking {
        val seeded = seedRunningQaTry(status = "COMPLETED")

        val error = ticketError(seeded.sdkToken, seeded.instanceId, "image/jpeg", 120_000)

        assertThat(error.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    /**
     * 순번 id를 훑어 남의 실행 중인 QA 프리픽스에 쓰는 서명을 받아낼 수 없어야 한다. 토큰의
     * 사용자가 그 인스턴스의 프로젝트 참여자인지 확인하고, 아니면 없는 것과 같은 응답을 준다.
     */
    @Test
    fun `refuses an instance the caller cannot access`(): Unit = runBlocking {
        val seeded = seedRunningQaTry()
        val stranger = signIn(providerUserId = "77", login = "stranger")
        val strangerToken = jwtService.issueSdkToken(stranger.userId).token

        val error = ticketError(strangerToken, seeded.instanceId, "image/jpeg", 120_000)

        assertThat(error.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `refuses a request without a token`(): Unit = runBlocking {
        val seeded = seedRunningQaTry()

        val error = ticketError(token = null, instanceId = seeded.instanceId, contentType = "image/jpeg", contentLength = 120_000)

        assertThat(error.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `refuses a content type that is not a screen capture`(): Unit = runBlocking {
        val seeded = seedRunningQaTry()

        val error = ticketError(seeded.sdkToken, seeded.instanceId, "application/pdf", 120_000)

        assertThat(error.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        // 4xx는 도메인 안내 message를 그대로 준다(서버 내부가 아니라 요청에 대한 안내).
        assertThat(error.responseBodyAsString).contains("application/pdf")
    }

    @Test
    fun `refuses a capture larger than the cap`(): Unit = runBlocking {
        val seeded = seedRunningQaTry()

        val error = ticketError(
            seeded.sdkToken,
            seeded.instanceId,
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
    fun `records a small SCREENSHOT log row that points at the image`(): Unit = runBlocking {
        val seeded = seedRunningQaTry()

        val ticket = issueTicket(seeded.sdkToken, seeded.instanceId, "image/jpeg", 120_000, targetId = 7)

        val logs = qaLogRepository.findAll().toList()
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

    private data class SeededRun(val sdkToken: String, val instanceId: String, val qaTryId: Long)

    private fun issueTicket(
        token: String?,
        instanceId: String,
        contentType: String,
        contentLength: Long,
        targetId: Int? = null
    ): JsonNode =
        client().post()
            .uri("/api/sdk/qa-captures/tickets")
            .apply { if (token != null) header(HttpHeaders.AUTHORIZATION, "Bearer $token") }
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body(instanceId, contentType, contentLength, targetId))
            .retrieve()
            .bodyToMono(JsonNode::class.java)
            .block(TIMEOUT)!!

    private fun ticketError(
        token: String?,
        instanceId: String,
        contentType: String,
        contentLength: Long
    ): WebClientResponseException =
        runCatching { issueTicket(token, instanceId, contentType, contentLength) }
            .exceptionOrNull() as WebClientResponseException

    private fun body(
        instanceId: String,
        contentType: String,
        contentLength: Long,
        targetId: Int?
    ): Map<String, Any> = buildMap {
        put("instanceId", instanceId)
        put("contentType", contentType)
        put("contentLength", contentLength)
        targetId?.let { put("targetId", it) }
    }

    private fun client(): WebClient = WebClient.create("http://localhost:$port")

    private suspend fun seedRunningQaTry(status: String = "RUNNING"): SeededRun {
        val owner = signIn()
        val ownerId = owner.userId.toLong()
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "capture-project", genre = "ACTION", createdAt = now, updatedAt = now)
        )
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = project.id!!,
                appUserId = ownerId,
                role = ProjectRole.OWNER.name,
                createdAt = now
            )
        )
        val scenario = testScenarioRepository.save(
            TestScenarioEntity(projectId = project.id!!)
        )
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = project.id!!,
                name = "instance",
                platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now
            )
        )
        val qaTry = qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenario.id!!,
                gameInstanceId = instance.id!!,
                startedBy = ownerId,
                status = status,
                startedAt = now,
                completedAt = if (status == "RUNNING") null else now
            )
        )
        return SeededRun(
            sdkToken = jwtService.issueSdkToken(owner.userId).token,
            instanceId = instance.id!!.toString(),
            qaTryId = qaTry.id!!
        )
    }

    private suspend fun signIn(
        providerUserId: String = "42",
        login: String = "octocat"
    ): AuthenticatedUser =
        oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = login,
                displayName = login,
                avatarUrl = null,
                email = "$login@example.com"
            )
        )

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
