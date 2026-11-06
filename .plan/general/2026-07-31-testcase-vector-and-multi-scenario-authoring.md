# 2026-07-31 — TestCase 벡터 검색 + 런 스코프 복수 시나리오 저작 (공용 임베딩 모듈 추출)

- Date: 2026-07-31
- Jira: ARTEL-206 (TestScenario 구조 변환으로 인한 Agent 호출 방식 리팩토링)
- Status: Draft
- Repos: `artel-orchestration-server`(주 — 벡터 인프라/검색/reconcile) + `artel-agent-server`(저작 에이전트 툴 루프) + `artel-home`(FE, 대부분 ARTEL-198 완료)
- Supersedes: `artel-agent-server/.plan/general/2026-07-30-run-scoped-multi-scenario-case-authoring.md`
  (그 초안은 "케이스 검색 = 텍스트" 전제. 아래에서 **벡터 확정**으로 대체하고, 저작 세션에 채널/툴 기계장치가 없다는 사실을 반영해 범위를 넓힘.)

## Goal

3-tier(TestRun → TestScenario → TestCase)에서, 시나리오 저작 챗봇을 **런 스코프·복수 시나리오·케이스 연결**로 확장한다. 사용자가 런 대시보드에서 자연어로 요청하면:

1. Agent가 자연어를 해석하고,
2. **기존 TestCase를 벡터 검색**(케이스는 Orche에만 있음)해 적합하게 매핑하며,
3. 필요 시 **한 번의 요청으로 여러 시나리오로 분해**하고,
4. Orche가 각 시나리오를 생성 + 케이스를 연결(`test_scenario_case`) + 런에 추가(`test_run_scenario`)한다.

케이스 검색은 **pgvector 기반**으로 간다(아래 근거). knowledge 도메인에 이미 있는 임베딩/벡터 기계장치와 겹치므로, **복붙이 아니라 공용 모듈로 추출**해 knowledge와 testcase가 함께 쓴다.

## 근거 — 왜 벡터인가 (실서비스 규모 기준)

- 실서비스 배포 + **TC 지속 누적**. 수년 라이브 게임의 회귀 스위트는 **초기 등록만으로도 프로젝트당 몇만 건** 가능.
- 이 규모에선 텍스트(ILIKE)도 어차피 인덱스(pg_trgm/tsvector)가 필요하고, **다수 category·교차언어(ARTEL-177 열 언어 미확정)** 로 lexical recall 구멍이 큼 → 의미검색이 선택이 아니라 요건.
- 쓰기 비용은 문제 아님: knowledge 패턴이 **임베딩을 쓰기 경로에서 떼어 이연 워커 + 별도 테이블**로 처리 → 케이스 INSERT 무변. 임베딩 API $도 무시가능(프로젝트당 ~$0.35/30K건).
- **스키마 소급이 비쌈**(몇만 행 라이브 테이블에 사후 임베딩 백필+인덱스는 고통) → **벡터 테이블은 지금 확정**. 단 HNSW·하이브리드(BM25)는 **소급 싼 인덱스/레이어라 임계·증거에 따라 후속**.
  - 근거 상세는 이 세션의 조사 결과(knowledge `V18` 쓰기경로 측정 + `artel-sdk` 씬 스캔 분석)에 있음. 요약: HNSW는 프로젝트당 행수가 임계 넘을 때 `CONCURRENTLY` 온라인 추가.

## Non-goals

- **TestCase "생성"은 범위 밖** — ARTEL-177(담당: 정의진), "기능 테스트 명세를 CSV로 출력". 본 작업은 **검색·연결(매핑)만**. 케이스는 ARTEL-208(CSV→적재, 머지됨)이 채운 라이브러리에 이미 존재.
- QA 실행 에이전트(`app/agents/qa/`)는 안 건드림. 대상은 **시나리오 저작 에이전트**(`app/agents/scenario/`).
- **HNSW 인덱스 / 하이브리드(BM25) 레이어**는 이번 범위 밖(스키마엔 벡터 컬럼만, 인덱스는 후속). 단 자리는 열어둔다.
- **케이스 QUERY 벡터 생성(hypothetical questions)** 은 초기 범위 밖. 케이스는 **CONTENT 1벡터**로 시작(아래 Open Questions).

