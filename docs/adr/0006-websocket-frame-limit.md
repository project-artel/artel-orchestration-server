# ADR 0006 — WebSocket 메시지 상한을 256 KB 로 두고 `WebFluxConfigurer` 로 적용한다

- 상태: 확정 (ARTEL-542 가 값을 정하고 ARTEL-682 가 실제로 걸리게 함)
- 근거: `sdk/config/SdkSocketProperties.kt`, `sdk/config/WebSocketConfig.kt`,
  `src/test/kotlin/kr/artel/orchestration/sdk/config/SdkSocketPropertiesTest.kt`

## 결정

- `/ws/sdk` 와 `/ws/viewer` 가 한 메시지로 받아들이는 최대 크기를 **256 KB** 로 둡니다 —
  `artel.sdk.socket.max-message-bytes`, 코드 기본값 `256 * 1024`,
  `application.yml` 에서 `${ARTEL_SDK_SOCKET_MAX_MESSAGE_BYTES:262144}`.
- 그 값을 `@Bean WebSocketService` 가 아니라 `WebFluxConfigurer.getWebSocketService()` 오버라이드로
  적용합니다.

```kotlin
override fun getWebSocketService(): WebSocketService {
    val strategy = ReactorNettyRequestUpgradeStrategy {
        WebsocketServerSpec.builder().maxFramePayloadLength(socketProperties.maxMessageBytes)
    }
    return HandshakeWebSocketService(strategy)
}
```

- Spring 은 이 값을 프레임 상한이자 **합계 상한**으로 함께 씁니다. SDK 가 쓰는 websocket-sharp 은
  1016 바이트를 넘는 메시지를 조각내 보내므로 프레임 하나는 언제나 작고, 실제로 걸리는 것은 합쳐진
  크기입니다.

## 왜

- 이 값이 없던 동안 Reactor Netty 기본값 **65536** 이 그대로 걸렸고, 정상 동작이 그 값을 넘겼습니다.
- stage 에서 게임이 전투 씬에 들어가는 순간 `whole` 이 `true` 인 `pulse` 78,946 바이트가 나갔고 서버가
  소켓을 끊었습니다.

```text
io.netty.handler.codec.TooLongFrameException: content length exceeded 65536 bytes.
  at io.netty.handler.codec.MessageAggregator.handleOversizedMessage
```

- SDK 에는 `1002 Protocol error` 로 도착합니다. SDK 는 스스로 다시 붙지 않으므로(ARTEL-527) 그 런은
  거기서 끝납니다.
- 넘긴 게임은 객체 마흔 개짜리 샘플이었습니다. 큰 게임이 아니라 평범한 전투 화면이었습니다.

### 상한을 정하는 축은 셋이고 제일 낮은 것이 결정한다

| 축 | 한계 |
| --- | --- |
| 서버 메모리 | 상한 × 동시 SDK 연결 수. 1 MB 에 인스턴스 쉰이면 50 MB 로 JVM 에서 신경 쓸 규모가 아님 |
| WebSocket 전송 | 사실상 없음 |
| agent 컨텍스트 | **여기가 벽** |

- `pulse` 는 프롬프트로 들어갑니다. 실측에서 render 가 `pulse` JSON 의 26~32% 였고, 그 블록은 매 턴 새로
  붙습니다 — compaction 이 못 건드립니다. 그래프 상태가 아니라 호출마다 만들어지기 때문입니다.
- 그래서 1 MB 짜리 `pulse` 는 **턴당 약 9 만 토큰**이 되어 agent 가 읽을 수가 없습니다.
- **상한을 무한정 올리지 않는 이유가 이것입니다.** 올리면 소켓은 안 끊기는 대신 런이 조용히 못 쓰게 되고,
  그것은 지금처럼 시끄럽게 끊기는 것보다 진단이 어렵습니다.
- 256 KB 는 실측한 최대(78,946 바이트)의 세 배이고, render 로 치면 턴당 2 만 토큰쯤입니다. 그보다 큰
  `pulse` 가 오면 그것은 정상 동작이 아니라 신호이고, 상한이 방어선으로 돌아옵니다.

### `@Bean` 이 아니라 오버라이드인 이유

- ARTEL-542 가 256 KB 를 정해 두었지만 그 값이 소켓에 닿은 적이 없었습니다. `WebSocketService` 를
  `@Bean` 으로 냈는데 **그 빈이 한 번도 쓰이지 않았습니다.**

