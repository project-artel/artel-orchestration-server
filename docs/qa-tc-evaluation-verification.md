# QA 영상과 TC 평가 통합 검증 기록

검증일: 2026-10-27~28 (Asia/Seoul)

## 검증 환경

WSL Ubuntu, Java 21, PostgreSQL(pgvector), Redis, 로컬 MinIO를 사용했다. 이 작업의 별도 DB와 bucket에 합성 프로젝트·사용자·TC·QA 실행을 만들었으며 운영 데이터는 사용하지 않았다. 인증은 로컬 서버의 서명 키로 발급한 테스트 JWT를 사용했다. 비밀 값과 presigned URL은 문서에 저장하지 않는다.

## 실제 HTTP와 영상 저장 검증

합성 `VideoStreamTrack`을 WebRTC로 worker에 보내고 viewer peer에서 프레임 수신을 확인했다. 실행 중인 orch API로 TC 시도를 시작·보고하고, worker의 실제 업로드 경로로 MinIO에 MP4를 저장했다. 재생 API가 발급한 URL을 내려받아 PyAV로 decode했다.

| 항목 | 관측 결과 |
| --- | --- |
| viewer 프레임 | 640 × 480 |
| 저장 영상 | H.264, 226 frames, 7.666초, 33,153 bytes |
| viewer 종료 후 녹화 | 계속 진행 |
| 검증 구간 | 서버 시작 시각 -10초, 보고 시각 +5초 |
| 평가 수정 | PASSED → FAILED, revision 2개 보존 |
| 원본 판정·snapshot·이유 | 평가 후에도 FAILED와 최초 내용 보존 |
| TC issue | 오탐 평가로 자동 종료, 실제 실패로 수정 후 기존 issue 재개 |
| 질문 답변 | 첫 답변 채택, 후속 답변 HTTP 409 |
| 신뢰도 | 프로젝트·일별·TC별 같은 시도 집계, 분모 0은 null |
| 미인증 영상 조회 | HTTP 401 |

31일 지난 segment 객체도 별도로 만들었다. 재생 API는 URL 없이 EXPIRED를 반환했고, 주기 작업이 S3 객체를 삭제했다. segment metadata와 평가 revision은 유지됐다.

후속 검증에서는 합성 SDK와 viewer를 실제 orch WebSocket에 연결했다. SDK → orch WebSocket → worker → S3 경로에서 viewer가 `connected` 상태로 640 × 480 프레임을 받았고, 저장한 MP4 115,065 bytes를 내려받아 832 frames를 decode했다. 종료 시 SDK에 STREAM_STOP이 전달됐으며 SDK·viewer peer 모두 `closed`로 끝났다. 이 결과도 합성 영상의 연결·저장 검증이며 실제 Unity 게임 QA 결과는 아니다.

## 자동 검증

- `CaseVerdictFromQaIntegrationTest` 20개, `VideoCoverageTest` 3개, `QaReliabilityTest` 3개, `QaMediaRelayTest` 5개 통과. knowledge 인용 카운터와 미디어 시작 실패·재연결 회귀 테스트를 포함한다.
- `python -m pytest media-worker/test_worker.py -q`: 실제 WebRTC 재연결, viewer 종료 후 녹화, MP4 timestamp, 업로드 대기열 상한 4개 통과. 후속 복구 검증에서 SDK의 FAILED/STOPPED 및 60초 협상 시간 초과 회귀 테스트를 추가했다.
- `docker build -t artel-qa-media-evaluation:20261027 media-worker`: 통과.
- 빌드한 Docker 이미지의 Python 3.12 환경에서 복구 회귀 테스트를 포함한 worker 테스트 6개 통과.
- `scripts/check-flyway-migrations.sh <선행 ARTEL-1256 브랜치>`: `OK: no version collisions.`
- `git diff --check`: 통과.

최종적으로 257개 클래스의 서로 다른 테스트 2,341개가 통과했다. 아래처럼 중단 전 실행·재개 실행·수정 후 집중 검증을 합친 결과이며, 재실행한 테스트는 중복 집계하지 않았다.

| 실행 | 최종 집계에 포함한 통과 수 | 결과 |
| --- | --- | --- |
| `./mvnw -B test` 중단 전 완료한 133개 클래스 | 1,223 | 모두 통과 |
| `./mvnw -B -Dkotlin.compiler.daemon=false -Dtest=<통과한 133개 클래스 제외> kotlin:test-compile surefire:test` | 1,114 | 124개 클래스·1,118개 실행 중 OpenAPI 4개 오류 발견, 나머지 통과 |
| `./mvnw -B -Dkotlin.compiler.daemon=false -Dtest=OpenApiDocumentationIntegrationTest kotlin:test-compile surefire:test` | 4 | 테스트 클라이언트 수정 후 모두 통과 |
| 중복을 제외한 합계 | 2,341 | 남은 실패·오류·건너뛴 테스트 0 |