## Context / Constraints

### 현재 저작 흐름 (단일 시나리오, 확인 완료)
- Orche `TestScenarioAgentService`가 **Orche가 WS 클라이언트**로서 Agent에 dial-out.
  - `POST {agent}/sessions` body `AgentSessionOpenRequest{user_input, unity_context(빈맵), game_context, model, locale}` → `{session_id}`. **`run_id` 없음.**
  - `WS {agent}/sessions/{session_id}`: 접속 즉시 첫 result, 이후 `AgentTurnMessage{type:"turn", user_input, draft}`, 종료 `{type:"close"}`.
  - 수신 `ScenarioStreamEvent{type, message, scenario}` — `type`은 enum 아닌 문자열. `scenario=ScenarioDraft{title,description,steps[step,title,state,action,expected]}`.
  - `handleInbound`(`TestScenarioAgentService.kt:205`)는 `result|error|closed`만 앎 → SSE 중계 + `test_scenario.payload` UPDATE.
  - 세션키 = `userId:testScenarioId`(`TestScenarioService.kt:169`). **런 스코프 아님.**
- game_context = **최신 빌드의 sceneScan JSON**(`gameContext():147`). SDK가 등록 시 스캔한 **UI 인벤토리**(씬트리+UI텍스트+버튼 interactable+에셋이름). **좌표·onClick배선·게임필드값·규칙 없음** → 추상어→구체 기능 그라운딩은 **휴리스틱 수준**.

### 케이스 검색 채널 = 반드시 신설 (플랜 초안의 오판 정정)
- **저작 WS는 전송로만 양방향**이고, 그 위에 케이스 검색을 **얹을 기계장치가 없다**:
  - Agent 저작 에이전트(`app/agents/scenario/agent.py:39-57`)는 **툴 없는 structured LLM 1콜** → 생성 도중 검색 프레임을 쏠 자리가 없음.
  - Agent 저작 세션(`app/api/sessions.py:91-151`)에 **channel/waiter 없음**. QA만 있음(`app/qa/channel.py`).
  - Orche `handleInbound`에 인바운드 프레임 라우팅 없음(result만).
- 청사진(QA 런 WS): **삼각편대** = `QaRunChannel.search_knowledge`(`channel.py:201-306`, 프레임+correlation+timeout+3분기) + `@tool search_knowledge`(`tools.py:236-290`, 예산/포맷) + `create_agent`/`astream` 루프(`runner.py:150-186`). Orche 수신은 `QaAgentInboundRouter.routeKnowledgeSearch`(`:264`), **projectId는 payload 아닌 세션 바인딩(qa_try→projectId)에서 파생**.
- → 저작에도 이 삼각편대를 이식해야 함(전송로는 재사용, 커넥션 신설 불필요).

### 데이터 모델 (V17, 존재)
- `test_case`(`TestCaseEntity`: projectId, category, title, precondition?, expected, verificationStatus[DRAFT/VERIFIED/BROKEN], lastVerifiedBuildId?).
- `test_scenario`(payload JSONB, 기존 챗봇 테이블), `test_run`(projectId,name,description?).
- 링크: `test_scenario_case`(testScenarioId, testCaseId, **position** uniq), `test_run_scenario`(testRunId, testScenarioId, **position** uniq).
- `qa_try`는 `test_scenario_id` 직접 참조, `test_run` 미연결(후속).

### knowledge 임베딩 기계장치 (추출 소스)
- `V18__create_knowledge_embedding.sql` — `knowledge_embedding`(id, knowledge_id, kind[QUERY/CONTENT], model, source_text, `embedding vector(1024)`, attempts, last_error, ...). **큐 겸 벡터스토어 이중 용도**(pending: 둘 다 NULL). **HNSW 없음**(의도적). 인덱스 3개(lookup/pending uniq/queue).
- `KnowledgeEmbeddingRepository.kt`(raw `DatabaseClient` SQL: seedPending/claim(FOR UPDATE SKIP LOCKED)/replacePendingWithVectors(DELETE+N INSERT)/recordFailure/discardFor/searchNearest).
- `KnowledgeEmbeddingBackfillWorker.kt`(seed→claim→generateQueries→embed→store/fail), `KnowledgeBackfillScheduler.kt`(`@Scheduled(fixedDelay)` + `@ConditionalOnProperty`), `KnowledgeBackfillProperties.kt`(interval 60s/batch 16/maxAttempts 5/model).
- `agent/AgentKnowledgeEmbeddingClient.kt`(`POST /knowledge-queries` + `POST /embed`), `KnowledgeEmbeddingAgent.kt`, `KnowledgeEmbeddingDtos.kt`.

