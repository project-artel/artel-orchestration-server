package kr.artel.orchestration.game

import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.entity.GamePlatform
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.dto.Genre
import kr.artel.orchestration.project.entity.ProjectEntity
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
import java.util.UUID

/**
 * 웹소켓 인증은 instanceKey로 통과하고, 그 뒤로는 게임 인스턴스 id만 쓴다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameInstanceWebSocketAuthIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var instanceRepository: GameInstanceRepository
    @Autowired private lateinit var sessionManager: SessionManager

    @Test
    fun `closes with 4001 when the key matches no instance`(): Unit = runBlocking {
        val closeStatus = connectAndAwaitClose("NOSUCH-KEY-00000-00000")

        assertThat(closeStatus?.code).isEqualTo(4001)
    }

    @Test
    fun `closes with 4001 when the key is missing entirely`(): Unit = runBlocking {
        val closeStatus = connectAndAwaitClose(instanceKey = null)

        assertThat(closeStatus?.code).isEqualTo(4001)
    }

    /**
     * 두 번째 연결을 거절한다. 앞 연결을 밀어내면 진행 중인 QA 세션이 우연한 두 번째 실행에
     * 소켓을 빼앗긴다.
     */
    @Test
    fun `closes with 4002 when the instance already has a live connection`(): Unit = runBlocking {
        val instance = createGameInstance()
        val instanceId = requireNotNull(instance.id).toString()
        val incumbent = Mockito.mock(WebSocketSession::class.java)
        sessionManager.register(instanceId, incumbent)

        try {
            val closeStatus = connectAndAwaitClose(instance.instanceKey)
            assertThat(closeStatus?.code).isEqualTo(4002)
        } finally {
            sessionManager.removeSession(instanceId, incumbent)
        }
    }

    @Test
    fun `stops accepting the key of a soft-deleted instance`(): Unit = runBlocking {
        val instance = createGameInstance()
        instanceRepository.save(instance.copy(deletedAt = Instant.now()))

        val closeStatus = connectAndAwaitClose(instance.instanceKey)

        assertThat(closeStatus?.code).isEqualTo(4001)
    }

    private fun connectAndAwaitClose(instanceKey: String?): CloseStatus? {
        val query = if (instanceKey == null) "" else "?instanceKey=$instanceKey"
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

    private suspend fun createGameInstance(): GameInstanceEntity {
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(
                name = "웹소켓 인증 테스트",
                genre = Genre.OTHER.name,
                createdAt = now,
                updatedAt = now
            )
        )

        return instanceRepository.save(
            GameInstanceEntity(
                projectId = requireNotNull(project.id),
                name = "웹소켓 인증 인스턴스",
                platform = GamePlatform.UNITY.name,
                instanceKey = UUID.randomUUID().toString().uppercase().take(23),
                createdAt = now,
                updatedAt = now
            )
        )
    }
}
