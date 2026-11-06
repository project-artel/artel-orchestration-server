# 2026-08-04 — 런 스코프 저작 챗봇: 복수 시나리오 추가/수정 + 카드 커밋 토글

- Date: 2026-08-04
- Jira: ARTEL-206 (Epic ARTEL-11) — Step 5-L2 / Step 6 확장
- Status: Draft

## Goal

하나의 **런 스코프 챗봇**으로 자연어 저작을 완성한다:

1. **추가** — 새 시나리오 여러 개 저작 (기존 Step 4/5).
2. **수정** — 사용자가 자연어로 겨냥한 **기존 시나리오(들)** 를 Agent가 판단해 수정. 한 턴에 **수정 N + 추가 M 혼합** 가능. 런 전체가 아니라 **지목된 것만**.
3. **대화 연속성** — 세션/채팅이 **런 단위**로 유지(시나리오 전환해도 대화 안 끊김).
4. **저장 방식 토글** — 자동저장(현 A) ↔ 카드 커밋(제안→사용자가 적용/Drop/추가수정). 사용자별 설정, 기본 **카드 ON**.
5. **수동 직접편집 공존** — composition 패널의 손편집은 토글과 무관하게 항상 즉시 저장.

## Non-goals

- Knowledge 툴을 저작에 연결(별도, 연기 유지).
- 삭제를 Agent/자동으로 수행 — **삭제는 계속 사용자 수동**. 빈 `scenarios[]`는 **무동작**(삭제 아님).
- game_context/unity_context 확장(현 상태 유지).
- HNSW 인덱스 등 벡터 성능 최적화(연기 유지).

## Context / Constraints

**현재 전부 testScenarioId 스코프 (재키 대상):**
- DB `test_scenario_message` = `(test_scenario_id, app_user_id)` FK CASCADE (V6).
- 엔드포인트 `/api/test-scenario/{tsid}/{messages,stream,message}` (TestScenarioController).
- 라우팅 키 `userId:testScenarioId`, WS 세션도 이 키로 맵핑.
- `saveMessage(session.testScenarioId, ...)` (TestScenarioAgentService).

**이미 런-ready (재사용):**
- Agent 세션이 `run_id`/`project_id` 실어받음(AgentSessionOpenRequest) → Agent 대화메모리는 한 런=한 세션이면 자연 연속.
- `reconcileScenarios`가 이미 runId에 INSERT.
- FE `RunEditPage`(`/test-runs/:runId/edit`) 런 라우트 존재(현재 per-scenario 스튜디오로 redirect).
- FE 수동 저장 primitive: `useScenarioComposition` + `setScenarioCases(tsId, caseIds[])` (`PUT /api/test-scenario/{id}/cases`).

**설계 결정 (사용자 확정):**
- 저장 엔진은 **하나(reconcile upsert, Layer 2)**, 트리거만 2개(자동/수동 커밋). 토글이 선택.
- 커밋 경로 = **통일된 reconcile 엔드포인트**(기존 per-scenario 재사용 X) → add/edit 분기 FE 중복 방지.
- 토글 스코프 = **사용자별 지속 설정**, 기본 카드 ON.
- 토글은 **Agent 제안 경로만** 게이팅. **수동 편집 경로는 항상 켜짐**.
- add/edit 판단 주체 = **Agent**(자연어→기존 id 지목 or 신규 null). 카드 리뷰가 오판 안전망.
- `TestCase.id`는 FE에서 **문자열**, Agent `case_ids`는 **int** → 경계 변환.
- pre-prod(운영 데이터 없음, ARTEL-177 미완) → 마이그레이션은 데이터 이전 불필요.

## Approach (Checklist)

### Step 0: Recon (완료)
- [x] 채팅 저장 위치·키 확인(`test_scenario_message` = ts_id 스코프).
- [x] 전송 3엔드포인트·라우팅 키(`userId:testScenarioId`) 확인.
- [x] FE 마운트(TestScenarioPage `st-chat`)·RunEditPage redirect 구조 확인.
- [ ] AgentSession 맵 키·세션 오픈/턴 코드 상세 재확인(TestScenarioAgentService 전체).
- [ ] app_user 설정(preference) 저장 위치 유무 확인(토글 지속용).