## Approach (Checklist)

> 원칙: **공용 모듈 추출은 별도 파일로 점진적으로**(한 커밋에 몰지 않음). 각 추출 단계마다 **knowledge 테스트 그린 유지**가 회귀 게이트다. 순서는 Orche 인프라 → Orche 검색 → Agent 저작 → Orche reconcile → FE.

### Step 0: Recon (완료/잔여)
- [x] Orche 저작·knowledge WS·TestCase·3-tier 매핑 (Explore).
- [x] Agent 세션·저작 에이전트·QA 채널/툴 패턴 매핑 (Explore).
- [x] knowledge pgvector 쓰기경로 측정, SDK 씬 스캔 분석 (Explore).
- [ ] `KnowledgeEmbeddingBackfillWorker`가 knowledge에 얼마나 결합돼 있는지(추출 시 남길 seam) 정독.
- [ ] Agent `/embed` / `/knowledge-queries` 계약이 도메인 중립인지 확인(케이스 재사용 가능 여부).
- [ ] 정의진(ARTEL-177) 케이스 열/언어 확정 — 임베딩 소스 텍스트 조합 + 모델 다국어 요건.

### Step 1 (Orche): 공용 임베딩 모듈 추출 — **별도 파일, 점진적, knowledge 무회귀**
> knowledge의 임베딩 기계장치를 `common/embedding/`(신규 패키지)로 일반화. 도메인별 차이는 **소스 프로바이더 인터페이스**로 뺀다.
- [ ] **1a. 벡터 유틸/DTO 추출** — `toVectorLiteral`, `/embed` 요청·응답 DTO, `EmbeddedText` 등 도메인 무관한 것부터 `common/embedding/`로 이동(파일 단위). knowledge는 import만 교체. 테스트 그린.
- [ ] **1b. 공용 임베딩 저장소 추출** — `EmbeddingRepository`(raw SQL을 `table`/`ownerIdColumn`으로 파라미터화: seedPending/claim/replacePendingWithVectors/recordFailure/discardFor/searchNearest). knowledge는 이 위에 얇은 wrapper. **DDL 형태 공유**(다음 스텝의 test_case_embedding이 같은 컬럼 셋).
- [ ] **1c. 공용 백필 워커/스케줄러 추출** — `EmbeddingBackfillWorker`가 `EmbeddingSource`(주어진 owner id 배치 → 임베딩할 source_text[] 생성)와 `kind`를 받도록. knowledge 소스 = `/knowledge-queries`로 QUERY 3벡터. 스케줄러/프로퍼티는 도메인별 섹션(`artel.knowledge.backfill.*`, `artel.testcase.embedding.*`).
- [ ] **1d. knowledge를 모듈 소비자로 전환** — 기존 동작 동일 유지, `./mvnw test`로 knowledge 스위트 회귀 0 확인(추출 게이트).

### Step 2 (Orche): TestCase 벡터 스키마 + 백필
- [ ] **2a. Flyway 마이그레이션** `V{다음번호}__create_test_case_embedding.sql` — `knowledge_embedding` 미러(owner=`test_case_id`, `embedding vector(1024)`, kind CHECK, 이중용도 큐, 인덱스 3개). **HNSW 없음**(주석으로 "임계 시 CONCURRENTLY 추가"). ⚠️ 머지 직전 develop 최신 버전번호 확인(Flyway 충돌 상습).
- [ ] **2b. TestCase 임베딩 소스** — `EmbeddingSource` 구현: 케이스 행 → `category/title/precondition/expected` 합성 텍스트를 **CONTENT 1벡터**로(초기; QUERY 생성 없음). Agent 쿼리생성 호출 불필요 → `/embed`만.
- [ ] **2c. 208 적재에 시딩 훅** — `TestCaseSpecService.ingest`/`TestCaseService`가 케이스 upsert 시 pending 임베딩 행 seed(내용 변경 시 `discardFor`로 재임베딩, tag/status만 바뀌면 skip — knowledge 선례).
- [ ] **2d. 버스트 드레인 설정** — 초기 몇만 건 대비 batch/interval을 knowledge 기본(16/60s)보다 크게 잡을 수 있게 프로퍼티화. 기본 enabled=false(로컬/테스트), 배포 on.

