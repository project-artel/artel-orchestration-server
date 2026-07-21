# 2026-07-21 — TestScenario 챗봇 파이프라인 MVP 구현

- Date: 2026-07-21
- Jira: None
- Status: Draft

## Goal

QA 대시보드(React)에서 자연어를 챗봇 형태로 입력하면 Agent 서버로 전달하고, Agent가 돌려주는
답변(step 분리 결과) 또는 폴백 질문을 **SSE**로 실시간 전달하는 순수 양방향 릴레이 파이프라인을
구축한다.

## Non-goals

- DB 저장/영속화 (YAGNI — 나중에 seam으로 추가).
- Canvas step-graph 시각화, 드래그앤드롭 step CRUD.
- 기존 step 수정 시 draft 미리보기/승인 플로우 (설계상 `type` + `payload` 통과로 열어만 둠).
- 실제 Agent 서버와의 end-to-end 통합 (본 작업은 Agent 스텁 기준).

## Context / Constraints

- 스택: Kotlin 2.3.21 + Spring Boot 3.3.1 (WebFlux, 리액티브) + Java 21.
- 기존 패턴 재사용: `sdk` 도메인의 `WebClient` 아웃바운드(`AgentClient`),
  `ConcurrentHashMap` 세션 관리(`SessionManager`), REST 컨트롤러(`SdkController`).
- 신규 의존성 없음 (SSE·WebClient 모두 WebFlux 기본 제공).
- 식별자: `clientId`(FE 생성 UUID, SSE·라우팅 키) / `agentSessionId`(Agent 발급, 나중에 콜백으로 도착).
  Orchestration이 `clientId ↔ agentSessionId` 매핑 보관.
- 계약 전제: Agent 콜백 body에 `clientId` 포함(echo) → `{agentSessionId}` 경로여도 올바른 스트림으로 라우팅.
- 테스트 계획: `.plan/general/2026-07-21-testscenario-pipeline-test-plan.md` (TC-1~TC-7).

## Approach (Checklist)

- [ ] **Step 0: Recon** — 기존 `sdk` 패키지 구조/패턴 확인 완료 (AgentClient, SessionManager, WebSocketConfig, controller).
- [ ] **Step 1: Implementation** — 신규 패키지 `kr.artel.orchestration.testscenario`
  - `dto/TestScenarioMessage.kt` — `{ type, testscenariomsg }` (FE → Orch 인바운드)
  - `dto/AgentScenarioRequest.kt` — `{ type, testscenariomsg, clientId, agentSessionId? }` (Orch → Agent)
  - `dto/ScenarioCallback.kt` — `{ clientId, type, payload: JsonNode }` (Agent → Orch 콜백)
  - `service/TestScenarioStreamManager.kt` — `clientId`별 `Sinks.Many<ServerSentEvent>` + `clientId↔agentSessionId` 맵. `stream()`, `emit()`, 구독 종료 시 정리.
  - `service/TestScenarioAgentClient.kt` — `POST {agentBaseUrl}/testscenario/scenariostep`.
  - `controller/TestScenarioController.kt` (`/api`) — `GET .../{clientId}/stream`(SSE), `POST .../{clientId}/message`.
  - `controller/AgentTestScenarioCallbackController.kt` (`/internal/api/agents`) — `POST .../testscenario/{agentSessionId}` → emit.
- [ ] **Step 2: Tests** — `TestScenarioPipelineIntegrationTest` (WebTestClient)
  - TC-1 콜백 → SSE 전달 / TC-2 아웃바운드 body / TC-3 매핑·멀티턴 / TC-5 스트림 격리 / TC-6 미등록 clientId graceful.
  - (TC-4 type 구분, TC-7 정리는 단위 수준에서 커버 가능하면 포함.)
- [ ] **Step 3: Rollout / Rollback** — 기능 플래그 없음. 신규 엔드포인트 추가만이라 기존 동작 영향 없음. 롤백은 `git revert`.

## Validation

- **Commands to run:**
  - `./mvnw -Dtest=TestScenarioPipelineIntegrationTest test`
  - `./mvnw test` (전체 회귀)
- **Expected output:** BUILD SUCCESS, 신규 통합테스트 통과, 기존 테스트(DB/WebSocket) 무영향.

## Risks & Rollback

- **Risks:**
  - Agent 실제 콜백 계약(특히 body의 `clientId` echo)이 미확정 → 스텁 기준 검증. 실제 연동 시 조정 필요.
  - SSE 구독 전 콜백이 먼저 도착하는 레이스 → 스트림 매니저의 버퍼/드롭 정책 문서화(TC-6).
- **Rollback steps:** 신규 파일 제거 / `git revert` (기존 코드 미변경이라 영향 격리됨).

## Open Questions

- Agent 콜백 body에 `clientId`를 실제로 넣어줄 수 있는지 Agent 팀 확인 필요 (현재는 이 전제로 구현).
- SSE 구독 전 도착한 콜백 이벤트: 드롭 vs 짧은 replay 버퍼 — MVP는 드롭 + 경고 로그로 시작.
</content>