### Step 1: Orche — 채팅/전송/세션 runId 재키
- [ ] 마이그레이션 `V21__create_test_run_message.sql` — `(test_run_id, app_user_id)` FK CASCADE, 인덱스 `(test_run_id, app_user_id, created_at)`. `test_scenario_message`는 pre-prod라 드롭 or 남겨두고 미사용(결정: 드롭).
- [ ] `TestRunMessageEntity` / `TestRunMessageRepository`(`findByTestRunIdAndAppUserIdOrderByCreatedAtAsc`).
- [ ] 런 스코프 엔드포인트 `/api/test-run/{runId}/{messages,stream,message}` (controller). 라우팅 키 `userId:runId`.
- [ ] `TestScenarioAgentService`/`TestScenarioService`: 세션 맵 키 runId, `saveMessage(runId,...)`, relay/stream/getMessages by runId. 한 런=한 Agent 세션.
- [ ] SecurityConfig permitAll/인증 경로 동반 확인.

### Step 2: Orche — reconcile Layer 2 upsert + 수동 커밋 엔드포인트
- [ ] `ScenarioResult`에 `scenario_id: Long?`(=null 추가, 값 수정) 추가.
- [ ] `reconcileScenarios`를 upsert로: 항목별 id 있으면 payload+케이스링크(test_scenario_case) UPDATE, null이면 INSERT+런 append. **빈 배열=무동작 유지**, deleteBy 없음.
- [ ] 수동 커밋 엔드포인트 `POST /api/test-run/{runId}/scenarios/commit` (body=선택/편집된 scenarios[]) → 같은 reconcile 호출.
- [ ] 자동/카드 토글 전달: relay 메시지 or 세션 오픈에 `autoApply: Boolean`. true면 결과 도착 시 자동 reconcile, false면 제안만 스트림.

### Step 3: Orche — 런 현재 시나리오 컨텍스트를 Agent에 전달
- [ ] 세션 오픈/턴 입력에 런의 현재 시나리오 목록(id, title, description, case_ids) 실기(`AgentTurnMessage.draft` 자리 대체 → `current_scenarios`).
- [ ] 조회: test_run_scenario + test_scenario_case로 런의 시나리오/케이스 구성 로드.

### Step 4: Agent — 계약/프롬프트
- [ ] `ScenarioPlan.scenario_id: int | None` 추가(none=추가, 값=수정).
- [ ] 턴 입력으로 `current_scenarios` 수신 → 시스템/휴먼 프롬프트에 주입.
- [ ] 프롬프트 v2: "사용자가 겨냥한 시나리오만 판단해 수정(기존 id echo) vs 추가(null)", 지목 못한 건 건드리지 말 것, 혼합 배치 허용.
- [ ] 테스트: 수정지목/추가/혼합/무매치(빈배열) 케이스.

### Step 5: FE — 런 챗봇 상향 + 카드 + 토글
- [ ] `RunEditPage`: redirect 제거하고 챗봇 호스팅(런 레벨). `useRunSession(runId)`.
- [ ] `scenarioApi`→run 스코프 API; `parseStreamEvent`가 `scenarios[]`(scenario_id, case_ids int→string) 파싱.
- [ ] 카드 UI: 항목별 🆕추가/✏️수정(#id) 표식, **적용/Drop/추가수정**. 커밋 시 `POST .../scenarios/commit`.
- [ ] 토글 UI(설정, 기본 ON). 값 세션/메시지로 Orche 전달.
- [ ] 수동 편집 패널(composition) 공존·항상 저장 유지. 빈 제안 시 카드 페이지 안 뜸.

## Validation
- **Commands to run:**
  - Orche: `TESTCONTAINERS_RYUK_DISABLED=true ./mvnw test`
  - Agent: `uv run --extra dev pytest`
  - FE: `npm run build` / `npm test`
- **Expected output:** 신규 upsert/커밋/재키 테스트 통과. 수동 편집 회귀 없음. 빈 배열 무동작.

## Risks & Rollback
- **Risks:**
  - 계약 변경(런 스코프 엔드포인트 + `scenario_id` + `current_scenarios`) → **Agent·Orche·FE 3자 동시 배포 필수**.
  - upsert 오판으로 엉뚱한 시나리오 덮어쓰기 → 카드 커밋(기본 ON)이 안전망. 자동저장(OFF)에서는 위험 존재.
  - 세션 재키 시 진행 중 대화 유실 가능(pre-prod라 허용).
- **Rollback steps:** 토글 기본을 카드 ON으로; 문제 시 자동 reconcile 비활성. 계약 v1(단일·insert-only)로 되돌림. 마이그레이션 revert.

## Open Questions
- 토글 지속 저장 위치: 신규 app_user 컬럼 vs 별도 preference 테이블 vs 클라이언트 로컬(그럼 relay마다 전달). → Step 0에서 기존 preference 저장 유무 확인 후 결정.
- 수동 커밋 시 "추가수정"(카드 내 편집)을 어디까지 허용할지(제목/설명/케이스 순서) — 최소 케이스 목록 편집부터.
