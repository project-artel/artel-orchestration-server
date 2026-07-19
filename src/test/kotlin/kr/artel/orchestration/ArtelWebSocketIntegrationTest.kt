package kr.artel.orchestration

import com.fasterxml.jackson.databind.ObjectMapper
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
import kr.artel.orchestration.sdk.service.AgentClient
import org.springframework.boot.test.mock.mockito.MockBean
import org.mockito.Mockito
import reactor.core.publisher.Sinks
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.time.Duration
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
    private lateinit var agentClient: AgentClient

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyKotlin(type: Class<T>): T {
        Mockito.any(type)
        return null as T
    }

    /**
     * SdkGameState에서 AgentGameState로 정제하는 변환 로직에 대한 단체 단언(Unit Test)
     */
    @Test
    fun testGameStateTransformation() {
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
            "NameInput.content",
            ObservableValue(value = "", type = "string")
        )
        assertThat(agentGameState.observables).containsEntry(
            "TitleLabel.content",
            ObservableValue(value = "Score: 1200", type = "string")
        )
        // 버튼 라벨 텍스트는 observables에서 제외되었는지 검증
        assertThat(agentGameState.observables).doesNotContainKey("SubmitButton.content")
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
    fun testWebSocketActionForwardingFlow() {
        val testSdkId = UUID.randomUUID().toString()
        val webClient = WebClient.create("http://localhost:$port")

        // 1. REST API를 이용해 테스트할 임의의 sdkId를 승인 목록에 등록
        val registrationResponse = webClient.post()
            .uri("/api/sdkId")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(SdkIdRegistrationRequest(testSdkId))
            .retrieve()
            .toEntity(String::class.java)
            .block(Duration.ofSeconds(5))

        assertThat(registrationResponse?.statusCode?.is2xxSuccessful).isTrue()

        // 2. 웹소켓 모킹 클라이언트(Mock SDK Client) 구동 및 연결 시도
        val wsClient = ReactorNettyWebSocketClient()
        val wsUri = URI("ws://localhost:$port/ws/sdk?sdkId=$testSdkId")
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
            .uri("/api/orchestration/action/$testSdkId")
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
     * SDK가 웹소켓을 통해 보낸 ACTION_RESULT 메시지가 SdkMessageHandler 전략 패턴을 거쳐
     * AgentClient.sendResult로 올바르게 전달되는지 검증합니다.
     */
    @Test
    fun testWebSocketActionResultForwardingFlow() {
        val testSdkId = UUID.randomUUID().toString()
        val webClient = WebClient.create("http://localhost:$port")

        // 1. AgentClient.sendResult 모킹 및 감시
        val resultReceivedLatch = Sinks.one<String>()
        Mockito.`when`(agentClient.sendResult(anyKotlin(String::class.java))).thenAnswer { invocation ->
            val arg = invocation.getArgument<String>(0)
            resultReceivedLatch.tryEmitValue(arg)
            Mono.just("mocked")
        }

        // 2. sdkId 등록
        val registrationResponse = webClient.post()
            .uri("/api/sdkId")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(SdkIdRegistrationRequest(testSdkId))
            .retrieve()
            .toEntity(String::class.java)
            .block(Duration.ofSeconds(5))

        assertThat(registrationResponse?.statusCode?.is2xxSuccessful).isTrue()

        // 3. 웹소켓 클라이언트 연결 및 결과 전송
        val wsClient = ReactorNettyWebSocketClient()
        val wsUri = URI("ws://localhost:$port/ws/sdk?sdkId=$testSdkId")

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
            session.send(Mono.just(resultMessage)).then()
        }

        val disposable = clientSessionMono.subscribe()

        // 4. AgentClient로 결과가 전송되었는지 최종 검증
        val receivedResult = resultReceivedLatch.asMono().block(Duration.ofSeconds(5))
        assertThat(receivedResult).isNotNull
        assertThat(receivedResult).contains("ACTION_RESULT")
        assertThat(receivedResult).contains("Unknown target id: 123")

        disposable.dispose()
    }
}