## 화면 간선의 조회 순서 의존성 수정

전체 테스트에서 `ScenarioReuseAcrossBuildsIntegrationTest`의 같은 지도 비교가 실패했다. 단독 재실행은 20개 모두 통과했지만, 프로젝트 조회와 빌드 조회 모두 SQL 행 순서를 보장하지 않는 상태에서 `sceneEdges`가 첫 non-null 조작을 선택하고 있었다. 같은 간선의 두 조작 `Canvas/continue`와 `Canvas/MapSceneButton` 중 선택되는 값이 조회 순서에 따라 달라졌다.

조회 결과 순서를 뒤집는 회귀 테스트를 추가해 기존 코드에서 1개 실패를 확인했다. 조작이 있는 경로를 우선하는 기존 규칙을 유지하면서, non-null 조작 중 사전순 최소값을 선택하도록 수정했다. 수정 후 회귀 테스트 1개와 전체 suite에서 실행한 `ScenarioReuseAcrossBuildsIntegrationTest` 20개가 모두 통과했다. 이 선택은 같은 지도에서 동일한 prompt를 만드는 기존 계약을 지킨다.

## 호스트 중단과 테스트 재개

전체 suite 실행 도중 호스트가 장시간 중단됐고 이후 WSL의 임시 디렉터리와 실행 프로세스가 사라졌다. 중단 전 134개 클래스의 1,230개 테스트 결과가 남았으며, 133개 클래스는 모두 통과했다. `FilesystemStorageRoutesIntegrationTest`의 업로드 테스트 1개는 URL 만료로 실패했다. 해당 URL의 만료 시각은 10월 27일 17:55:30 KST였지만 실제 PUT 요청은 20:03:05에 처리돼 `invalid_storage_signature`와 HTTP 403을 반환했다. 이 테스트의 측정 시간은 8,255초였다.

만료 시간이나 테스트 조건을 늘리지 않고, 지속되는 Linux 작업 디렉터리에 동일 소스를 복원했다. 통과한 133개 클래스만 제외하고 남은 클래스와 URL 만료 실패 클래스를 다시 실행했다. 최종 결과는 중단 전 통과 결과와 재개 실행 결과를 합쳐 기록하며, 한 번의 중단 없는 전체 suite 통과로 표기하지 않는다.

재개 실행에서는 `FilesystemStorageRoutesIntegrationTest` 7개가 2.162초에 모두 통과했다. 테스트 컴파일 중 Kotlin daemon 연결이 EOF로 끊겨, 다른 작업의 daemon을 종료하지 않고 `-Dkotlin.compiler.daemon=false`와 Maven JVM 3 GiB 상한으로 테스트 컴파일을 완료했다.

## OpenAPI 테스트 클라이언트 수정

추가한 API를 포함한 `/v3/api-docs` 응답은 280,501 bytes였다. 테스트 WebClient의 기본 262,144 bytes 버퍼를 넘겨 HTTP 200 응답을 받더라도 `DataBufferLimitException`이 발생했고, 최초 schema 생성은 기존 5초 대기를 초과했다.

테스트 전용 WebClient의 버퍼를 1 MiB로 제한하고, `BeforeAll`에서 최대 30초 동안 계약을 한 번 읽도록 바꿨다. endpoint·인증 파라미터를 확인하는 기존 4개 검증은 유지했다. 제품의 응답 제한이나 인증 동작은 변경하지 않았다.

수정 후 4개 테스트가 모두 통과했고 `BUILD SUCCESS`를 확인했다. 최종 실행도 야간 호스트 중단 후 재개됐으므로 Maven의 전체 벽시계 시간 8시간 42분은 실제 테스트 처리 시간을 뜻하지 않는다. JUnit이 기록한 클래스 실행 시간은 42.70초였다.

## 남은 운영 검증

이 기록은 실제 Unity 게임 QA의 변경 전·후 비교 결과가 아니다. 같은 model·prompt_version·scenario·build로 실제 게임을 실행하는 비교는 LLM 복호화 설정이 없어 진행하지 못했다. agent 저장소 검증 문서에 같은 제한을 기록한다.

외부 네트워크의 TURN 연결, 다중 instance의 장시간 녹화 부하, 운영 bucket의 보관 작업, 실제 사용자 브라우저 알림은 배포 전 별도 확인해야 한다. 합성 영상·로컬 네트워크 테스트로 이 결과를 대신하지 않는다.

## 연관 문서

- [확정 요구사항](qa-tc-evaluation-requirements.md)
- [미디어 설정과 보관 정책](qa-video-recording.md)
- Jira: ARTEL-1269, 전체 작업 ARTEL-1268
