# 내 서버 PR 9개 통합 기록

2026-10-28 `dev-yunseong`의 서버 PR을 최신 `develop` 기준 통합 branch에 합쳤다. 원본 branch와 commit·작성자 이력은 유지했다. 이 작업은 `develop` merge가 아니다.

## 원본 PR

| PR | 포함한 기능 |
| --- | --- |
| [#367](https://github.com/project-artel/artel-orchestration-server/pull/367) | agent-server의 4xx 응답 전달 |
| [#368](https://github.com/project-artel/artel-orchestration-server/pull/368) | 유효한 provider와 모델 기본값 선택 |
| [#369](https://github.com/project-artel/artel-orchestration-server/pull/369) | ping에 답하지 않는 SDK session 교체 |
| [#370](https://github.com/project-artel/artel-orchestration-server/pull/370) | SDK 전송 큐 동시 emit과 액션 지연 계측 |
| [#371](https://github.com/project-artel/artel-orchestration-server/pull/371) | QA try 성능 지표 internal API |
| [#379](https://github.com/project-artel/artel-orchestration-server/pull/379) | TC 시연 제어권·검증 절차 저장과 QA 활용 |
| [#381](https://github.com/project-artel/artel-orchestration-server/pull/381) | 현재 버전에 자동 생성 TC 추가 |
| [#382](https://github.com/project-artel/artel-orchestration-server/pull/382) | 로그 페이지와 무관한 QA 판정 전체 조회 |
| [#383](https://github.com/project-artel/artel-orchestration-server/pull/383) | orch 내부 영상 중계·변환·저장과 TC 평가·신뢰도·질문·issue API |

원본 PR의 설명·리뷰·CI 결과와 증거 링크는 원본 PR에 남긴다. 각 원본 head가 통합 HEAD의 ancestor인지 확인해 commit 누락을 검사한다.

## 충돌 해결

- `SessionManager.sendAction`은 시연 제어권 검사와 전송 성공 여부 `Boolean` 반환을 함께 유지했다.
- 명령 전송 큐의 동시 emit 보호·대기 수 계측과 최신 영상 ACK 한 개를 유지하는 전송 경로를 함께 보존했다. 영상 ACK는 액션 대기 수에서 제외한다.
- `AgentCase`와 `ScenarioCompositionService`는 승인된 `verification_procedure`와 사람 평가 `reviews`를 모두 전달한다.
- `/verdicts`는 `STATUS`·`CASE_VERDICT` 전체를 조회하므로 `step`이 없는 새 TC 판정도 복원한다.

## 기존 기반 PR

원본 #383의 기반에 이미 포함됐던 [#375](https://github.com/project-artel/artel-orchestration-server/pull/375), [#376](https://github.com/project-artel/artel-orchestration-server/pull/376), [#377](https://github.com/project-artel/artel-orchestration-server/pull/377), [#378](https://github.com/project-artel/artel-orchestration-server/pull/378)의 commit을 유지한다. 다른 작성자의 PR과 branch는 이 작업에서 변경하거나 종료하지 않는다.

## 검증

- `./mvnw -B test`로 전체 테스트 2,416개를 실행했다. 시연 기능이 연결되면서 mock WebSocket session의 ID 누락, SSE 단계와 notice frame의 도착 시점 차이, 영상 relay fixture의 공유 map 동시 접근 문제가 드러났다.
- 제품의 non-null session ID 계약과 기존 assertion을 유지하며 테스트 fixture·이벤트 대기·공유 상태를 수정했다. 영상 ACK를 액션 대기 수에서 제외하는 결합 회귀도 추가했다.
- 실패 항목과 관련 회귀 13개 class, 124개 테스트를 `./mvnw -B -Dtest=ContentMapScanTest,ContentMapEventStreamTest,ScreenNameTest,ScreenCaptureTest,QaReadingsTest,GameInstanceWebSocketAuthIntegrationTest,GameBuildSummaryIntegrationTest,GameInstanceResetServiceTest,QaMediaRelayTest,TestScenarioReconcileIntegrationTest,SessionManagerTest,ScenarioExecutionModeTest,OpenApiDocumentationIntegrationTest package`로 재실행해 모두 통과했다. Spring Boot JAR 생성도 통과했다.
- 최종 Surefire 보고서 263개에는 고유 테스트 2,417개, 실패·오류·skip 0개가 기록됐다. 이 수치는 전체 실행과 수정된 class 재실행의 합쳐진 최종 보고서이며, 수정 후 전체 suite를 한 번 더 실행한 수치는 아니다.
- 원본 head 9개 ancestry와 `git diff --check`를 통과했다. Flyway migration의 중복 version은 없으며 기존 migration 변경·삭제 없이 추가만 포함한다. 독립 통합 리뷰를 통과했다.
- 사용 중인 로컬 서버·Unity·QA DB는 변경하지 않았다. 실제 유료 QA와 새 UI 시연은 실행하지 않았다.
