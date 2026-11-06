# 2026-07-24 — Knowledge(구 "Fact") 도메인 정의 및 저장 설계

- Date: 2026-07-24
- Jira: None (추후 부여)
- Status: Draft

## Goal

Agent가 게임에 대한 판단을 내릴 때 참고하는 **프로젝트 스코프 지식 저장소**를 정의·구현한다.
- 입력원(source): ① 사용자가 선택적으로 준 기획서(project_document)에서 추출한 내용, ② Agent가 판단 중 "필요하다"고 만든 지식.
- 저장: 각 지식을 **행(row) 단위**로 저장하고, 유연한 부분만 JSONB로 담는다.
- 접근: **projectId 키**로 영구 접근(세션 만료와 무관). Agent는 Orche 내부 API로 읽고/쓴다.

## Non-goals

- **시맨틱 검색(벡터 임베딩/RAG 랭킹)·전문검색** — MVP는 projectId+type 조회까지. 임베딩/tsvector는 후속.
- **entity별 세밀 분해(row-per-entry)** — 지금은 타입당 1행(content=섹션 통째). 개별 mechanic 단위 조회/폐기가 필요해지면 후속 승격.
- **중복 제어(dedup)** — 재추출 멱등(문서×타입 upsert)만. entity별/시맨틱/content-hash dedup은 후속. (사용자가 같은 파일을 이름 바꿔 넣는 케이스 없음 가정 → 느슨)
- **S3 파일 레벨 중복 제거** — 동일 파일 업로드 시 object 재사용(content-hash/ETag)은 후속. 지금은 이대로.
- **Agent QA-time 지식 쓰기** — 현재 Agent는 `/extract`(문서 요약)만; QA 중 자체 판단 삽입은 없음 → 생기면 그때 `source='AGENT'` 등 확장.
- **Agent 쪽 추출 로직(`/extract`)** — Agent 소관(이미 구현됨). 본 계획은 Orche의 저장/조회.
- **QA Cycle 오케스트레이션** — 별도 플랜(`2026-07-24-qa-cycle-orchestration-entrypoint.md`).

## Context / Constraints

### 개념 & 이름
- 임시명 "Fact" → **`Knowledge` / `KnowledgeEntry`** 로 변경 제안(대안: `GameKnowledge`, `Codex`). `Context`/`Memory`는 기존 `unity_context`/`game_context`·세션과 혼동되어 회피.
- 이하 문서는 잠정적으로 `Knowledge`/`KnowledgeEntry`로 표기.

### 스코프 결정 (핵심)
- **projectId 스코프**가 1순위: 지식은 "게임에 대한" 영구 자산이며 세션·시나리오·QA run보다 오래 산다.
- ⚠️ **절대 sessionId로 키잉하지 않음** — 세션 TTL 만료 후 접근 불가 문제. Orche가 컨텍스트에서 projectId를 알고 있으므로 Agent는 그걸로 접근.
- (선택 차원) gameBuildId/버전, scenarioId로 **좁히는 태그**는 컬럼/metadata로 부가. 기본 키는 projectId.

### 저장 모델 결정 (확정 — row-per-TYPE)
- 입력원은 사실상 **문서(기획서)뿐**. Agent `/extract`가 문서→`GameContext`(고정 8섹션) 요약본을 냄. (QA 중 Agent의 자체 지식 삽입은 **현재 없음** → Deferred.)
- **문서 1개 → 타입(섹션)별 1행**으로 저장. Agent가 **타입별로 뽑기를 원함**(`mechanics만`, `entities만`) → 타입이 조회 단위.
- **row-per-ENTRY(개별 mechanic 1행) 아님, 단일 blob(문서 통째) 아님 — 그 중간 "타입당 1행".**
- 각 행의 `content`는 해당 타입의 요약 콘텐츠(JSONB): `overview`=객체, 나머지=배열.
- **재추출 멱등**: 같은 (project, 문서, 타입)이면 교체(upsert). 사용자가 같은 파일을 이름 바꿔 중복 업로드하는 케이스는 없다고 보고 **entity별/시맨틱 dedup은 하지 않음**(느슨).

### 이름 (확정)
- **이 테이블 = `game_context`** (문서에서 추출한 요약. Agent `/extract` 산출물명과 일치).
- 넓은 "지식창고"는 도메인/모듈 개념으로만(예: `knowledge` 패키지). 그 안에:
  - `game_context` — **문서 추출 요약(지금 구현)**
  - (향후) Agent가 QA 중 스스로 판단해 넣는 데이터는 **별도 테이블**(예: `agent_insight`) — 수명주기/스키마가 달라 분리.

### 확정 스키마 `game_context`
| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | BIGSERIAL PK | |
| project_id | BIGINT NOT NULL | **스코프 키**(인덱스). FK는 project 도메인 확정 시(논리참조) |
| source_document_id | BIGINT NOT NULL | **출처**(원본 추적) → `project_document.id`. 이거 하나로 파일명·S3 `object_key`·버전 전부 도달. 다른 project면 문서행 자체가 달라 자연 분리 |
| type | VARCHAR(20) NOT NULL | `overview\|screens\|mechanics\|entities\|progression\|flows\|glossary\|misc` (GameContext 8섹션 고정 = 조회 단위) |
| content | JSONB NOT NULL | 해당 type의 Agent 요약 콘텐츠(overview=객체, 나머지=배열) |
| created_at / updated_at | TIMESTAMPTZ | R2DBC auditing |

