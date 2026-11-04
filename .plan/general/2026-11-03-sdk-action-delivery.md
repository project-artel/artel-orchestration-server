# 2026-11-03 — SDK ACTION 수신 ACK와 재접속 복구 추가

- Date: 2026-11-03
- Jira: ARTEL-1341 (server ARTEL-1342, SDK ARTEL-1343)
- Status: Ready for review

## Goal
SDK 수신 ACK, 동일 requestId 재전송, 중복 실행 방지와 세션 교체 시 미완료 QA ACTION 복구를 추가한다.

## Non-goals
서버/게임 프로세스 재시작을 넘는 exactly-once, agent 도구/프롬프트/UI 변경, 실행 중 QA 재시작. 비-QA 요청의 자동 재전송은 제외한다(별도 ID 공간 충돌 방지).

## Context / Constraints
실패 요청 45228은 기존 소켓 큐 등록 후 세션 교체되어 결과가 없었다. 서버의 queued는 수신 증거가 아니다. origin/develop 기반 격리 worktree 사용. 새 프로토콜을 협상한 SDK에만 복구 활성화한다.

## Approach (Checklist)
- [x] **Step 0: Recon** — SessionManager, QaActionDispatchService, ActionResultMessageHandler, ArtelManager와 transport 수명 확인.
- [x] **Step 1: Implementation**
  - SDK handshake query actionDelivery=1, processId=UUID. journal과 processId는 소켓 재접속 및 reset_game 동안 유지한다.
  - QA ACTION에 delivery {processId, expiresAtUnixMs} 부착. 기존 id가 requestId이며 재전송 wire/ID는 동일하다. 구버전 SDK에는 기존 wire 유지.
  - 서버 SdkActionDelivery가 instanceId/requestId/qaTryId/원 wire/대상 processId/ACK/시도 수/만료를 메모리에 관리한다. DB 로그 transaction commit 후 pending 등록 및 최초 전송을 수행한다. commit 이전 rollback/cancel은 전송 0회다.
  - ACK 없는 요청은 2초, ACK 있는 요청은 결과 복구용 5초 간격, 총 6회 이내/25초 이내 재전송한다. agent의 기존 30초 timeout 이전에 중단한다.
  - 새 연결에서 같은 processId일 때만 즉시 재전송한다. replay 직전 활성 QA 및 현재 소켓 identity 확인. 새 프로세스/구버전/QA 종료/만료 시 요청 제거하고 자동 실행하지 않는다.
  - 세션 객체에 묶인 emit으로 검사 이후 다른 세션으로 바뀌는 경합을 막는다. 일반 disconnect도 pending이 있으면 5초 grace를 두고, 복구 실패 시 원래 try ID만 종료한다.
  - 25초 만료 시각은 dispatch 진입 시 계산한다. DB commit 대기 중 이미 만료됐다면 최초 전송도 하지 않는다.
  - SDK는 등록 후 ACTION_ACCEPTED {requestId,processId}를 보낸다. 실행 중 중복은 ACK만, 완료 중복은 동일 결과 재응답. 같은 ID의 다른 본문은 거절한다.
  - journal은 최대 1024개, 만료 완료 항목만 제거하며, 공간 부족/만료된 미등록 요청/다른 processId는 실행 거절한다. 만료 시각은 서버/게임 UTC clock 기준으로 검사한다.
  - 수신 ACK와 결과는 현재 소켓/대상 processId에 대해 검사한다. ACTION_RESULT는 bridge 전달 성공 후 pending에서 제거한다. 중복 결과는 기존 QA log unique 처리 활용.
  - SDK disconnect/실행 예외로 중단된 요청 및 대기 큐 전체는 실행 여부 미확인 실패를 cache한다. stale message 경로도 terminal로 처리한다. 재접속으로 다시 실행하지 않는다. 기존 입력 해제 동작 유지.
  - SDK journal/processId 범위는 server origin + instanceId다. 대상 변경 시 다른 processId/journal 사용. 동일 대상의 재접속만 기존 기록을 사용한다.
  - QA bridge는 중복 SDK 결과를 받아도 agent relay를 재시도한다. 로그는 unique로 중복 생성하지 않고 agent messageId도 requestId 기반으로 고정한다.
  - 만료는 동일 UTC clock을 요구한다. 서버는 25초 이후 보내지 않으며 SDK는 실행 시작 때도 검사한다. clock 오차가 있는 peer의 보장은 범위 밖이며 이 제약을 문서화한다.
- [x] **Step 2: Tests** — ACK/결과 유실, 재접속/세션 교체, stale session, process 변경, 구버전, 만료/QA 종료, commit 전 실패/취소, 중계 재시도와 기존 WebSocket·QA 회귀 검증. SDK의 중복 실행 및 입력/reset 경로는 ARTEL-1343에서 검증했다.
- [x] **Step 3: Rollout / Rollback** — 양쪽 capability 협상으로 독립 적용. SDK 업데이트 없으면 기존 단발 전달. commit revert로 복구 비활성화. 실행 중 QA 서버·게임은 재시작하지 않았다.

## Validation
- **Commands to run:** 서버 Maven focused SDK/QA tests 및 관련 통합 테스트, Unity EditMode/PlayMode. 실제 명령은 환경 확인 후 기록.
- **Expected output:** 재전송 2회 이상이어도 게임 효과는 1회, ACK/결과 유실 후 동일 ID 복구, 프로세스 변경 시 재실행 0회.
- **Results:** Java 21, Maven, 격리 Postgres/Redis에서 서버 테스트 9개 클래스 79개 통과, 실패/오류/skip 0개. 명령: `mvn -B -Dtest=SdkActionDeliveryTest,SdkActionReconnectTest,ActionResultDeliveryRoutingTest,SessionManagerTest,SessionReplacementTest,SdkWebSocketHandlerUnknownTypeTest,GameFunctionsIntegrationTest,ArtelWebSocketIntegrationTest,QaReadingsTest test`.
- Windows 경로의 검증은 파일 읽기가 느려 중단하고 WSL 격리 복사본에서 최종 검증했다. 변경된 source/test 파일 14개의 SHA-256이 원 worktree와 동일함을 확인했다. `git diff --check` 통과.
- SDK counterpart는 Unity EditMode 91개/PlayMode 52개, 총 143개 통과. XML/log는 각 worktree의 로컬 검증 경로에 보존한다.

## Risks & Rollback
- **Risks:** 메모리 journal은 프로세스 재시작을 넘지 않는다. 서버/SDK UTC clock 오차는 만료 판정에 영향. 부작용 이후 중단은 실패 결과에 실행 여부 미확인을 명시한다.
- **Rollback steps:** SDK와 서버 commit revert. 신규 capability 없는 peer에는 재전송하지 않는다.

## Open Questions
- 없음. 기능 범위는 QA ACTION transport에 한정하며 agent 응답 형식은 유지한다.

## Plan Review
- FAST/MEDIUM: NONPASS 피드백 반영 — commit 뒤 전송, 중단된 큐 terminal 처리, 연결 대상별 journal, 중복 결과 agent relay 재시도.
- clock 자동 동기화/영속 outbox는 범위 밖이다. UTC clock 동기화 요구를 명시한다.
- HEAVY REVIEW: PASS. Pair review에서 identity 검사와 emit 경합, 비-QA scan/capture ID 충돌을 수정하고 회귀 테스트를 추가했다.
- Final pair review: PASS — SDK와 서버 wire 계약, reset·중단·결과 보관 경로 확인.
