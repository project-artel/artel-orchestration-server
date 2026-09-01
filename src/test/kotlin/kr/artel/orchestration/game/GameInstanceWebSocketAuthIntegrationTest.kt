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
import reactor.core.publisher.Mono
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

    /**
     * Reactor Netty 기본값(65536)을 넘는 메시지가 소켓을 끊지 않는다 (ARTEL-682).
     *
     * 상한을 정하는 코드는 [kr.artel.orchestration.sdk.config.SdkSocketProperties] 로 오래 있었지만
     * **그 값이 소켓에 닿은 적이 없었다.** `WebSocketService` 를 `@Bean` 으로 냈는데 Spring 은
     * 어댑터를 만들 때 그 빈을 조회하지 않는다 — `WebFluxConfigurer.getWebSocketService()` 로만
     * 받고, 없으면 기본값으로 자기 것을 만든다. 그래서 상한은 내내 65536 이었고, 전투 씬 판독이
     * 그것을 넘긴 날에야 드러났다.
     *
     * 설정한 값과 실제로 걸리는 값이 어긋나면 깨지는 테스트가 이것 하나다. 없으면 같은 버그가
     * 같은 방식으로 다시 조용해진다 — 값을 고쳐도 아무 일도 일어나지 않고, 아무도 모른다.
     *
     * 타입은 핸들러가 모르는 것으로 보낸다. 핸들러는 그것을 경고로 남기고 흘려보내므로
     * (`정의되지 않은 메시지 타입 수신`), 여기서 소켓이 닫힌다면 그것은 핸들러가 아니라 프레임을
     * 합치는 자리에서 닫은 것이다.
     */
    @Test
    fun `a message past the netty default does not close the socket`(): Unit = runBlocking {
        val member = signIn("42", "octocat")
        val instance = createGameInstance(member.userId)

        // 65536 위, 설정한 상한(256 KiB) 아래.
        val payload = """{"type":"NOT_A_REAL_TYPE","pad":"${"x".repeat(100_000)}"}"""

        val closeStatus = connectAndSend(
            member.sdkToken, requireNotNull(instance.id).toString(), payload
        )

        assertThat(closeStatus).isNull()
    }

    /**
     * 보내고 나서 서버가 닫는지 본다. 닫지 않으면 `null` 이다.
     *
     * [connectAndAwaitClose] 와 갈라 두는 것은 기다리는 것이 반대이기 때문이다 — 저쪽은 닫히기를
     * 기다리고 이쪽은 닫히지 않기를 기다린다. 한 함수로 합치면 어느 쪽을 보는 테스트인지 호출부에서
     * 읽히지 않는다.
     */
    private fun connectAndSend(token: String, instanceId: String, text: String): CloseStatus? {
        val uri = URI("ws://localhost:$port/ws/sdk?token=$token&instanceId=$instanceId")
        val captured = Sinks.one<CloseStatus>()

        val connection = ReactorNettyWebSocketClient().execute(uri) { session ->
            session.send(Mono.just(session.textMessage(text)))
                .then(session.closeStatus())
                .doOnNext { status -> captured.tryEmitValue(status) }
                .then()
        }.subscribe()

        // 닫히지 않는 것을 보는 테스트라 이 대기는 결과 자체다. 서버가 끊으면 그 전에 값이 온다.
        // `block(timeout)` 은 시간이 다하면 던지므로 여기서 쓸 수 없다 — 던지지 않고 비어서
        // 끝나는 것이 이 테스트가 기다리는 답이다.
        val status = captured.asMono()
            .timeout(Duration.ofSeconds(3), Mono.empty())
            .block()
        connection.dispose()
        return status
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