제약/인덱스:
- `UNIQUE (project_id, source_document_id, type)` — 문서×타입 유일 → 재추출 upsert 앵커.
- `INDEX (project_id, type)` — Agent의 타입별 조회.

**S3 원본 추적**: `source_document_id → project_document.object_key(S3 키) → presigned URL`. **이 참조 하나로 충분** — S3 경로 문자열을 행에 복제(denorm)하지 않음(drift 방지, 불필요).

### 기존 코드/제약
- R2DBC(리액티브), JSONB는 `io.r2dbc.postgresql.codec.Json`(컴파일 스코프) — TestScenario에서 이미 사용 패턴 있음.
- 기획서: `project_document`(S3 메타 + `parse_status` PENDING) 이미 존재 → 파싱 결과를 `knowledge_entry(source=USER_DOCUMENT, source_document_id=…)`로 적재하는 흐름과 연결.
- Agent 내부 통신은 `/api/orchestration/**`·`/ws/sdk` 등 **permitAll**(엔드유저 JWT 아님). Knowledge write/read도 이 내부 경로에 두면 Agent가 인증 없이 접근.

## Approach (Checklist)

- [ ] **Step 0: Recon** — `project_document`(object_key/presigned) 현황, JSONB(R2DBC `Json`) 사용 패턴, Agent `/extract` 호출 트리거 지점(업로드 후 누가 부르나), 내부 엔드포인트(`/api/orchestration/**`) 관례. 이름 최종 확정(Knowledge).
- [ ] **Step 1: 도메인/마이그레이션** — `game_context` 마이그레이션(Vn: 테이블+UNIQUE(project_id,source_document_id,type)+INDEX(project_id,type)) + 엔티티/리포지토리(R2DBC, content=`io.r2dbc.postgresql.codec.Json`). auditing. (모듈은 `knowledge` 아래 둬도 됨.)
- [ ] **Step 2: 적재(쓰기) 계약** — 문서 추출 결과 저장: `POST /api/orchestration/game-context` `{projectId, sourceDocumentId, gameContext}` → **8개 type으로 분해 → 각 type upsert**(같은 문서×타입 교체). (또는 Orche가 `/extract`를 직접 호출 후 저장까지.)
- [ ] **Step 3: 조회(읽기) 계약** — `GET /api/orchestration/game-context?projectId=&type=` → 해당 타입 행들(문서별) 반환(+ 병합 옵션). Agent가 타입별로 뽑아 판단에 사용.
- [ ] **Step 4: 재추출 멱등** — 같은 (project, source_document_id, type) upsert로 교체. entity별/시맨틱 dedup은 하지 않음(Deferred).
- [ ] **Step 5: 기획서/추출 연동** — 업로드된 `project_document` → `/extract`(presigned url) → gameContext → 저장. 트리거 시점(업로드 즉시/수동) 확정.
- [ ] **Step 6: 테스트** — 멤버십/스코프, 문서→타입행 분해 저장, 타입별 조회, 재추출 upsert 멱등, source_document_id로 S3 추적, 세션과 무관하게 재조회.

### 최소 슬라이스
1. `game_context` 모델 + 문서 추출 적재(타입 분해 upsert) + 타입별 조회 → 독립 구현·테스트 가능.
2. `/extract` 자동 트리거, 전문/벡터 검색, entity별 분해, Agent QA-time 쓰기(별도 테이블)는 후속.

## Validation
- **Commands to run:**
  - `./mvnw -q compile`
  - 신규 `KnowledgeIntegrationTest`: write→read(projectId), 동시 INSERT 2건 모두 보존, 다른 project는 격리, topic 필터, "세션 없이도" projectId로 재조회.
- **Expected output:** BUILD SUCCESS, 신규 테스트 green.

## Risks & Rollback
- **Risks:**
  - 이름 재변경 비용 → Step 0에서 확정 후 코드 진입.
  - metadata JSONB 남용으로 조회 조건이 blob 안으로 들어가면 인덱싱 어려움 → 조회 키는 반드시 **정규 컬럼(topic/source)** 로.
  - 지식 폭증/노이즈(Agent가 쓸데없이 많이 씀) → `superseded`/topic 정규화/후속 dedup·랭킹으로 대응.
  - project 도메인 FK 미확정(타팀 개발 중) → 논리참조(FK 없이 컬럼+인덱스), 확정 시 FK 추가(TestScenario 선례와 동일).
- **Rollback steps:** `knowledge_entry`는 additive(신규 테이블) → 미사용 시 무해. 기능 플래그/`git revert`.

## Open Questions
- **이름 최종**: `Knowledge`/`KnowledgeEntry` vs `Codex` vs `GameKnowledge`? (추천: Knowledge)
- **스코프 차원**: projectId 단독? gameBuild/scenario로 좁히는 태그가 실제로 필요한가?
- **topic 체계**: 자유문자열 vs 사전정의 enum(규칙/UI/씬/기획 등)? Agent 생성이면 자유문자열이 현실적.
- **content 형식**: 순수 text(LLM 친화) + metadata JSONB 조합이면 충분한가, 아니면 content 자체도 구조화 JSONB가 필요한 케이스가 있나?
- **읽기 주도**: Agent가 직접 조회 호출 vs Orche가 관련 지식을 자동 주입(RAG) — MVP는 명시적 조회 API. 자동 주입은 후속?
- **중복/갱신**: 같은 지식 재학습 시 append+supersede로 충분한가, upsert 키(project+topic+식별자)가 필요한가?
- **기획서 적재 트리거**: 업로드 즉시? 파싱 완료 후? (파서 미구현이라 계약만.)
