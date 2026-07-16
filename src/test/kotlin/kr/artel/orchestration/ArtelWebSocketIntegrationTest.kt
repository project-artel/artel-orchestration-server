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
import java.net.URI
import java.time.Duration
import java.util.*

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

    /**
     * 웹소켓 연결수립 -> 스캔데이터 전송 -> 커맨드 푸시 전체 흐름을 테스트합니다.
     */
    @Test
    fun testWebSocketBidirectionalFlow() {
        Mockito.`when`(agentClient.sendState(anyKotlin(AgentGameState::class.java))).thenReturn(Mono.just("mocked"))

        val testSdkId = UUID.randomUUID().toString()
        // WebFlux의 non-blocking HTTP 요청 도구인 WebClient 구성
        val webClient = WebClient.create("http://localhost:$port")

        // ========================================================
        // Step 1: REST API를 이용해 테스트할 임의의 sdkId를 승인 목록에 등록
        // ========================================================
        val registrationResponse = webClient.post()
            .uri("/api/sdkId")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(SdkIdRegistrationRequest(testSdkId))
            .retrieve()
            .toEntity(String::class.java)
            .block(Duration.ofSeconds(5)) // REST 응답이 올 때까지 최대 5초 대기

        assertThat(registrationResponse?.statusCode?.is2xxSuccessful).isTrue()

        // ========================================================
        // Step 2: 웹소켓 모킹 클라이언트(Mock SDK Client) 구동 및 연결 시도
        // ========================================================
        val wsClient = ReactorNettyWebSocketClient()
        // 등록된 sdkId를 쿼리 파라미터로 붙여 웹소켓 URI 구성 (?sdkId=...)
        val wsUri = URI("ws://localhost:$port/ws/sdk?sdkId=$testSdkId")

        // 비동기 스레드간 상태 전이를 안전하게 조율하기 위한 Reactor Sinks 객체들
        val commandReceivedLatch = Sinks.one<CommandDto>() // 커맨드 정상 수신 여부 조율용

        val clientSessionMono = wsClient.execute(wsUri) { session ->
            // (1) 모킹 클라이언트가 연결되자마자 서버로 C# 클래스 구조 스캔 데이터(GAME_STATE)를 보냄
            val scanPayload = createSampleGameState()
            val scanMessage = session.textMessage(objectMapper.writeValueAsString(scanPayload))

            // 발송 작업을 수행하는 Mono (발송 완료 시 완료 신호 방출)
            val sender = session.send(Mono.just(scanMessage))

            // (2) 서버에서 전달하는 메시지 수신 리스너 정의 (raw CommandDto 수신 처리)
            val receiver = session.receive()
                .doOnNext { message ->
                    val payload = message.payloadAsText
                    try {
                        val command = objectMapper.readValue(payload, CommandDto::class.java)
                        // 커맨드를 정상 수신했음을 Sinks를 통해 테스트 스레드에 알림
                        commandReceivedLatch.tryEmitValue(command)
                    } catch (e: Exception) {
                        // 무시
                    }
                }
                .then() // 스트림이 닫힐 때까지 대기하도록 설정

            // 송신(sender)을 먼저 완료한 후, 수신 대기 상태(receiver)를 계속 유지하여 연결 소켓을 열어둠
            sender.then(receiver)
        }

        // 백그라운드 스레드에서 모킹 클라이언트 웹소켓 활성화
        val disposable = clientSessionMono.subscribe()

        // 클라이언트가 연결 수립 및 핸드셰이크를 완료할 수 있도록 잠깐(1.5초) 대기
        Thread.sleep(1500)

        // ========================================================
        // Step 3: 외부 트리거(REST API)를 호출하여 해당 sdkId 세션에 커맨드 푸시 유도
        // ========================================================
        val testCommand = CommandDto(
            className = "Player",
            methodName = "TakeDamage",
            variableName = "health",
            parameters = listOf(20)
        )

        val commandTriggerResponse = webClient.post()
            .uri("/api/orchestration/command/$testSdkId")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(testCommand)
            .retrieve()
            .toEntity(String::class.java)
            .block(Duration.ofSeconds(5))

        assertThat(commandTriggerResponse?.statusCode?.is2xxSuccessful).isTrue()

        // ========================================================
        // Step 4: 클라이언트가 정상적으로 커맨드를 받고, 결과를 리포트했는지 최종 단언(Assert)
        // ========================================================
        // 모킹 클라이언트가 커맨드를 성공적으로 가져갔는지 대기 후 확인
        val receivedCommand = commandReceivedLatch.asMono().block(Duration.ofSeconds(5))
        assertThat(receivedCommand).isNotNull
        assertThat(receivedCommand?.methodName).isEqualTo("TakeDamage")

        // 테스트 종료 후 백그라운드 모킹 소켓 세션 반환
        disposable.dispose()
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
}

