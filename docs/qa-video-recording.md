# orch 내부 영상 중계와 TC 검증 시도 저장

SDK 영상은 orch 서버의 JVM 프로세스에서 수신한다. 같은 프로세스가 브라우저에 영상을 중계하고 H.264 MP4로 변환해 S3에 저장한다. 별도 media-worker, Python 서버, 미디어 컨테이너나 FFmpeg 자식 프로세스는 사용하지 않는다.

## 실행 설정

기존 orch 서버만 배포한다. `webrtc-java 0.20.0`의 JNI로 WebRTC peer를 만들고 `JavaCV 1.5.14`와 `FFmpeg 8.1.2-1.5.14`의 Linux GPL native 라이브러리를 같은 JVM에 로드한다. Linux x86_64 런타임을 포함하며 Dockerfile은 libwebrtc 로딩에 필요한 X11·GLib·GBM·DRM 라이브러리를 설치한다.

| 설정 | 기본값 | 용도 |
| --- | --- | --- |
| `ARTEL_STREAM_ENABLED` | `true` | orch 내부 스트리밍 활성화 |
| `ARTEL_MEDIA_DIRECTORY` | `./data/qa-video` | MP4 segment 임시 저장 경로 |
| `ARTEL_MEDIA_MAX_INGRESS` | `8` | 종료 중인 연결을 포함한 동시 SDK ingress 상한 |
| `ARTEL_MEDIA_MAX_VIEWERS` | `8` | ingress당 viewer 상한 |

임시 디렉터리는 orch 실행 사용자가 쓸 수 있어야 한다. peer와 인코더는 최초 사용 시 초기화한다. native 초기화나 저장에 실패해도 API 서버와 QA 실행은 계속하며 오류·누락 구간을 남긴다. SDK와 브라우저를 직접 연결하는 P2P 경로로 자동 전환하지 않는다.

외부 네트워크 연결에는 배포 환경에서 도달 가능한 TURN 서버를 설정한다. 기존 `ARTEL_TURN_URL`, `ARTEL_TURN_USERNAME`, `ARTEL_TURN_CREDENTIAL`을 orch·SDK·브라우저 peer에 사용한다. S3는 기존 `DocumentStorage` 설정을 사용하며 객체 prefix는 `qa-video/`다. 브라우저에는 프로젝트 접근 확인 후 짧은 유효기간의 재생 URL을 발급한다.

## 중계·변환과 자원 상한

`InProcessMediaService`가 SDK 수신 peer와 viewer 송신 peer를 관리한다. `QaMediaRelay`는 기존 SDK·viewer WebSocket의 offer·answer·ICE 계약과 녹화 소유권을 연결한다. viewer 연결이 끝나도 QA가 영상을 필요로 하면 ingress와 녹화는 유지한다.

`QaVideoRecorder`는 ingress당 인코딩 thread 1개와 FFmpeg thread 1개를 사용한다. native callback에서 프레임을 보관할 때만 retain하고 처리·drop·종료의 모든 경로에서 release한다. raw frame은 ingress당 3개·8 MiB 중 먼저 도달하는 상한으로 제한하며 입력 해상도는 최대 1920×1080이다.

MP4 segment는 최대 60초·32 MiB로 나눈다. 업로드 thread는 서버 전체 2개이며, 업로드 대기는 서버 전체 10개·256 MiB로 제한한다. 업로드는 3회 시도하고 실패하거나 대기열이 포화되면 해당 구간을 gap으로 남긴다. QA 전체 영상을 메모리에 누적하지 않는다.

첫 프레임의 서버 시각과 monotonic 시각을 함께 기록하고 이후 도착 간격을 MP4 PTS에 반영한다. 입력 정지나 프레임 drop 시간을 프레임 수로 압축하지 않는다. 재연결·해상도·회전 변경은 새 segment를 시작한다.

완성한 segment는 `QaRecordingStore`를 통해 기존 검증·멱등성 규칙으로 등록하고 `DocumentStorage`에 직접 저장한다. 자기 서버의 HTTP API나 presigned 업로드 URL을 거치지 않는다. S3 크기 확인 후 READY로 표시한다.

## 종료와 보관

- 같은 `qa_run`의 시나리오들은 녹화 하나를 공유한다. 단독 `qa_try`는 별도 녹화를 사용한다.
- 사용자 질문으로 agent가 대기하는 동안에도 녹화한다. QA 종료 후 5초까지 녹화를 유지한다.
- 종료 시 callback 입력을 막고 보관한 프레임을 처리·해제한 뒤 마지막 MP4를 finalize한다. 제한 시간 내 업로드를 마치고 실패 구간을 기록한 뒤 peer·track·source·factory를 정리한다. 처리 중인 native buffer를 다른 thread에서 강제로 해제하지 않는다.
- 각 segment 완료 시각부터 30일 후 S3 객체를 삭제한다. 30일 넘는 QA 대기 중에도 오래된 segment부터 삭제하며 판정·평가·issue 이력은 보존한다.
- 저장소 삭제 오류는 다음 주기에 재시도한다. 임시 파일이나 UPLOADING 상태를 정상 저장된 영상으로 표시하지 않으며, 저장되지 않은 구간은 재생할 수 없다.

## TC 평가·질문과 issue

`start_test_case`와 `report_test_case`로 검증 시도를 기록한다. step은 참고 절차이며 별도 판정·완료 의무가 없다. 재검증은 새 시도다. 재생 범위는 검증 시작 10초 전부터 결과 보고 5초 후까지이며 저장 영상 경계에 맞춘다.

원본 agent 판정과 TC snapshot을 보존하고, 사람의 마지막 평가와 모든 수정 revision을 기록한다. 같은 TC의 다음 검증에는 최근 평가를 자동으로 제공하며 TC 버전 차이를 표시한다. 신뢰도는 사용자가 검토한 시도에서 계산한다. 오탐률은 `FP / (FP + TN)`, 미탐률은 `FN / (TP + FN)`이며 분모가 0이면 `null`이다. 판단 불가·중단을 사람이 실제 실패로 확인하면 미탐에 포함한다. 일별 추이는 `Asia/Seoul` 날짜로 묶는다.

질문은 첫 프로젝트 멤버의 답변만 채택하며 실행 전체가 답변까지 기다린다. 알림은 앱이 열린 구독자에게 SSE로 전달하고, 알림 권한이 없어도 화면에서 답할 수 있다. TC 실패는 같은 TC의 열린 issue에 모으며, 수동 해결 후 재실패는 이전 issue를 연결한 새 issue로 기록한다. 오탐만 남으면 자동 종료하고 실제 실패로 평가를 정정하면 원래 issue를 재개한다.

실제 검증 결과와 미실행 범위는 [통합 검증 기록](qa-tc-evaluation-verification.md)에 기록한다.
