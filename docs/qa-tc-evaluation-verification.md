# orch 내부 영상 처리 검증 기록

갱신일: 2026-10-28 (Asia/Seoul)

## 현재 검증 대상

별도 media-worker를 제거하고 orch JVM 내부에서 WebRTC 수신·viewer 중계, H.264 MP4 변환, S3 저장을 수행하는 구조를 검증한다. `webrtc-java 0.20.0`, `JavaCV 1.5.14`, `FFmpeg 8.1.2-1.5.14`의 JNI를 사용한다. native 라이브러리를 로드한 orch 프로세스 안에서 처리하며 미디어 자식 프로세스를 실행하지 않는다.

검증 환경은 WSL Ubuntu, Java 21, PostgreSQL(pgvector), Redis와 로컬 MinIO다. 검증 전용 DB·bucket과 합성 사용자·TC·QA 실행을 사용한다. 비밀 값, JWT와 presigned URL은 이 문서에 저장하지 않는다.

## 확인한 범위

Linux native POC에서 단일 JVM PID `97441`로 H.264 인코딩이 동작하는 것을 확인했다. 이 결과는 JNI 로딩과 인코딩 가능성 확인이며, SDK·viewer·DB·S3를 잇는 최종 통합 검증 결과는 아니다.

## 최종 검증 결과

| 검증 | 결과 |
| --- | --- |
| 실제 SDK WebSocket → orch JVM → viewer | 640×480 프레임 수신, viewer connected 확인 |
| viewer 종료 후 QA 녹화 유지 | viewer WebSocket 종료 뒤에도 SDK 송출 유지, QA 종료 뒤 STREAM_STOP 확인 |
| H.264 MP4 → S3 → 다운로드·decode | READY segment 1개, 37,594 bytes, 757 frames |
| 단일 프로세스 | orch PID 108203의 자식 프로세스 목록이 비어 있음 |
| 입력 정지·drop 시간축 | native MP4 decode와 저장 metadata 비교 테스트 통과 |
| 프레임·연결 상한과 release | burst 입력 retain 상한, 초과 해상도, ICE 후보 상한, native 연결 자리 재사용 테스트 통과 |
| 종료·실패 | 최초 START 실패, 늦은 시그널, 인코더 종료 실패 재시도, MP4 최종화 실패, 업로드 3회 실패·gap·파일 정리 테스트 통과 |
| 느린 업로드·종료 | latch로 S3/종료를 막아도 native 종료 반환과 다른 SDK lease 갱신이 진행되는 테스트 통과 |
| 집중 Kotlin 회귀 | 10개 클래스 55개 항목 통과. 최초 52개와 마지막 native 9개 결과에서 재실행 6개를 중복 제외 |
| JAR package | 통과 |
| Docker build | artel-orch-inprocess-media:20261028 통과 |
| Docker native 실행 | 실제 orch 이미지의 PID 1에서 WebRTC 초기화와 H.264 MP4 인코딩 통과, exit 0 |
| worker 제거 | media-worker 폴더·compose.media.yml·worker URL·전용 HTTP 업로드 API 삭제 |

집중 검증 명령은 `./mvnw -Dkotlin.compiler.daemon=false -Dtest=QaMediaRelayTest,QaVideoRecorderTest,InProcessMediaServiceTest,StreamPropertiesTest,ViewerSessionRegistryTest,ViewerPayloadLifetimeTest,CaseVerdictFromQaIntegrationTest,VideoCoverageTest,QaReliabilityTest,OpenApiDocumentationIntegrationTest package`다. native 테스트의 Kotlin 반환형을 명시한 뒤 `-Dtest=QaVideoRecorderTest,InProcessMediaServiceTest kotlin:test-compile surefire:test`로 9개가 모두 발견·통과하는 것도 확인했다.

실제 송수신 검증은 합성 SDK·viewer 클라이언트, 별도 DB `artel_qa_inprocess_20261028`, 로컬 MinIO와 서버 8190/8191 포트를 사용했다. Python은 테스트 클라이언트로만 사용했으며 orch 서버나 Docker 배포에는 포함되지 않는다. Docker native 검증은 네트워크를 끄고 같은 의존성을 로드한 실행이다. 실제 Unity 게임 QA나 외부 TURN 검증 결과로 해석하지 않는다.

## 이전 구조의 검증 이력

2026-10-27~28의 worker 기반 합성 WebRTC·S3 검증, Python worker 테스트 6개와 worker Docker build는 삭제한 구조에 대한 과거 결과다. orch JVM 내부 구현의 통과 근거로 사용하지 않는다.

이전 서버 변경은 중단 전·재개·수정 후 검증을 중복 제외해 257개 클래스·2,341개 테스트를 통과했다. 이 수치는 native 미디어 구현을 추가하기 전의 회귀 기준이며 현재 변경이 같은 suite를 통과했다는 뜻이 아니다. 기존 검증 중 발견한 scene 대표 조작의 조회 순서 의존성과 OpenAPI 테스트 버퍼 수정은 코드에 유지한다.

## 남은 운영 검증

실제 Unity 게임을 같은 model·prompt_version·scenario·build로 실행하는 변경 전·후 비교는 LLM 복호화 설정이 없어 미실행이다. 자동 테스트나 합성 영상 검증으로 agent 정확도 개선을 주장하지 않는다.

외부 TURN 연결, 다중 instance의 장시간 부하, 운영 bucket의 30일 삭제 작업, 실제 사용자 알림도 배포 환경에서 확인해야 한다. JNI native 오류가 같은 JVM에 영향을 줄 수 있으므로 연결·프레임·thread·저장 상한과 종료 경로를 함께 검증한다.

## 연관 문서

- [확정 요구사항](qa-tc-evaluation-requirements.md)
- [내부 미디어 설정과 보관 정책](qa-video-recording.md)
- Jira: ARTEL-1269, 전체 작업 ARTEL-1268
