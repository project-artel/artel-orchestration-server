package kr.artel.orchestration.sdk.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService
import org.springframework.web.reactive.socket.server.WebSocketService
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy

/**
 * **웹소켓 상한이 판단의 결과라는 것을 지킨다** (ARTEL-542).
 *
 * 이 값이 없던 동안 Reactor Netty 기본값 65536이 걸렸고, 정상 동작이 그것을 밟았다 — stage에서
 * 전투 씬의 전량 판독 78,946바이트가 소켓을 끊었고, SDK는 스스로 다시 붙지 않으므로 그 런이
 * 거기서 끝났다.
 *
 * 지키는 것은 둘이다. 상한이 실제로 어댑터에 닿는가, 그리고 그 값이 알맞은 폭인가. 앞의 것이
 * 특히 쉽게 되돌아간다 — 어댑터를 인자 없이 만드는 한 줄이면 조용히 65536으로 돌아가고, 그때
 * 드러나는 자리는 여기가 아니라 큰 씬에 들어간 런이다.
 */
@ActiveProfiles("test")
@SpringBootTest
class SdkSocketPropertiesTest {

    @Autowired private lateinit var properties: SdkSocketProperties
    // 빈이 아니라 **어댑터가 실제로 든 것**을 본다. 이 줄이 이 파일에서 제일 중요하다 —
    // 전에는 `WebSocketService` 빈을 그대로 주입받았고, 그래서 빈이 옳게 만들어졌다는 것만
    // 확인하고 그 빈이 쓰이는지는 못 봤다. Spring 은 어댑터를 만들 때 그 빈을 조회하지 않는다.
    @Autowired private lateinit var adapter: WebSocketHandlerAdapter

    /**
     * 상한이 실제로 끊겼던 크기보다 크다.
     *
     * 숫자를 못 박지 않는 이유: 이 값은 실측을 따라 바뀔 수 있고, 그때마다 테스트를 고치게 하면
     * 테스트가 값의 사본이 될 뿐이다. 지키려는 것은 **정상 동작이 상한을 밟지 않는다**이고, 그
     * 기준이 stage에서 소켓을 끊은 그 크기다.
     */
    @Test
    fun `상한이 실제로 끊겼던 크기보다 크다`() {
        val brokeTheSocket = 78_946

        assertThat(properties.maxMessageBytes)
            .withFailMessage(
                "stage에서 %d바이트 판독이 소켓을 끊었다. 상한이 그보다 작으면 같은 일이 다시 난다",
                brokeTheSocket,
            )
            .isGreaterThan(brokeTheSocket)
    }

    /**
     * 그러면서 무한정 크지도 않다.
     *
     * 상한을 크게 열면 소켓은 안 끊기는 대신 에이전트가 못 읽는 판독이 통과한다. 렌더가 판독의
     * 26~32%이고 그 블록은 매 턴 새로 붙으므로(압축이 못 건드린다), 1MB짜리 판독은 턴당 약 9만
     * 토큰이 되어 런이 조용히 못 쓰게 된다 — 시끄럽게 끊기는 것보다 진단이 어렵다.
     */
    @Test
    fun `상한이 에이전트가 읽을 수 없는 크기까지 열려 있지 않다`() {
        assertThat(properties.maxMessageBytes).isLessThanOrEqualTo(512 * 1024)
    }

    /**
     * 그 값이 **Spring 이 실제로 쓰는** 업그레이드 전략까지 닿는다.
     *
     * 프로퍼티만 있고 연결이 빠지면 설정은 읽히는데 동작은 기본값이다. 그 상태는 코드를 읽어서는
     * 옳아 보이고, 큰 판독이 오는 날에만 드러난다.
     *
     * 이 테스트는 그 말을 적어 두고도 한 번 놓쳤다(ARTEL-682). `WebSocketService` 빈을 주입받아
     * 검사했는데, Spring 은 어댑터를 만들 때 그 빈을 조회하지 않는다 —
     * `WebFluxConfigurer.getWebSocketService()` 로만 받고 없으면 자기 것을 기본값으로 만든다.
     * 그래서 빈은 옳았고, 소켓은 3주 동안 65536이었고, 둘 다 참이었다.
     *
     * 그래서 묻는 대상을 바꿨다. 빈이 어떻게 생겼는지가 아니라 **어댑터가 무엇을 들고 있는지**다.
     */
    @Test
    fun `상한이 업그레이드 전략에 닿는다`() {
        val service = adapter.webSocketService
        assertThat(service).isInstanceOf(HandshakeWebSocketService::class.java)

        val strategy = (service as HandshakeWebSocketService).upgradeStrategy
        assertThat(strategy).isInstanceOf(ReactorNettyRequestUpgradeStrategy::class.java)
        assertThat((strategy as ReactorNettyRequestUpgradeStrategy).maxFramePayloadLength)
            .isEqualTo(properties.maxMessageBytes)
    }
}