### Step 3 (Orche): TestCase 벡터 검색 서비스
- [ ] **3a. 검색 저장소** — `searchNearest`를 **project_id 필터 먼저 + category 필터(선택) → 벡터 랭킹**으로. 공용 `EmbeddingRepository.searchNearest`에 필터 파라미터 확장.
- [ ] **3b. 검색 서비스** `TestCaseSearchService` — 쿼리 텍스트를 Agent `/embed`로 벡터화(knowledge와 동일 모델·경로 재사용) → searchNearest → 히트에 `{id,title,category,precondition,expected,verificationStatus,score}` 실어 반환.
- [ ] **3c. category 열거** — `SELECT DISTINCT category`(프로젝트 스코프) = 기능 카탈로그. 검색과 별도의 저렴한 그라운딩 프레임으로 노출 검토.

### Step 4 (Agent): 저작 에이전트를 툴 루프로 + 복수 시나리오 출력
- [ ] **4a. 세션 오픈 확장** — `OpenSessionRequest`에 `run_id`(+`project_id`) 추가(`app/api/sessions.py`, `app/sessions/*`). 런 스코프 컨텍스트 + **런의 현재 시나리오/caseId 구성**(수정·중복방지용, 지금 단일 draft의 런 단위 확장).
- [ ] **4b. 저작 세션 채널 신설** — `QaRunChannel.search_knowledge` 복제로 `search_test_cases()` + `TEST_CASE_SEARCH`/`TEST_CASE_SEARCH_RESULT` 프레임·waiter·timeout·3분기. `app/api/sessions.py`의 `while True` 루프가 turn 처리 중 들어온 result 프레임을 **waiter로 디스패치**(QA `service.deliver` 패턴)하도록 재구성.
- [ ] **4c. 에이전트를 툴 루프로** — `ScenarioAgent.run`을 단일 콜 → `create_agent`+`astream`(QA `runner.py` 패턴), `search_test_cases` 툴 바인딩, 검색 예산/타임아웃 가드(QA: 6회/20s).
- [ ] **4d. 출력 스키마 복수화** — `ScenarioAgentResult` → `{message, scenarios:[{title, description, caseIds:[...]}]}`. **steps 제거/deprecate**(구 데이터 호환은 Open Q). `app/agents/scenario/schemas.py`.
- [ ] **4e. 프롬프트 v2** — 단수 → "런 목표를 여러 시나리오로 분해, 검색된 케이스를 caseId로 매핑; 매칭 케이스 없으면 빈 시나리오 대신 '먼저 케이스 필요'를 message로". `app/prompts/scenario/v2/`.

### Step 5 (Orche): TEST_CASE_SEARCH 수신 + result reconcile
- [ ] **5a. 저작 세션 런 스코프화** — 세션키 `userId:testScenarioId` → 런 기반, `run_id`/`project_id`를 세션 오픈에 전달(`AgentSessionOpenRequest` 확장). projectId는 프레임이 아닌 **세션 바인딩에서 파생**(knowledge 원칙).
- [ ] **5b. TEST_CASE_SEARCH 인바운드 핸들러** — 저작 WS `handleInbound`에 프레임 분기 추가: 쿼리 수신 → `TestCaseSearchService`(project는 세션 바인딩 파생) → `TEST_CASE_SEARCH_RESULT` 프레임을 `correlationId=messageId`로 `outbound`에 회신. (QA `routeKnowledgeSearch` 대칭.)
- [ ] **5c. result reconcile** — `scenarios[]` 수신 → 각 시나리오 생성 + `test_scenario_case`(position) 링크 + `test_run_scenario`(position) 런 조합. 한 트랜잭션/시나리오. ARTEL-198 `/cases`·`/scenarios` API 재사용 가능하면 재사용.
- [ ] **5d. 스트림 DTO 복수화** — `ScenarioStreamEvent`/SSE를 복수 시나리오 진행상황으로 확장, FE 중계.

