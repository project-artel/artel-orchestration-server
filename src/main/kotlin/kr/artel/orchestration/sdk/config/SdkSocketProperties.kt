package kr.artel.orchestration.sdk.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * SDK·뷰어 웹소켓이 한 메시지로 받아들이는 최대 크기.
 *
 * **이 값이 없던 동안 Reactor Netty 기본값 65536이 그대로 걸렸고, 정상 동작이 그것을 밟았다.**
 * stage에서 게임이 전투 씬에 들어가는 순간 전량 판독 78,946바이트가 나갔고 서버가 소켓을 끊었다.
 *
 * ```
 * io.netty.handler.codec.TooLongFrameException: content length exceeded 65536 bytes.
 *   at io.netty.handler.codec.MessageAggregator.handleOversizedMessage
 * ```
 *
 * SDK에는 `1002 Protocol error`로 도착한다. SDK는 스스로 다시 붙지 않으므로(ARTEL-527) 그 런은
 * 거기서 끝난다. 넘긴 게임은 객체 마흔 개짜리 샘플이었다.
 *
 * ## 무엇에 맞춰 잡았나
 *
 * 상한을 정하는 축이 셋인데 제일 낮은 것이 결정한다.
 *
 * | | 한계 |
 * |---|---|
 * | 서버 메모리 | 이 값 × 동시 SDK 연결 수. 1MB에 인스턴스 쉰이면 50MB로 JVM에서 신경 쓸 규모가 아니다 |
 * | 웹소켓 전송 | 사실상 없다 |
 * | **에이전트 컨텍스트** | **여기가 벽이다** |
 *
 * 판독은 프롬프트로 들어간다. 실측에서 렌더가 판독 JSON의 26~32%였고, 그 블록은 매 턴 새로 붙는다
 * — 압축이 못 건드린다(그래프 상태가 아니라 호출마다 만들어진다). 그래서 1MB짜리 판독은 턴당 약
 * 9만 토큰이 되어 에이전트가 읽을 수가 없다.
 *
 * **그래서 상한을 무한정 올리지 않는다.** 올리면 소켓은 안 끊기는 대신 런이 조용히 못 쓰게 되고,
 * 그것은 지금처럼 시끄럽게 끊기는 것보다 진단이 어렵다.
 *
 * 256KB는 실측한 최대(전투 전량 78,946바이트)의 세 배이고, 렌더로 치면 턴당 2만 토큰쯤이다.
 * 그보다 큰 판독이 오면 그것은 **정상 동작이 아니라 신호**다 — 상한이 방어선으로 돌아온다.
 *
 * ## 이 값이 SDK 쪽 절감을 대신하지 않는다
 *
 * ARTEL-540이 판독에서 아무도 안 읽는 것을 걷어내 최대 문서를 44,470바이트로 내렸다. 상한을
 * 올리는 것만으로는 게임이 커질 때 다시 걸린다 — 그쪽이 크기를 유계로 만드는 일이고 이쪽은
 * 그 위에 두는 여유다.
 */
@ConfigurationProperties(prefix = "artel.sdk.socket")
data class SdkSocketProperties(
    /**
     * 한 메시지의 최대 바이트.
     *
     * Spring은 이 값을 프레임 상한이자 **합계 상한**으로 함께 쓴다. SDK가 쓰는 websocket-sharp은
     * 1016바이트를 넘는 메시지를 조각내 보내므로 프레임 하나는 언제나 작고, 실제로 걸리는 것은
     * 합쳐진 크기다.
     */
    val maxMessageBytes: Int = 256 * 1024,
    /**
     * 서버가 SDK 세션에 ping frame 을 보내는 주기. `SdkHeartbeat` 참고.
     */
    val pingInterval: Duration = Duration.ofSeconds(15),
    /**
     * 이 시간 동안 아무 frame 도 받지 못한 SDK 세션을 닫는다.
     *
     * ping 주기의 세 배다. pong 을 두 번 놓쳐도 닫지 않는다. 너무 길게 잡으면 끊긴 세션이 그만큼
     * 연결됨으로 남고, SDK 의 재연결도 그만큼 거절된다(ARTEL-1111).
     *
     * 검사는 ping 을 보낼 때만 하므로, 끊긴 뒤 닫힐 때까지는 최대 이 값에 [pingInterval] 을 더한
     * 60초가 걸린다.
     */
    val idleTimeout: Duration = Duration.ofSeconds(45),
    /**
     * 같은 인스턴스의 새 연결이 왔을 때 앞 세션의 답을 기다리는 시간(ARTEL-1234). 이 안에 frame 이 오지 않으면 앞
     * 세션을 닫고 새 연결을 받는다.
     *
     * 살아 있는 세션의 pong 은 로컬에서 수 ms 안에 온다. 2초는 그보다 충분히 길고, SDK 가 재연결을 다시 시도하기
     * 전에 끝난다.
     */
    val replaceProbeTimeout: Duration = Duration.ofSeconds(2),
) {
    init {
        require(idleTimeout > pingInterval) {
            "artel.sdk.socket.idle-timeout($idleTimeout) 은 ping-interval($pingInterval) 보다 길어야 한다. 아니면 pong 을 기다리기 전에 세션을 닫는다."
        }
    }
}