```text
WebFluxConfigurationSupport
  public    WebSocketHandlerAdapter webFluxWebSocketHandlerAdapter()
  protected WebSocketService        getWebSocketService()
```

- 어댑터는 컨텍스트에서 `WebSocketService` 빈을 조회하지 않습니다. `getWebSocketService()` 로만 받고,
  아무도 그것을 구현하지 않으면 기본 `HandshakeWebSocketService` 를 스스로 만듭니다 — 우리
  `WebsocketServerSpec` 이 없는 것으로.
- 빈은 만들어지고, 주입되지 않고, 조용합니다. **상한은 3 주 내내 65536 이었습니다.** ARTEL-678 이
  `pulse` 에 화면의 글자를 실어 전투 씬의 `whole` `pulse` 가 그 값을 넘긴 날에야 드러났습니다.
- 그래서 회귀를 잡는 테스트는 빈이 아니라 어댑터를 봅니다 — 실제 `WebSocketService` 가
  `HandshakeWebSocketService` 인지, 그 `upgradeStrategy` 의 `maxFramePayloadLength` 가 설정값과 같은지.
- **어댑터를 직접 만드는 것은 여전히 하지 않습니다.** 같은 이름의 빈이 둘이 되어 기동이 거절되거나, 다른
  이름으로 두면 어느 쪽이 쓰이는지가 등록 순서에 달립니다.

## 거절한 대안

| 대안 | 왜 거절했나 |
| --- | --- |
| 상한을 1 MB 이상으로 크게 잡는다 | 소켓은 안 끊기지만 턴당 약 9 만 토큰이 되어 런이 조용히 못 쓰게 됨 |
| `@Bean WebSocketService` 를 고쳐 쓴다 | 어댑터가 그 빈을 조회하지 않음. 고칠 데가 아니라 닿지 않는 자리 |
| `WebSocketHandlerAdapter` 를 직접 만든다 | 빈 이름이 겹치면 기동 거절, 안 겹치면 등록 순서에 달림 |
| `/ws/sdk` 와 `/ws/viewer` 의 상한을 나눈다 | 값이 둘이 되고 한쪽만 고쳐지는 날이 옴 |
| SDK 쪽 `pulse` 크기 절감만으로 해결한다 | 필요하지만 충분하지 않음. 상한이 없으면 게임이 커질 때 다시 걸림 |

- 뷰어가 보내는 것은 갱신과 signalling 뿐이라 이 상한이 필요하지 않습니다. 뷰어에 더 낮은 상한이
  필요해지면 그때 갈라야 할 이유가 생깁니다.
- ARTEL-540 이 `pulse` 에서 아무도 안 읽는 것을 걷어내 최대 문서를 44,470 바이트로 내렸습니다. 그쪽이
  크기에 상한을 두는 일이고 이 상한은 그 위에 두는 여유입니다.

## 대가

| 대가 | 무엇이 일어나나 |
| --- | --- |
| 설정을 한 자리에만 둘 수 있음 | `WebFluxConfigurer` 빈이 이 앱에 둘인데 이 메서드는 **하나만** 값을 내야 함. 둘이 내면 기동 거절 |
| 두 경로가 상한 하나를 나눠 씀 | 뷰어에게는 과한 값이지만 값이 둘이 되는 것보다 나음 |
| 256 KB 도 언젠가 작아짐 | 상한은 `pulse` 크기 자체를 줄이지 않음. SDK 쪽 절감이 그 일을 함 |
| 프레임이 아니라 합계에 걸림 | websocket-sharp 이 1016 바이트마다 조각내므로 프레임 상한만 보면 원인이 안 보임 |

- `WebFluxConfigurerComposite` 가 합성을 맡고, `WebFluxArgumentResolverConfig` 가 또 하나의
  `WebFluxConfigurer` 입니다. WebSocket 설정을 `WebSocketConfig` 한 곳에 둔 이유가 그것입니다.
- 두 경로는 한 `HandlerMapping` 에 함께 들어 있습니다. 매핑 빈을 나눠 등록하면 같은 순위를 가진
  `HandlerMapping` 이 둘이 되어 어느 쪽이 먼저 조회되는지가 등록 순서에 달립니다.
- 스트리밍이 꺼져 있으면 `/ws/viewer` 를 아예 매핑하지 않습니다. 핸들러 안에서 거절하면 꺼진 기능을 향해
  브라우저가 계속 재연결을 시도합니다.
