# orch 서버 내부 화면 스트리밍 프로토콜

Unity SDK가 보낸 WebRTC 영상을 orch JVM 프로세스에서 수신하고, orch가 별도의 WebRTC peer로 브라우저에 중계한다. 같은 JVM에서 H.264 MP4 segment를 인코딩해 S3에 저장한다. 별도 media-worker와 FFmpeg 자식 프로세스는 없다.

```text
SDK ── WebRTC ──▶ orch JVM ── WebRTC ──▶ 브라우저
                    │
                    └── JNI H.264 MP4 변환 ──▶ S3
```

시그널링은 기존 `/ws/sdk?token=&instanceId=`와 `/ws/viewer?instanceId=`를 사용한다. SDK 수신 peer와 viewer 송신 peer는 독립적이며 서로 다른 `streamId`를 사용한다. native 초기화·인코딩 오류가 나도 직접 SDK↔브라우저 P2P로 전환하지 않는다.

## 연결 식별자와 협상 순서

- `token`: SDK의 `aud=artel-sdk` JWT다. SDK handshake에서만 읽으며 브라우저에 전달하지 않는다.
- `instanceId`: 프로젝트에 속한 게임 인스턴스 ID다.
- `streamId`: 수신 ingress 또는 viewer peer를 구분한다. 재연결 시 새 ID를 만들고 이전 ID의 늦은 SDP·ICE·STOPPED를 무시한다.

SDK가 offer를 만들면 orch가 answer를 보낸다. 브라우저 쪽은 orch가 offer를 만들고 브라우저가 answer를 보낸다. 각 peer는 SDP를 먼저 보낸 뒤 모아둔 ICE 후보를 순서대로 전달한다. SDP보다 먼저 받은 원격 ICE는 최대 64개까지 기다린다.

## Viewer 접속과 임대

현재 공개 viewer admission은 인스턴스당 하나이며 가장 최근 접속이 기존 접속을 대체한다. 기존 viewer는 `4009`로 종료되며 자동 재접속하지 않는다. 프로젝트 멤버가 아니면 `4403`, 연결된 SDK가 없으면 `4404`다. 내부 peer 자원 상한과 공개 viewer admission 정책은 별개다.

브라우저는 10초마다 `RENEW`를 보내고 viewer registry가 접속 임대를 관리한다. 기본 임대는 90초이며 숨겨진 탭의 갱신 지연을 허용하도록 61초 미만 설정은 시작 시 거절한다.

orch는 viewer나 QA 녹화가 ingress를 필요로 하는 동안 SDK에 `STREAM_RENEW`를 보낸다. viewer가 종료돼도 QA 녹화가 진행 중이면 SDK 수신과 인코딩은 계속된다. QA 완료 뒤 5초가 지나고 viewer도 없으면 마지막 segment를 정리하고 `STREAM_STOP`을 보낸다. SDK 자체의 임대 타이머는 서버 장애·연결 유실 시 불필요한 송출을 끝낸다.

owner별 유지·종료 작업과 만료 객체 삭제는 분리되어, 한 인코더 종료나 S3 요청이 다른 SDK의 임대 갱신을 막지 않는다.

## 메시지

메시지는 JSON이며 `type`으로 구분한다. 아래 wire 필드는 기존 SDK·브라우저 계약을 유지한다.

### orch → SDK

| `type` | Fields |
|---|---|
| `STREAM_START` | `streamId`, `iceServers[]`, `video: { maxWidth, maxFramerate }`, `leaseSeconds` |
| `STREAM_RENEW` | `streamId` |
| `STREAM_STOP` | `streamId` |
| `WEBRTC_ANSWER` | `streamId`, `sdp` |
| `WEBRTC_ICE` | `streamId`, `candidate: { candidate, sdpMid, sdpMLineIndex }` |

새 STREAM_START는 SDK의 기존 송출 세션을 대체한다. iceServers는 orch 설정에서 전달한다.

### SDK → orch

| `type` | Fields |
|---|---|
| `WEBRTC_OFFER` | `streamId`, `sdp` |
| `WEBRTC_ICE` | `streamId`, `candidate` |
| `STREAM_STATE` | `streamId`, `state`, `error` (nullable) |

state 값은 CONNECTING, LIVE, FAILED, STOPPED다. 실패 상태는 orch의 재연결 판단에 반영한다.

### 브라우저 → orch

| `type` | Fields |
|---|---|
| `RENEW` | — |
| `STOP` | — |
| `WEBRTC_ANSWER` | `streamId`, `sdp` |
| `WEBRTC_ICE` | `streamId`, `candidate` |

### orch → 브라우저

| `type` | Fields |
|---|---|
| `STREAM_READY` | `streamId`, `iceServers[]` |
| `WEBRTC_OFFER` | `streamId`, `sdp` |
| `WEBRTC_ICE` | `streamId`, `candidate` |
| `STREAM_STATE` | `streamId`, `state`, `error` |
| `ERROR` | `code`, `message` |

## 접속 종료 코드와 인증

| 코드 | 대상 | 의미 |
| --- | --- | --- |
| `4001` | SDK | 인증 정보 누락·오류 또는 접근할 수 없는 인스턴스 |
| `4002` | SDK | 같은 인스턴스의 SDK가 이미 연결됨 |
| `4003` | viewer | 잘못된 instanceId |
| `4009` | viewer | 새로운 viewer 접속으로 대체됨 |
| `4403` | viewer | 프로젝트 멤버가 아님 |
| `4404` | viewer | 연결된 SDK가 없음 |

viewer handshake는 기존 `artel_access_token` 쿠키로 인증한다. viewer의 query string에는 토큰을 넣지 않는다. 쿠키의 SameSite 정책에 맞게 프런트엔드와 서버를 배포한다.

## 실행 설정과 녹화

`ARTEL_STREAM_ENABLED`, `ARTEL_STREAM_LEASE_SECONDS`, `ARTEL_STUN_URLS`, `ARTEL_TURN_URL`, `ARTEL_TURN_USERNAME`, `ARTEL_TURN_CREDENTIAL`을 사용한다. 외부 네트워크에서 직접 연결할 수 없으면 도달 가능한 TURN 서버가 필요하다.

별도 worker URL은 설정하지 않는다. native 의존성과 임시 파일 경로, 프레임·연결·업로드 상한, 종료 및 30일 보관 정책은 [orch 내부 영상 중계·저장](qa-video-recording.md)을 따른다. 새로운 공개 HTTP 업로드 경로 없이 기존 `DocumentStorage`에 직접 저장하고, 프로젝트 멤버에게만 재생 URL을 제공한다.
