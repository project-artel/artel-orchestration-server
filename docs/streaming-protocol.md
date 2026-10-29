# orch 서버 내부 화면 스트리밍 프로토콜

Unity SDK가 JPEG frame을 기존 인증된 WebSocket으로 orch에 전달한다. orch JVM 프로세스가 브라우저의 WebSocket으로 frame을 중계하고, 같은 JVM에서 H.264 MP4 segment를 인코딩해 S3에 저장한다. 별도 media-worker와 FFmpeg 자식 프로세스는 없다.

```text
SDK ── /ws/sdk · JPEG_WS ──▶ orch JVM ── /ws/viewer · JPEG_WS ──▶ 브라우저
                               │
                               └── JNI H.264 MP4 변환 ──▶ S3
```

메시지는 JSON이며 `type`으로 구분한다. 영상도 기존 `/ws/sdk?token=&instanceId=`와 `/ws/viewer?instanceId=` 연결을 사용한다. `JPEG_WS`는 SDP·ICE 협상을 하지 않으며, 별도 UDP 포트나 STUN·TURN 서버를 요구하지 않는다. SDK ingress와 viewer의 `streamId`는 서로 다르다.

## 연결 식별자와 시작 순서

- `token`: SDK의 `aud=artel-sdk` JWT다. SDK handshake에서만 읽으며 브라우저에 전달하지 않는다.
- `instanceId`: 프로젝트에 속한 게임 인스턴스 ID다.
- `streamId`: SDK ingress 또는 viewer 연결을 구분한다. 재연결 시 새 ID를 만들고 이전 ID의 늦은 상태·frame·ACK를 무시한다. 같은 인스턴스라도 SDK WebSocket이 교체되면 기존 연결의 frame을 새 연결에 적용하지 않는다.
- `frameId`: ingress 안에서 증가하는 frame 번호다. 중복·과거 번호를 새 영상으로 처리하지 않는다. viewer로 중계할 때도 이 번호를 사용한다.

orch가 `transport: "JPEG_WS"`인 `STREAM_START`를 보내면 SDK가 같은 transport를 담은 `STREAM_STATE CONNECTING`으로 지원 여부를 알리고 frame을 보낸다. 10초 안에 지원 여부를 확인하지 못하거나 구 SDK가 `WEBRTC_OFFER`·`WEBRTC_ICE`를 보내면 `SDK_UPDATE_REQUIRED` 오류를 남긴다. SDP·ICE로 자동 전환하지 않는다. 서버·SDK·home을 함께 업데이트해야 한다.

## Viewer 접속과 임대

같은 게임 인스턴스를 최대 8개 viewer가 동시에 시청할 수 있다. 각 접속의 프로젝트 멤버 권한을 확인하고 별도 viewer ID와 전송 대기열을 부여한다. 새 접속이 기존 viewer를 대체하지 않는다. `ARTEL_MEDIA_MAX_VIEWERS` 상한을 넘으면 새 접속만 `1013`으로 종료하고 기존 접속은 유지한다. 프로젝트 멤버가 아니면 `4403`, 연결된 SDK가 없으면 `4404`다.

브라우저는 10초마다 `RENEW`를 보내고 viewer registry가 접속 임대를 관리한다. 기본 임대는 90초이며 숨겨진 탭의 갱신 지연을 허용하도록 61초 미만 설정은 시작 시 거절한다.

orch는 viewer가 하나라도 남거나 QA 녹화가 ingress를 필요로 하는 동안 SDK에 `STREAM_RENEW`를 보낸다. 한 viewer가 종료되면 해당 접속만 정리한다. 모든 viewer가 종료돼도 QA 녹화가 진행 중이면 SDK 수신과 인코딩은 계속된다. QA 완료 뒤 5초가 지나고 viewer도 없으면 마지막 segment를 정리하고 `STREAM_STOP`을 보낸다. SDK 자체의 임대 타이머는 서버 장애·연결 유실 시 불필요한 송출을 끝낸다.

