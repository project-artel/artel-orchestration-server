# orch WebSocket 영상 처리 검증 기록

갱신일: 2026-10-28 (Asia/Seoul)

## 현재 구성

SDK와 브라우저는 기존 인증된 WebSocket으로 JPEG frame을 주고받는다. orch JVM이 중계와 H.264 MP4 변환·S3 저장을 담당한다. 별도 TURN·media-worker·Python 서버·FFmpeg 자식 프로세스와 WebRTC peer는 사용하지 않는다. JavaCV 1.5.14와 FFmpeg 8.1.2-1.5.14의 JNI를 사용한다.

## 실제 Windows Unity 검증

합성 SDK가 아닌 실제 WordVenture Unity 2022.3.62f3(D3D)와 WSL orch, 로컬 MinIO를 사용했다. 기존 WebRTC 경로에서 첫 영상 frame을 받지 못하는 현상을 재현했고, proxy를 127.0.0.1로 바꾸는 것만으로 해결되지 않는 것을 확인했다.

JPEG_WS 전환 후 사용자가 브라우저에서 영상 수신을 확인했다. 최초 캡처는 상하가 반대였고, SDK의 framebuffer UV 방향을 보정한 뒤 원본 JPEG와 S3 영상의 글자가 정상 방향인 것을 확인했다. 구 package의 컴파일 결과가 남아 있던 Unity에는 최종 local SDK package를 다시 연결해 적용했다.

| 검증 | 관측 결과 |
| --- | --- |
| 실제 Unity→orch viewer WebSocket | 보정 후 1280×720 JPEG 40 frames, 약 9.37fps |
| 실제 JPEG 크기 | 평균 63,367 bytes, 최대 63,711 bytes, 128KiB 이내 |
| 실제 QA 6 H.264 MP4→S3→다운로드·decode | 1,350,436 bytes, 472 frames, 마지막 PTS 약 54.57초 |
| 화면 방향 | 다운로드한 MP4와 새 JPEG의 게임·UI 글자가 정상 방향 |
| 최종 SDK | GPU EditMode 578개·PlayMode 92개, 총 670개 통과·skip 0 |
| home | 전체 1,509개·전송 경계 11개 테스트, typecheck·build·수정 파일 eslint 통과 |
| 서버 | 집중 회귀 14개 클래스·83개 항목과 JAR package 통과 |
| Docker | artel-orch-websocket-media:20261028 build 통과 |
| Docker JPEG/H.264 | 실제 이미지에서 JPEG decode→H.264 encode/decode·상하 패턴 보존 통과, JVM PID 1·childProcesses 0 |

실제 게임의 QA 결과 정확도 개선을 측정한 결과는 아니다. 영상 전송·저장 검증과 agent 판정 품질 평가는 구분한다. Firefox 화면 자동 확인은 도구의 Windows 브라우저 정책 지원 제한으로 중단됐으며 이후 검증은 서버 viewer API와 S3 다운로드를 사용했다.

## 회귀 범위

- ACK flow-control, 서버 frame rate·JPEG/JSON 크기·metadata 해상도 제한, 중복 frameId·이전 SDK session·구 transport 거부
- 느린 SDK ACK·viewer의 최신 frame 대기와 실제 최종 merge의 고정된 in-flight 상한
- JPEG decode/encoder의 대기+처리 중 count·8MiB budget, PTS에 입력 정지 시간을 보존
- S3 지연 격리, 3회 재시도 실패·gap·임시 파일 정리, finalize 실패 후 자리 재사용
- SDK의 송신 callback 미반환→실제 소켓 종료→새 socket 송신, 늦은 timer·callback·ACK 차단
- GPU noise 화면의 JPEG 크기 제한·자동 해상도 축소·surface 재사용·UV 방향 분기
- home의 decode 1개·최신 대기 1개, 늦은 bitmap 해제, frame stale와 버전 불일치 오류 구분
- TC 평가 API·신뢰도·영상 coverage·OpenAPI 회귀

서버 명령: `./mvnw -Dkotlin.compiler.daemon=false -Dtest=QaVideoRecorderTest,InProcessMediaServiceTest,QaMediaRelayTest,ViewerSessionRegistryTest,ViewerPayloadLifetimeTest,StreamPropertiesTest,SessionManagerTest,SdkWebSocketHandlerUnknownTypeTest,SdkHeartbeatTest,SdkSocketPropertiesTest,CaseVerdictFromQaIntegrationTest,VideoCoverageTest,QaReliabilityTest,OpenApiDocumentationIntegrationTest package`.

## 추가 검증과 남은 범위

실제 Windows Unity에 viewer 두 개를 동시에 연결해 각자 다른 streamId로 frame을 받는 것을 확인했다. 첫 viewer의 frameId가 1→2로 증가하는 동안 두 번째도 수신했고, 첫 viewer를 종료한 뒤에도 두 번째는 frameId 1→13까지 새 frame을 받았다. 기존 viewer 교체 경로는 없다. 단위 회귀는 최대 8개·32개 동시 admission·개별 종료·마지막 viewer 종료·녹화 유지·전체 viewer 재연결을 검증한다. QA history의 테스트 런 이름 링크와 실행 기록 명칭도 반영했다. home 전체 lint에는 이번 수정 범위 밖의 기존 오류 5개·경고 4개가 남아 있으며 수정 파일 lint는 통과했다.

운영 장시간 부하, main-thread JPEG encode p95와 느린 외부 TCP 연결에서의 QA action 지연은 별도 측정이 남아 있다. 1280×720·10fps와 128KiB JPEG, ACK·queue 상한은 비용을 제한하는 장치이며 모든 네트워크의 지연 보장값은 아니다.

## 이전 구현 기록

별도 worker의 6개 테스트와 synthetic WebRTC 경로의 55개 회귀·757/590 frames 결과는 이전 구현의 기록이다. 현 WebSocket 구현의 검증 수치로 합산하지 않는다. 이전 상세는 서버 PR #383의 commit e45569cf에 보존돼 있다.