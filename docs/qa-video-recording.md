# orch 내부 영상 중계와 TC 검증 시도 저장

SDK 영상은 기존 인증된 WebSocket으로 orch 서버에 전달한다. orch JVM 프로세스가 브라우저에 프레임을 중계하고 H.264 MP4로 변환해 S3에 저장한다. 별도 media-worker, Python 서버, 미디어 컨테이너나 FFmpeg 자식 프로세스는 사용하지 않는다.

## 실행 설정

기존 orch 서버만 배포한다. 영상 전송은 `JPEG_WS`이며 SDK의 `/ws/sdk`와 브라우저의 `/ws/viewer`를 사용한다. 영상용 UDP 포트나 STUN·TURN 서버를 요구하지 않는다. `JavaCV 1.5.14`와 `FFmpeg 8.1.2-1.5.14`의 Linux GPL native 라이브러리를 같은 JVM에 로드해 H.264 MP4를 인코딩한다. WebRTC peer와 libwebrtc는 현재 영상 전송 경로에 사용하지 않는다.

| 설정 | 기본값 | 용도 |
| --- | --- | --- |
| `ARTEL_STREAM_ENABLED` | `true` | orch 내부 스트리밍 활성화 |
| `ARTEL_STREAM_MAX_WIDTH` | `1280` | SDK capture의 최대 너비. 서버가 최대 1280으로 제한 |
| `ARTEL_STREAM_MAX_FRAMERATE` | `10` | SDK capture의 최대 초당 프레임 수. 서버가 최대 10으로 제한 |
| `ARTEL_MEDIA_DIRECTORY` | `./data/qa-video` | MP4 segment 임시 저장 경로 |
| `ARTEL_MEDIA_MAX_INGRESS` | `8` | 종료 중인 연결을 포함한 동시 SDK ingress 상한 |
| `ARTEL_MEDIA_MAX_VIEWERS` | `8` | 같은 게임 인스턴스의 동시 viewer 상한 |

기본 capture 범위는 최대 1280×720, JPEG quality 65다. JPEG 한 장은 128 KiB, `STREAM_FRAME` JSON은 184 KiB로 제한한다. 기존 SDK WebSocket의 전체 메시지 상한 256 KiB는 유지한다. `ARTEL_STUN_URLS`·`ARTEL_TURN_*` 설정은 현재 `JPEG_WS` 전송에 사용하지 않으며 wire의 `iceServers`는 빈 목록이다.

임시 디렉터리는 orch 실행 사용자가 쓸 수 있어야 한다. 인코더 초기화나 저장에 실패해도 API 서버와 QA 실행은 계속하며 오류·누락 구간을 남긴다. S3는 기존 `DocumentStorage` 설정을 사용하며 객체 prefix는 `qa-video/`다. 브라우저에는 프로젝트 접근 확인 후 짧은 유효기간의 재생 URL을 발급한다.

## 중계·변환과 자원 상한

`InProcessMediaService`는 현재 SDK 연결과 stream ID를 확인하고 JPEG header의 형식·크기를 검사한다. 서버는 초당 10개, 순간 burst 2개의 frame 제한을 적용한다. SDK는 frame 하나를 보낸 뒤 `STREAM_FRAME_ACK`를 기다리며, ACK 전에는 새 frame을 보내지 않는다. ACK는 검증과 전송률 확인 결과이며 인코딩·S3 저장 성공을 보장하지 않는다. 녹화 대기열이 가득 차도 viewer 전달은 계속할 수 있다.

`QaMediaRelay`는 viewer ID 집합과 QA 녹화의 소유권을 따로 관리한다. 같은 게임을 여러 viewer가 동시에 시청하며 각자의 프로젝트 접근 권한을 확인한다. 상한을 넘는 새 viewer만 `1013`으로 거절하고 기존 시청자는 유지한다. 한 viewer가 종료돼도 다른 viewer나 QA 녹화가 영상을 필요로 하면 수신과 녹화는 계속된다. 재연결 시 새 ingress ID를 만들고 등록된 모든 viewer를 다시 연결한다. 이전 연결의 늦은 상태·frame·ACK·close를 새 연결이나 다른 viewer에 적용하지 않는다.

SDK ACK와 viewer frame은 source마다 최신 pending 값 하나만 보관한다. 구독 전에도 하나만 남기며, 각 merge 단계의 prefetch는 1로 제한한다. source의 pending 외에 각 단계의 전송 중 값은 있을 수 있으나 frame 이력을 계속 쌓지 않는다. viewer control queue는 별도로 16개·64 KiB까지 보관한다. 느린 viewer가 SDK 수신·녹화나 다른 ingress를 기다리게 하지 않는다.

