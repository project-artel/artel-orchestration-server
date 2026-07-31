package kr.artel.orchestration

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.entity.GamePlatform
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.dto.Genre
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.sdk.dto.*
import kr.artel.orchestration.sdk.service.GameStateTransformer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.publisher.Mono
import kr.artel.orchestration.qa.service.QaSdkBridgeService
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import org.springframework.boot.test.mock.mockito.MockBean
import org.mockito.Mockito
import reactor.core.publisher.Sinks
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.*

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ArtelWebSocketIntegrationTest {

    // 스프링이 랜덤으로 띄운 내장 Netty 서버의 포트를 주입받음
    @LocalServerPort
    private val port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockBean
    private lateinit var qaBridge: QaSdkBridgeService

    @Autowired
    private lateinit var jwtService: JwtService

    @Autowired
    private lateinit var oauthUserService: OAuthUserService

    @Autowired
    private lateinit var projectRepository: ProjectRepository

    @Autowired
    private lateinit var instanceRepository: GameInstanceRepository

    @Autowired
    private lateinit var memberRepository: ProjectMemberRepository

    /**
     * 웹소켓 인증이 인메모리 목록이 아니라 DB의 게임 인스턴스를 보게 되면서, 연결 전에 실제
     * 행이 있어야 한다. 프로젝트와 참여자까지 함께 만드는 이유는 인증이 "이 토큰의 사용자가
     * 이 인스턴스의 프로젝트 참여자인가"로 판단하기 때문이다.
     */
    private suspend fun createGameInstance(): ConnectableInstance {
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(
                name = "웹소켓 테스트 프로젝트",
                genre = Genre.OTHER.name,
                createdAt = now,
                updatedAt = now
            )
        )
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = UUID.randomUUID().toString(),
                login = "ws-tester",
                displayName = "ws-tester",
                avatarUrl = null,
                email = "ws-tester@example.com"
            )
        )
        memberRepository.save(
            ProjectMemberEntity(
                projectId = requireNotNull(project.id),
                appUserId = user.userId.toLong(),
                role = ProjectRole.OWNER.name,
                createdAt = now
            )
        )

        val instance = instanceRepository.save(
            GameInstanceEntity(
                projectId = requireNotNull(project.id),
                name = "웹소켓 테스트 인스턴스",
                platform = GamePlatform.UNITY.name,
                sdkUuid = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now
            )
        )
        return ConnectableInstance(instance, jwtService.issueSdkToken(user.userId).token)
    }

    /** 인스턴스와 그 인스턴스에 붙을 수 있는 SDK 토큰. 핸드셰이크에 둘 다 필요하다. */
    private data class ConnectableInstance(val instance: GameInstanceEntity, val sdkToken: String) {
        val handshakeQuery: String
            get() = "?token=$sdkToken&instanceId=${requireNotNull(instance.id)}"
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyKotlin(type: Class<T>): T {
        Mockito.any(type)
        return null as T
    }

    /**
     * SdkGameState에서 AgentGameState로 정제하는 변환 로직에 대한 단체 단언(Unit Test)
     */
    @Test
    fun testGameStateTransformation(): Unit = runBlocking {
        val testGameState = createSampleGameState()
        val agentGameState = GameStateTransformer.toAgentGameState(testGameState)

        assertThat(agentGameState.scene).isEqualTo("SampleScene")

        // 1. 조작 후보(interactables) 단언
        assertThat(agentGameState.interactables).hasSize(3)

        val player = agentGameState.interactables.first { it.id == 2 }
        assertThat(player.name).isEqualTo("Player")
        assertThat(player.type).isEqualTo("PlayerController")
        assertThat(player.actions).containsExactly("Jump", "GetScore", "UseItem")
        assertThat(player.label).isNull()

        val submitButton = agentGameState.interactables.first { it.id == 5 }
        assertThat(submitButton.name).isEqualTo("SubmitButton")
        assertThat(submitButton.type).isEqualTo("button")
        assertThat(submitButton.actions).isNull()
        assertThat(submitButton.label).isEqualTo("Submit")

        val nameInput = agentGameState.interactables.first { it.id == 6 }
        assertThat(nameInput.name).isEqualTo("NameInput")
        assertThat(nameInput.type).isEqualTo("editText")
        assertThat(nameInput.actions).isNull()
        assertThat(nameInput.placeholder).isEqualTo("Enter text...")

        // 2. 관찰 값(observables) 단언 (세부 값 및 타입 정보 검증)
        assertThat(agentGameState.observables).hasSize(4)
        assertThat(agentGameState.observables).containsEntry(
            "Player.PlayerController.health",
            ObservableValue(value = 100, type = "int")
        )
        assertThat(agentGameState.observables).containsEntry(
            "Player.PlayerController.playerName",
            ObservableValue(value = "Player1", type = "string")
        )
        assertThat(agentGameState.observables).containsEntry(
            "Canvas.NameInput.content",
            ObservableValue(value = "", type = "string")
        )
        assertThat(agentGameState.observables).containsEntry(
            "Canvas.TitleLabel.content",
            ObservableValue(value = "Score: 1200", type = "string")
        )
        // 버튼 라벨 텍스트는 observables에서 제외되었는지 검증
        assertThat(agentGameState.observables).doesNotContainKey("Canvas.SubmitButton.content")
    }

    @Test
    fun testAuthenticatedUserEndpoint(): Unit = runBlocking {
        val webClient = WebClient.create("http://localhost:$port")
        val unauthorizedStatus = webClient.get()
            .uri("/api/auth/me")
            .exchangeToMono { Mono.just(it.statusCode()) }
            .block(Duration.ofSeconds(5))

        assertThat(unauthorizedStatus?.value()).isEqualTo(401)

        // /api/auth/me는 DB에서 프로필을 읽으므로 실제 사용자가 있어야 한다.
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = "42",
                login = "octocat",
                displayName = "The Octocat",
                avatarUrl = "https://avatars.example/octocat.png",
                email = "octocat@example.com"
            )
        )
        val token = jwtService.issue(user)
        val authenticatedResponse = webClient.get()
            .uri("/api/auth/me")
            .cookie("artel_access_token", token)
            .retrieve()
            .bodyToMono(String::class.java)
            .block(Duration.ofSeconds(5))

        assertThat(authenticatedResponse).contains("\"id\":\"${user.userId}\"")
        assertThat(authenticatedResponse).contains("\"displayName\":\"The Octocat\"")
        assertThat(authenticatedResponse).contains("\"provider\":\"github\"")
        assertThat(authenticatedResponse).contains("\"login\":\"octocat\"")
    }

    @Test
    fun `rejects a well-formed token whose user no longer exists`(): Unit = runBlocking {
        val orphanToken = jwtService.issue(
            AuthenticatedUser(
                userId = "99999999",
                provider = "github",
                login = "ghost",
                displayName = "Ghost",
                avatarUrl = null
            )
        )
        val status = WebClient.create("http://localhost:$port").get()
            .uri("/api/auth/me")
            .cookie("artel_access_token", orphanToken)
            .exchangeToMono { Mono.just(it.statusCode()) }
            .block(Duration.ofSeconds(5))

        assertThat(status?.value()).isEqualTo(401)
    }

    @Test
    fun `rejects a token carrying a pre-migration subject format`(): Unit = runBlocking {
        val legacyToken = jwtService.issue(
            AuthenticatedUser(
                userId = "github:42",
                provider = "github",
                login = "octocat",
                displayName = "The Octocat",
                avatarUrl = null
            )
        )
        val status = WebClient.create("http://localhost:$port").get()
            .uri("/api/auth/me")
            .cookie("artel_access_token", legacyToken)
            .exchangeToMono { Mono.just(it.statusCode()) }
            .block(Duration.ofSeconds(5))

        assertThat(status?.value()).isEqualTo(401)
    }

    private fun createSampleGameState(): SdkGameState {
        return SdkGameState(
            type = "GAME_STATE",
            id = 1,
            scene = SdkBlock(
                id = 1,
                type = "scene",
                name = "SampleScene",
                children = listOf(
                    SdkBlock(
                        id = 2,
                        type = "block",
                        name = "Player",
                        components = listOf(
                            SdkComponent(
                                type = "PlayerController",
                                name = "PlayerController",
                                states = listOf(
                                    SdkState("hp", "health", "int", 100),
                                    SdkState("nickname", "playerName", "string", "Player1")
                                ),
                                actions = listOf(
                                    SdkAction(
                                        sequence = 1,
                                        tag = "jump",
                                        name = "Jump",
                                        success = true,
                                        returnValue = null,
                                        timeStamp = "2026-07-15T11:01:13.1372630+00:00"
                                    ),
                                    SdkAction(
                                        sequence = 2,
                                        tag = "get_score",
                                        name = "GetScore",
                                        success = true,
                                        returnValue = 1200,
                                        timeStamp = "2026-07-15T11:01:13.1375250+00:00"
                                    ),
                                    SdkAction(
                                        sequence = 3,
                                        tag = "use_item",
                                        name = "UseItem",
                                        success = false,
                                        returnValue = null,
                                        error = SdkError("System.InvalidOperationException", "Item not found"),
                                        timeStamp = "2026-07-15T11:01:13.1377350+00:00"
                                    )
                                )
                            )
                        )
                    ),
                    SdkBlock(
                        id = 3,
                        type = "block",
                        name = "Canvas",
                        children = listOf(
                            SdkBlock(
                                id = 4,
                                type = "block",
                                name = "TitleLabel",
                                components = listOf(
                                    SdkComponent(
                                        type = "text",
                                        name = "TitleLabel",
                                        content = "Score: 1200"
                                    )
                                )
                            ),
                            SdkBlock(
                                id = 5,
                                type = "block",
                                name = "SubmitButton",
                                components = listOf(
                                    SdkComponent(type = "button", name = "SubmitButton"),
                                    SdkComponent(type = "text", name = "SubmitButton", content = "Submit")
                                )
                            ),
                            SdkBlock(
                                id = 6,
                                type = "block",
                                name = "NameInput",
                                components = listOf(
                                    SdkComponent(
                                        type = "editText",
                                        name = "NameInput",
                                        content = "",
                                        placeholder = "Enter text..."
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
    }

    /**
     * Agent로부터 수신한 ACTION 요청이 웹소켓 클라이언트로 정상 전달되는지 전체 흐름을 테스트합니다.
     */
    @Test
    fun testWebSocketActionForwardingFlow(): Unit = runBlocking {
        val connectable = createGameInstance()
        val instanceId = requireNotNull(connectable.instance.id)
        val webClient = WebClient.create("http://localhost:$port")

        // 1. 웹소켓 모킹 클라이언트(Mock SDK Client) 구동 및 연결 시도
        val wsClient = ReactorNettyWebSocketClient()
        val wsUri = URI("ws://localhost:$port/ws/sdk${connectable.handshakeQuery}")
        val actionReceivedLatch = Sinks.one<ActionResponseDto>()

        val clientSessionMono = wsClient.execute(wsUri) { session ->
            val receiver = session.receive()
                .doOnNext { message ->
                    val payload = message.payloadAsText
                    try {
                        val actionResponse = objectMapper.readValue(payload, ActionResponseDto::class.java)
                        actionReceivedLatch.tryEmitValue(actionResponse)
                    } catch (e: Exception) {
                        // ignore other messages
                    }
                }
                .then()

            receiver
        }

        val disposable = clientSessionMono.subscribe()

        // 소켓 연결이 수립될 때까지 잠시 대기
        Thread.sleep(1000)

        // 3. HTTP POST로 ACTION 요청 전달 (Agent -> Orchestrator)
        val actionPayload = ActionResponseDto(
            type = "ACTION",
            id = 2,
            actions = listOf(
                ActionItemDto(id = 1, jsonrpc = "2.0", method = "button_click", params = listOf(2)),
                ActionItemDto(id = 2, jsonrpc = "2.0", method = "enter_text", params = listOf(3, "입력할 문자열")),
                ActionItemDto(id = 3, jsonrpc = "2.0", method = "key_click", params = listOf("Space", 0.5))
            )
        )

        val actionResponse = webClient.post()
            .uri("/api/orchestration/action/$instanceId")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(actionPayload)
            .retrieve()
            .toEntity(String::class.java)
            .block(Duration.ofSeconds(5))

        assertThat(actionResponse?.statusCode?.is2xxSuccessful).isTrue()

        // 4. 웹소켓 클라이언트가 전송받은 데이터 검증 (Orchestrator -> SDK)
        val receivedAction = actionReceivedLatch.asMono().block(Duration.ofSeconds(5))
        assertThat(receivedAction).isNotNull
        assertThat(receivedAction?.type).isEqualTo("ACTION")
        assertThat(receivedAction?.id).isEqualTo(2L)
        assertThat(receivedAction?.actions).hasSize(3)

        val action1 = receivedAction?.actions?.get(0)
        assertThat(action1?.method).isEqualTo("button_click")
        assertThat(action1?.params).containsExactly(2)

        val action2 = receivedAction?.actions?.get(1)
        assertThat(action2?.method).isEqualTo("enter_text")
        assertThat(action2?.params).containsExactly(3, "입력할 문자열")

        val action3 = receivedAction?.actions?.get(2)
        assertThat(action3?.method).isEqualTo("key_click")
        assertThat(action3?.params).containsExactly("Space", 0.5)

        disposable.dispose()
    }

    /**
     * SDK(ARTEL-154)가 새로 추가한 마우스/키 ACTION 메서드가 릴레이를 그대로 통과하는지 고정합니다.
     *
     * 오케스트레이션은 `method`를 해석하지 않습니다. `ActionItemDto.method`는 enum이 아닌 String이고,
     * 검증은 `QaActionDispatchService`의 non-blank 확인 한 줄뿐이라 어떤 메서드든 그대로 흘러갑니다.
     * 이 성질은 코드 어디에도 명시돼 있지 않아, 나중에 누군가 메서드 화이트리스트나 enum을 넣어도
     * 아무것도 깨지지 않습니다. 이 테스트가 그때 가장 먼저 실패하는 자리입니다.
     *
     * 그래서 단언 대상은 "전달됐는가"가 아니라 메서드명, params(값·타입·빈 배열), 그리고 배치 순서가
     * 무손실인가입니다. 순서는 드래그 앤 드롭이 쓰는 그대로이며, 재정렬되면 조작 의미가 달라집니다.
     */
    @Test
    fun testWebSocketMouseAndKeyActionForwardingFlow(): Unit = runBlocking {
        val connectable = createGameInstance()
        val instanceId = requireNotNull(connectable.instance.id)
        val webClient = WebClient.create("http://localhost:$port")

        val wsClient = ReactorNettyWebSocketClient()
        val wsUri = URI("ws://localhost:$port/ws/sdk${connectable.handshakeQuery}")
        val actionReceivedLatch = Sinks.one<ActionResponseDto>()

        val clientSessionMono = wsClient.execute(wsUri) { session ->
            session.receive()
                .doOnNext { message ->
                    val payload = message.payloadAsText
                    try {
                        val actionResponse = objectMapper.readValue(payload, ActionResponseDto::class.java)
                        actionReceivedLatch.tryEmitValue(actionResponse)
                    } catch (e: Exception) {
                        // ignore other messages
                    }
                }
                .then()
        }

        val disposable = clientSessionMono.subscribe()

        // 소켓 연결이 수립될 때까지 잠시 대기
        Thread.sleep(1000)

        // 드래그 앤 드롭 한 동작을 이루는 배치. mouse_down은 버튼 인덱스를 명시하고(0=left),
        // mouse_up은 생략형([])으로 둬서 두 형태가 모두 그대로 건너가는지 함께 태운다.
        val actionPayload = ActionResponseDto(
            type = "ACTION",
            id = 7,
            actions = listOf(
                ActionItemDto(id = 1, jsonrpc = "2.0", method = "mouse_down", params = listOf(0)),
                ActionItemDto(id = 2, jsonrpc = "2.0", method = "move_mouse", params = listOf(120, 640)),
                ActionItemDto(id = 3, jsonrpc = "2.0", method = "move_mouse", params = listOf(880, 200)),
                ActionItemDto(id = 4, jsonrpc = "2.0", method = "mouse_up", params = emptyList()),
                ActionItemDto(id = 5, jsonrpc = "2.0", method = "key_down", params = listOf("Space")),
                ActionItemDto(id = 6, jsonrpc = "2.0", method = "key_up", params = listOf("Space"))
            )
        )

        val actionResponse = webClient.post()
            .uri("/api/orchestration/action/$instanceId")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(actionPayload)
            .retrieve()
            .toEntity(String::class.java)
            .block(Duration.ofSeconds(5))

        assertThat(actionResponse?.statusCode?.is2xxSuccessful).isTrue()

        val receivedAction = actionReceivedLatch.asMono().block(Duration.ofSeconds(5))
        assertThat(receivedAction).isNotNull
        assertThat(receivedAction?.type).isEqualTo("ACTION")
        assertThat(receivedAction?.id).isEqualTo(7L)
        assertThat(receivedAction?.actions).hasSize(6)

        // 배치 순서 자체가 계약이다. 개별 항목만 보면 재정렬 회귀를 놓친다.
        assertThat(receivedAction?.actions?.map { it.method }).containsExactly(
            "mouse_down", "move_mouse", "move_mouse", "mouse_up", "key_down", "key_up"
        )
        assertThat(receivedAction?.actions?.map { it.id }).containsExactly(1L, 2L, 3L, 4L, 5L, 6L)

        val mouseDown = receivedAction?.actions?.get(0)
        assertThat(mouseDown?.params).containsExactly(0)

        val moveMouseFrom = receivedAction?.actions?.get(1)
        assertThat(moveMouseFrom?.params).containsExactly(120, 640)

        val moveMouseTo = receivedAction?.actions?.get(2)
        assertThat(moveMouseTo?.params).containsExactly(880, 200)

        // 빈 params도 손실 없이 건너가야 한다. SDK가 여기서 기본 버튼(0=left)을 채운다.
        val mouseUp = receivedAction?.actions?.get(3)
        assertThat(mouseUp?.params).isEmpty()

        val keyDown = receivedAction?.actions?.get(4)
        assertThat(keyDown?.params).containsExactly("Space")

        val keyUp = receivedAction?.actions?.get(5)
        assertThat(keyUp?.params).containsExactly("Space")

        disposable.dispose()
    }

    /**
     * SDK가 웹소켓을 통해 보낸 ACTION_RESULT 메시지가 SdkMessageHandler 전략 패턴을 거쳐
     * QA 브리지(QaSdkBridgeService)로 전달되는지 검증합니다. Agent Server로 HTTP POST하던
     * 폴백은 존재하지 않는 엔드포인트라 제거되었고, 이제 QA 브리지가 유일한 소비자입니다.
     */
    @Test
    fun testWebSocketActionResultForwardingFlow(): Unit = runBlocking {
        val connectable = createGameInstance()
        val instanceId = requireNotNull(connectable.instance.id)

        // 1. QA 브리지 모킹 및 감시
        val resultReceivedLatch = Sinks.one<Pair<Long, String>>()
        Mockito.`when`(
            qaBridge.routeActionResult(Mockito.anyLong(), anyKotlin(String::class.java))
        ).thenAnswer { invocation ->
            resultReceivedLatch.tryEmitValue(
                invocation.getArgument<Long>(0) to invocation.getArgument<String>(1)
            )
            true
        }

        // 2. 웹소켓 클라이언트 연결 및 결과 전송
        val wsClient = ReactorNettyWebSocketClient()
        val wsUri = URI("ws://localhost:$port/ws/sdk${connectable.handshakeQuery}")

        val clientSessionMono = wsClient.execute(wsUri) { session ->
            val resultPayload = """
                {
                  "type": "ACTION_RESULT",
                  "id": 1,
                  "results": [
                    {
                      "id": 2,
                      "success": true,
                      "error": ""
                    },
                    {
                      "id": 3,
                      "success": false,
                      "error": "Unknown target id: 123"
                    }
                  ]
                }
            """.trimIndent()
            val resultMessage = session.textMessage(resultPayload)
            // send()가 끝나자마자 핸들러를 완료시키면 클라이언트가 close를 보내고, 서버가
            // 프레임을 읽기 전에 receive 파이프라인이 끊길 수 있다. 단언이 끝나고 dispose로
            // 닫을 때까지 세션을 열어 둔다.
            session.send(Mono.just(resultMessage)).then(Mono.never())
        }

        val disposable = clientSessionMono.subscribe()

        // 4. QA 브리지로 결과가 전달되었는지 최종 검증
        val receivedResult = resultReceivedLatch.asMono().block(Duration.ofSeconds(5))
        assertThat(receivedResult).isNotNull
        assertThat(receivedResult?.first).isEqualTo(instanceId)
        assertThat(receivedResult?.second).contains("ACTION_RESULT")
        assertThat(receivedResult?.second).contains("Unknown target id: 123")

        disposable.dispose()
    }
}
