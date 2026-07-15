package kr.artel.orchestration

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.sdk.controller.SdkIdRegistrationRequest
import kr.artel.orchestration.sdk.dto.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.net.URI
import java.time.Duration
import java.util.*

/**
 * Spring WebFlux 기반 웹소켓의 전체 시나리오를 검증하기 위한 통합 테스트 클래스
 * 실제 랜덤 포트(RANDOM_PORT)로 웹서버를 기동하여 테스트를 돌립니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ArtelWebSocketIntegrationTest {

    // 스프링이 랜덤으로 띄운 내장 Netty 서버의 포트를 주입받음
    @LocalServerPort
    private val port: Int = 0

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    /**
     * 웹소켓 연결수립 -> 스캔데이터 전송 -> 커맨드 푸시 -> 결과 리포팅 전체 흐름을 테스트합니다.
     */
    @Test
    fun testWebSocketBidirectionalFlow() {
        val testSdkId = UUID.randomUUID().toString()
        // WebFlux의 non-blocking HTTP 요청 도구인 WebClient 구성
        val webClient = WebClient.create("http://localhost:$port")

        // ========================================================
        // Step 1: REST API를 이용해 테스트할 임의의 sdkId를 승인 목록에 등록
        // ========================================================
        val registrationResponse = webClient.post()
            .uri("/api/sdk-ids")
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
        val reportCompletedLatch = Sinks.one<Void>()       // 리포트 정상 발송 여부 조율용

        val clientSessionMono = wsClient.execute(wsUri) { session ->
            // (1) 모킹 클라이언트가 연결되자마자 서버로 C# 클래스 구조 스캔 데이터(SCAN)를 보냄
            val scanPayload = ClassMetadata(
                className = "Player",
                variables = listOf(VariableMetadata("health", "Double")),
                methods = listOf(MethodMetadata("TakeDamage", listOf(ParameterMetadata("Int", "amount"))))
            )
            val scanEnvelope = WebSocketEnvelope(
                type = MessageType.SCAN,
                payload = objectMapper.writeValueAsString(scanPayload)
            )
            val scanMessage = session.textMessage(objectMapper.writeValueAsString(scanEnvelope))

            // 발송 작업을 수행하는 Mono (발송 완료 시 완료 신호 방출)
            val sender = session.send(Mono.just(scanMessage))

            // (2) 서버에서 전달하는 메시지 수신 리스너 정의
            val receiver = session.receive()
                .doOnNext { message ->
                    val payload = message.payloadAsText
                    val envelope = objectMapper.readValue(payload, WebSocketEnvelope::class.java)
                    
                    // 수신 메시지가 COMMAND 타입인 경우 처리
                    if (envelope.type == MessageType.COMMAND) {
                        val command = objectMapper.readValue(envelope.payload, CommandDto::class.java)
                        // 커맨드를 정상 수신했음을 Sinks를 통해 테스트 스레드에 알림
                        commandReceivedLatch.tryEmitValue(command)

                        // (3) 받은 커맨드를 토대로 테스트 결과 보고서(REPORT)를 생성하여 서버로 회신
                        val report = ReportDto(
                            className = command.className,
                            methodName = command.methodName,
                            variableName = command.variableName,
                            beforeValue = 100.0,
                            afterValue = 80.0
                        )
                        val reportEnvelope = WebSocketEnvelope(
                            type = MessageType.REPORT,
                            payload = objectMapper.writeValueAsString(report)
                        )
                        val reportMessage = session.textMessage(objectMapper.writeValueAsString(reportEnvelope))
                        
                        // 결과 보고 메시지 전송 후, 정상 발송 완료 신호를 방출
                        session.send(Mono.just(reportMessage))
                            .doOnSuccess { reportCompletedLatch.tryEmitEmpty() }
                            .subscribe()
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

        // 클라이언트가 회신한 리포트가 서버에 전송 완료되었는지 대기 후 최종 확인
        reportCompletedLatch.asMono().block(Duration.ofSeconds(5))

        // 테스트 종료 후 백그라운드 모킹 소켓 세션 반환
        disposable.dispose()
    }
}