SDK 송출이 재시작되면 등록된 모든 viewer에 각자의 ID로 `STREAM_READY`를 다시 보내고 새 ingress에 연결한다. 이전 소켓의 늦은 close는 다른 viewer나 같은 ID로 새로 등록된 소켓을 제거하지 않는다.

owner별 유지·종료 작업과 만료 객체 삭제는 분리되어, 한 인코더 종료나 S3 요청이 다른 SDK의 임대 갱신을 막지 않는다.

## 메시지

### orch → SDK

| `type` | 필드 |
| --- | --- |
| `STREAM_START` | `streamId`, `transport: "JPEG_WS"`, `iceServers: []`, `video: { maxWidth, maxHeight, maxFramerate, jpegQuality, maxFrameBytes }`, `leaseSeconds` |
| `STREAM_RENEW` | `streamId` |
| `STREAM_STOP` | `streamId` |
| `STREAM_FRAME_ACK` | `streamId`, `frameId`, `accepted` |

새 `STREAM_START`는 SDK의 기존 송출 세션을 대체한다. 기본 video 값은 최대 1280×720, 10fps, JPEG quality 65, frame당 131072 bytes다. 실제 화면의 비율에 맞춰 이 범위 안에서 capture한다. `iceServers`는 기존 필드 모양만 유지하며 빈 목록으로 전달한다.

### SDK → orch

| `type` | 필드 |
| --- | --- |
| `STREAM_STATE` | `streamId`, `transport: "JPEG_WS"`, `state`, `error` (nullable) |
| `STREAM_FRAME` | `streamId`, `frameId`, `jpeg` |

`state` 값은 CONNECTING, LIVE, FAILED, STOPPED다. 실패 상태는 orch의 재연결 판단에 반영한다. `jpeg`는 JPEG byte를 base64로 인코딩한 문자열이다. frame JSON의 UTF-8 크기는 184 KiB, base64를 풀어 얻은 JPEG는 128 KiB 이하여야 한다. 기존 SDK WebSocket의 메시지 합계 상한 256 KiB를 올리지 않는다.

서버는 JPEG header에서 형식과 해상도를 확인한 뒤 녹화 대기열과 현재 viewer에 전달한다. 최대 header 해상도는 1920×1080이며 녹화 대기열은 별도의 메모리 상한도 적용한다. 전체 JPEG decode와 H.264 encode는 인코더 thread가 맡는다. 서버 수신 시각과 monotonic 시각으로 영상 시간축을 기록하며 SDK가 임의로 보낸 시각을 기준으로 삼지 않는다.

### 브라우저 → orch

| `type` | 필드 |
| --- | --- |
| `RENEW` | — |
| `STOP` | — |

브라우저는 frame을 받아 화면에 표시하며 WebRTC answer나 ICE를 보내지 않는다. 동시에 decode하는 frame을 하나로 제한하고 늦은 decode 결과와 object URL을 정리한다. 첫 frame 전에는 연결 대기로 표시하고, 갱신이 멈추면 마지막 화면을 현재 영상으로 표시하지 않는다.

### orch → 브라우저

| `type` | 필드 |
| --- | --- |
| `STREAM_READY` | `streamId`, `transport: "JPEG_WS"`, `iceServers: []` |
| `STREAM_FRAME` | `streamId` (viewer ID), `frameId`, `jpeg` |
| `STREAM_ERROR` | `streamId` (viewer ID), `code: "SDK_UPDATE_REQUIRED"` |
| `ERROR` | `code`, `message` |

`STREAM_READY`는 viewer 연결과 전송 방식의 준비를 뜻한다. 첫 영상 frame 수신이나 S3 저장 완료를 뜻하지 않는다. SDK의 ingress ID를 viewer에게 그대로 적용하지 않고 현재 viewer ID로 frame을 보낸다.