### Step 6 (FE, artel-home): 잔여 (대부분 ARTEL-198 완료)
- [ ] 런 편집 셸 챗봇이 **런 스코프 세션**을 열고 복수 시나리오 생성 결과를 목록/Map·케이스 선택 상태에 반영.
- [ ] 케이스 0개 런 가드 + "먼저 케이스 필요" 안내.

### Step 7: Tests
- [ ] Orche: 공용 모듈 추출 후 knowledge 회귀 0(게이트). test_case_embedding seed/claim/store/discard + searchNearest(project/category 필터) 통합테스트. 208 적재→시딩 훅.
- [ ] Agent: `search_test_cases` 툴 단위(프레임 mock), 복수 시나리오 출력 파싱, session_ws 프레임 디스패치(turn vs search_result 구분).
- [ ] Orche: TEST_CASE_SEARCH 핸들러 + reconcile 통합(시나리오 N개 생성+링크+런 조합).
- [ ] 수동 e2e: 런 대시보드 → 자연어 → 벡터 케이스검색 → 복수 시나리오 생성 → Map 확인.

## Validation
- **Commands to run:**
  - Orche: `./mvnw test` (각 추출 스텝 후 knowledge 회귀 확인 포함)
  - Agent: `python -m pytest`
  - FE: `npm run build`
- **Expected output:** 각 스위트 통과 + 수동 e2e(자연어→벡터검색→복수 시나리오+케이스 연결) 동작. knowledge 검색/백필 회귀 0.

## Risks & Rollback
- **Risks:**
  - **공용 모듈 추출이 knowledge(ARTEL-186, 최근 머지)를 깨뜨림.** → 파일 단위 점진 추출 + 매 스텝 knowledge 테스트 게이트.
  - **계약 대폭 변경**(단일→복수, steps→caseIds, run_id 추가) — Agent/Orche/FE 3자 동시 정합. → v2 프롬프트/스키마 버전업, Orche/Agent 함께 배포.
  - **초기 몇만 건 임베딩 드레인 속도** — 기본 트리클(16/60s)이면 몇십 시간. → 버스트 파라미터(2d).
  - **HNSW 없는 순차스캔이 대형 프로젝트에서 느려짐.** → 임계 모니터링 후 `CREATE INDEX CONCURRENTLY`(무중단).
  - **교차언어 recall**(ARTEL-177 열 영문 + 한글 질의) — 다국어 임베딩 모델로 완화, 부족 시 하이브리드(BM25) 후속.
  - **Flyway 버전 충돌**(develop 상습) — 머지 직전 최신 번호 확인.
- **Rollback steps:** 계약 v1(단일 시나리오) 경로 유지/복귀, 신규 프레임 타입 비활성, 백필 `enabled=false`, test_case_embedding은 검색만 끄면 적재/다운로드 영향 없음.

## Open Questions
- **케이스 임베딩 kind**: CONTENT 1벡터로 시작(확정 제안) vs 처음부터 QUERY 생성(hypothetical questions, knowledge식 3벡터). 초기 CONTENT, recall 나쁘면 QUERY 승격.
- **Agent 쿼리생성 재사용**: `/knowledge-queries`를 케이스에도 쓸지(도메인 중립화) vs 케이스는 `/embed`만. → CONTENT면 `/embed`만으로 충분.
- **세션 스코프**: 완전 런 단위(한 세션 여러 시나리오) 확정 vs 시나리오 단위 유지+연결만. → 런 단위 지향(복수 출력 전제).
- **steps 필드**: 완전 제거 vs deprecate 유지(구 payload 호환·마이그레이션 필요 여부).
- **run→qa_try 배선**: 이번 범위 밖이지만 reconcile가 만든 런 구성이 QA 실행과 어떻게 이어지는지 후속 이슈 필요.
- **임베딩 모델**: knowledge와 동일 `text-embedding-3-large`(다국어 OK)로 통일 확정?
