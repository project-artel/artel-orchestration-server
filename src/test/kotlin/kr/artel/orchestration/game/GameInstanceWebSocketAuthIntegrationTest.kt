package kr.artel.orchestration.game

import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.entity.GamePlatform
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.dto.Genre
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.sdk.service.SessionManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.publisher.Sinks
import java.net.URI
import java.time.Duration
import java.time.Instant

/**
 * 웹소켓 인증은 SDK 토큰과 instanceId로 통과한다. 토큰은 쿼리 파라미터로 오므로 시큐리티
 * 필터가 아니라 핸들러가 직접 검증한다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameInstanceWebSocketAuthIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var memberRepository: ProjectMemberRepository
    @Autowired private lateinit var instanceRepository: GameInstanceRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var sessionManager: SessionManager

    @Test
    fun `closes with 4001 when the instance does not exist`(): Unit = runBlocking {
        val member = signIn("42", "octocat")

        val closeStatus = connectAndAwaitClose(member.sdkToken, "999999")

        assertThat(closeStatus?.code).isEqualTo(4001)
    }

    @Test
    fun `closes with 4001 when the token is missing entirely`(): Unit = runBlocking {
        val closeStatus = connectAndAwaitClose(token = null, instanceId = "1")

        assertThat(closeStatus?.code).isEqualTo(4001)
    }

    /** 30일짜리 SDK 토큰과 브라우저 세션은 audience가 다르다. 서로의 자리에서 통하면 안 된다. */
    @Test
    fun `closes with 4001 when a browser session token is used`(): Unit = runBlocking {
        val member = signIn("42", "octocat")
        val instance = createGameInstance(member.userId)

        val closeStatus = connectAndAwaitClose(member.webToken, requireNotNull(instance.id).toString())

        assertThat(closeStatus?.code).isEqualTo(4001)
    }

    @Test
    fun `closes with 4001 when the user is not a member of the project`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val stranger = signIn("77", "stranger")
        val instance = createGameInstance(owner.userId)

        val closeStatus = connectAndAwaitClose(stranger.sdkToken, requireNotNull(instance.id).toString())

        assertThat(closeStatus?.code).isEqualTo(4001)
    }

    @Test
    fun `stops accepting a soft-deleted instance`(): Unit = runBlocking {
        val member = signIn("42", "octocat")
        val instance = createGameInstance(member.userId)
        instanceRepository.save(instance.copy(deletedAt = Instant.now()))

        val closeStatus = connectAndAwaitClose(member.sdkToken, requireNotNull(instance.id).toString())

        assertThat(closeStatus?.code).isEqualTo(4001)
    }

    /**
     * 두 번째 연결을 거절한다. 앞 연결을 밀어내면 진행 중인 QA 세션이 우연한 두 번째 실행에
     * 소켓을 빼앗긴다.
     */
    @Test
    fun `closes with 4002 when the instance already has a live connection`(): Unit = runBlocking {
        val member = signIn("42", "octocat")
        val instance = createGameInstance(member.userId)
        val instanceId = requireNotNull(instance.id).toString()
        val incumbent = Mockito.mock(WebSocketSession::class.java)
        sessionManager.register(instanceId, incumbent)

        try {
            val closeStatus = connectAndAwaitClose(member.sdkToken, instanceId)
            assertThat(closeStatus?.code).isEqualTo(4002)
        } finally {
            sessionManager.removeSession(instanceId, incumbent)
        }
    }

    private fun connectAndAwaitClose(token: String?, instanceId: String?): CloseStatus? {
        val params = listOfNotNull(
            token?.let { "token=$it" },
            instanceId?.let { "instanceId=$it" }
        )
        val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        val uri = URI("ws://localhost:$port/ws/sdk$query")
        val captured = Sinks.one<CloseStatus>()

        ReactorNettyWebSocketClient().execute(uri) { session ->
            session.receive()
                .then()
                .then(session.closeStatus())
                .doOnNext { status -> captured.tryEmitValue(status) }
                .then()
        }.block(Duration.ofSeconds(5))

        return captured.asMono().block(Duration.ofSeconds(5))
    }

    private data class SignedInUser(val userId: Long, val webToken: String, val sdkToken: String)

    private suspend fun signIn(providerUserId: String, login: String): SignedInUser {
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = login,
                displayName = login,
                avatarUrl = null,
                email = "$login@example.com"
            )
        )
        return SignedInUser(
            userId = user.userId.toLong(),
            webToken = jwtService.issue(user),
            sdkToken = jwtService.issueSdkToken(user.userId).token
        )
    }

    private suspend fun createGameInstance(ownerId: Long): GameInstanceEntity {
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(
                name = "웹소켓 인증 테스트",
                genre = Genre.OTHER.name,
                createdAt = now,
                updatedAt = now
            )
        )
        memberRepository.save(
            ProjectMemberEntity(
                projectId = requireNotNull(project.id),
                appUserId = ownerId,
                role = ProjectRole.OWNER.name,
                createdAt = now
            )
        )

        return instanceRepository.save(
            GameInstanceEntity(
                projectId = requireNotNull(project.id),
                name = "웹소켓 인증 인스턴스",
                platform = GamePlatform.UNITY.name,
                sdkUuid = "sdk-uuid-ws",
                createdAt = now,
                updatedAt = now
            )
        )
    }
}