## ACK와 대기열 상한

SDK는 frame 하나를 보낸 뒤 같은 stream과 frame ID의 `STREAM_FRAME_ACK`를 기다린다. 기다리는 동안 과거 화면을 쌓지 않고 다음 capture 시점의 최신 화면을 사용한다. `accepted: false`도 다음 frame을 보낼 수 있는 수신 확인이다. ACK를 10초 동안 받지 못하면 SDK가 실패를 보고하고 orch가 새 stream ID로 복구한다.

ACK는 서버의 frame 검증·전송률 확인 결과다. 녹화 완료나 S3 저장 성공의 확인이 아니다. 녹화 대기열이 가득 차도 viewer를 갱신하고 `accepted: true`로 다음 capture를 허용할 수 있다. 잘못된 frame, 중복·과거 ID, 전송률 초과는 거부한다. 서버는 SDK의 자율 제한과 별도로 초당 10개, 순간 burst 2개를 제한하며 초과 frame을 추가 decode하지 않는다.

SDK ACK와 viewer frame은 각 source에 최신 pending 값 하나만 보관한다. 구독 전에도 하나만 남기고 각 merge 단계의 prefetch는 1로 제한한다. source pending 외에 각 단계의 전송 중 값은 있을 수 있으나 과거 frame 이력을 계속 보관하지 않는다. viewer control queue는 16개·64 KiB로 제한한다. 느린 viewer가 SDK 수신이나 녹화를 기다리게 하지 않는다.

녹화 대기열은 처리 중인 frame까지 합쳐 3개·8 MiB 중 먼저 도달하는 상한을 사용한다. 메모리 계상은 `width × height × 8 + JPEG bytes`다. 최대 해상도 안에 있어도 이 메모리 상한을 넘으면 해당 frame을 녹화 대기열에 넣지 않고 gap을 기록한다. 업로드 thread는 서버 전체 2개, 대기와 처리 중인 segment는 합계 10개·256 MiB 이하다.

## 접속 종료 코드와 인증

| 코드 | 대상 | 의미 |
| --- | --- | --- |
| `4001` | SDK | 인증 정보 누락·오류 또는 접근할 수 없는 인스턴스 |
| `4002` | SDK | 같은 인스턴스의 SDK가 이미 연결됨 |
| `4003` | viewer | 잘못된 instanceId |
| `1013` | viewer | 동시 viewer 상한 초과 또는 해당 viewer의 control queue 포화 |
| `4403` | viewer | 프로젝트 멤버가 아님 |
| `4404` | viewer | 연결된 SDK가 없음 |

viewer handshake는 기존 `artel_access_token` 쿠키로 인증한다. viewer의 query string에는 토큰을 넣지 않는다. 쿠키의 SameSite 정책에 맞게 프런트엔드와 서버를 배포한다.

## 실행 설정과 녹화

`ARTEL_STREAM_ENABLED`, `ARTEL_STREAM_LEASE_SECONDS`, `ARTEL_STREAM_MAX_WIDTH`, `ARTEL_STREAM_MAX_FRAMERATE`와 기존 orch의 S3 설정을 사용한다. 현재 `JPEG_WS` 경로에는 STUN·TURN 설정이나 별도 미디어 포트가 필요 없다.

native 인코더와 임시 파일 경로, 연결·프레임·업로드 상한, 종료 및 30일 보관 정책은 [orch 내부 영상 중계·저장](qa-video-recording.md)을 따른다. 새로운 공개 HTTP 업로드 경로 없이 기존 `DocumentStorage`에 직접 저장하고, 프로젝트 멤버에게만 재생 URL을 제공한다.

이전 WebRTC·worker 구현의 검증 수치는 현재 전송 방식의 증거가 아니다. 실제 Unity 화면 방향과 최종 통합 검증 결과는 [통합 검증 기록](qa-tc-evaluation-verification.md)에서 구분해 확인한다.