`QaVideoRecorder`는 ingress당 인코딩 thread 1개와 FFmpeg thread 1개를 사용한다. JPEG decode와 H.264 encode는 인코더 thread에서 수행한다. 대기 중인 frame과 처리 중인 frame을 합쳐 3개·8 MiB 중 먼저 도달하는 상한으로 제한한다. 각 frame의 메모리는 `width × height × 8 + JPEG bytes`로 계산해 decode raster와 converter surface를 함께 계상한다. 서버가 수용하는 JPEG header의 최대 크기는 1920×1080이며, 이 범위 안이어도 메모리 상한을 초과하면 녹화 대기열에 넣지 않는다.

MP4 segment는 최대 60초·32 MiB로 나눈다. 업로드 thread는 서버 전체 2개이며, 대기와 처리 중인 업로드를 합쳐 10개·256 MiB로 제한한다. 업로드는 3회 시도하고 실패하거나 대기열이 포화되면 해당 구간을 gap으로 남긴다. QA 전체 영상을 메모리에 누적하지 않는다.

프레임의 서버 수신 시각과 monotonic 시각을 함께 기록하고 도착 간격을 MP4 PTS에 반영한다. 입력 정지나 frame drop 시간을 프레임 수로 압축하지 않는다. 재연결·해상도 변경은 새 segment를 시작한다.

완성한 segment는 `QaRecordingStore`를 통해 기존 검증·멱등성 규칙으로 등록하고 `DocumentStorage`에 직접 저장한다. 자기 서버의 HTTP API나 presigned 업로드 URL을 거치지 않는다. S3 크기 확인 후 READY로 표시한다.

## 종료와 보관

- 같은 `qa_run`의 시나리오들은 녹화 하나를 공유한다. 단독 `qa_try`는 별도 녹화를 사용한다.
- 사용자 질문으로 agent가 대기하는 동안에도 녹화한다. QA 종료 후 5초까지 녹화를 유지한다.
- 종료 시 새 frame 접수를 막고 대기 중인 frame을 처리한 뒤 마지막 MP4를 finalize한다. 업로드 작업은 segment 파일만 소유하므로 인코더 종료와 분리해 제한된 시간 안에 마무리한다. 처리 중인 인코더를 다른 thread에서 강제로 해제하지 않는다.
- 각 segment 완료 시각부터 30일 후 S3 객체를 삭제한다. 30일 넘는 QA 대기 중에도 오래된 segment부터 삭제하며 판정·평가·issue 이력은 보존한다.
- 저장소 삭제 오류는 다음 주기에 재시도한다. 임시 파일이나 UPLOADING 상태를 정상 저장된 영상으로 표시하지 않으며, 저장되지 않은 구간은 재생할 수 없다.

## TC 평가·질문과 issue

`start_test_case`와 `report_test_case`로 검증 시도를 기록한다. step은 참고 절차이며 별도 판정·완료 의무가 없다. 재검증은 새 시도다. 재생 범위는 검증 시작 10초 전부터 결과 보고 5초 후까지이며 저장 영상 경계에 맞춘다.

원본 agent 판정과 TC snapshot을 보존하고, 사람의 마지막 평가와 모든 수정 revision을 기록한다. 같은 TC의 다음 검증에는 최근 평가를 자동으로 제공하며 TC 버전 차이를 표시한다. 신뢰도는 사용자가 검토한 시도에서 계산한다. 오탐률은 `FP / (FP + TN)`, 미탐률은 `FN / (TP + FN)`이며 분모가 0이면 `null`이다. 판단 불가·중단을 사람이 실제 실패로 확인하면 미탐에 포함한다. 일별 추이는 `Asia/Seoul` 날짜로 묶는다.

질문은 첫 프로젝트 멤버의 답변만 채택하며 실행 전체가 답변까지 기다린다. 알림은 앱이 열린 구독자에게 SSE로 전달하고, 알림 권한이 없어도 화면에서 답할 수 있다. TC 실패는 같은 TC의 열린 issue에 모으며, 수동 해결 후 재실패는 이전 issue를 연결한 새 issue로 기록한다. 오탐만 남으면 자동 종료하고 실제 실패로 평가를 정정하면 원래 issue를 재개한다.

실제 Windows Unity의 정상 방향 frame 수신과 S3 H.264 다운로드·decode, 두 viewer의 동시 수신·개별 종료를 확인했다. 이전 WebRTC·worker 기반 검증 수치를 현재 전송의 검증 결과로 사용하지 않는다. 최종 결과와 미실행 범위는 [통합 검증 기록](qa-tc-evaluation-verification.md)에 기록한다.
