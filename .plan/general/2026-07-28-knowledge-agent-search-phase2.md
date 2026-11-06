# 2026-07-28 — Knowledge: 현재 구조(as-built) + Agent 전용 지식 서칭 과제(Phase 2)

- Date: 2026-07-28
- Jira: (TBD)
- Status: **Phase 1 완료(저장·조회·hash dedup·tag 확정)**, Phase 2(하이브리드 검색)는 과제 정의 단계
- Branch: `refactor/migration-referencecontext-form-ARTEL-139`
- 선행 설계본: `2026-07-24-knowledge-fact-domain-design.md`, `2026-07-26-knowledge-domain-hybrid-search-redesign.md`
  (이 문서가 **최신 as-built** 기준. 위 두 문서와 달라지는 지점은 아래 "설계본 대비 바뀐 점"에 명시)

---

## 1. 배경 — knowledge란 무엇인가
`reference_context`(V10)를 폐기하고, 문서/QA에서 나온 **요약 지식(game_context)** 을 담는 통합 테이블
`knowledge`로 갈아엎었다. 문서/QA/시나리오를 테이블로 나누지 않는다(입력부가 전부 Agent).
신뢰도(문서 사실 vs Agent 추론) 구분은 스키마 분리 대신 **`source`/`tag`** 로 코드 레이어에서 다룬다.

소비자는 QA만이 아니다 — **시나리오 작성 · QA 진행 · 보고서 · Issue 작성** 의 Agent 태스크가 모두
이 지식창고에서 필요한 정보를 꺼내 쓴다(요약본 생성 자체는 성질이 달라 제외).

---

## 2. 현재 구조 (as-built, Phase 1)

### 2.1 스키마
```sql
-- V13__replace_reference_context_with_knowledge.sql
CREATE TABLE knowledge (
  id           BIGSERIAL PRIMARY KEY,
  project_id   BIGINT NOT NULL,
  source       VARCHAR(20) NOT NULL CHECK (source IN ('DOCS','QA')),
  source_id    BIGINT,          -- nullable: DOCS→project_document.id, QA→qa_try.id
  content_hash VARCHAR(64),     -- nullable: DOCS 파일 hash (멱등키 아님, 출처 스냅샷용 저장만)
  tag          VARCHAR(20) NOT NULL CHECK (tag IN (...)),  -- V15에서 넓힘(§3)
  summary      TEXT NOT NULL,   -- Agent 생성
  description  TEXT NOT NULL,   -- Agent 생성
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_knowledge_project_tag    ON knowledge (project_id, tag);
CREATE INDEX idx_knowledge_project_source ON knowledge (project_id, source);
```
- 마이그레이션: **V13**(knowledge 생성) · **V14**(project_document.content_hash + 부분 유니크) ·
  **V15**(tag CHECK 세분화). V12는 Issue PR #46 선점이라 회피.
- **강한 유니크 없음**(팀 결정: DB 제재 약하게). 파일 중복은 업로드 레이어에서 hash로 막는다(§2.4).

### 2.2 enum (우리가 정의, Agent가 이 토큰을 보냄)
- `KnowledgeSource`: **DOCS, QA** (`fromWire`로 대소문자 무시 파싱)
- `KnowledgeTag`: §3 참조

### 2.3 저장 파이프라인 (source별 transport)
| source | 경로 | 상태 |
|---|---|---|
| **DOCS** | FE 업로드 → Orche가 hash 검증(§2.4) → Agent `/extract` 호출 → 응답을 `KnowledgeService.store(source=DOCS)` | 완료 (`DocumentKnowledgeExtractionService`) |
| **QA** | QA Cycle 중 Agent가 WS로 push → `QaAgentInboundRouter`가 `type:"KNOWLEDGE"` 인식 → `store(source=QA)` | 완료 (early-return 분기, gameInstance→projectId 해석) |
| **scenario** | 파이프라인 없음 | 미정(후속) |

- 저장은 **배치(리스트)**. 무효 항목(잘못된 tag/빈 summary·description)은 **throw 금지 → 스킵+로그**
  (QA 라우팅은 `appendError` ORCHE_INTERNAL, 런은 실패시키지 않음). 유효 항목만 saveAll.

### 2.4 파일 hash dedup (업로드 레이어 = **Document 도메인 소유**)
- hash 생성·저장·중복검증의 authoritative 책임은 `project`(Document) 도메인.
- register 시 Orche가 **S3 원본 바이트로 SHA-256 계산 → 같은 프로젝트에 동일 파일이면 409**
  (`(project_id, content_hash)` 부분 유니크). **Agent 요약 요청 이전에 선행**되어 불필요한 요청 차단.
- knowledge는 그 확정된 `document.contentHash`를 **참조·복사**만 함(Agent metadata.hash는 불신).
- 사이클 순서: **문서 저장(+hash) → (중복이면 여기서 끝) → Agent 요약 → knowledge 적재**.
- 트레이드오프(S3 진입 전 검증 vs 후 검증)는 Notion 김태민 페이지에 문서화됨.

### 2.5 조회
- `GET /api/knowledge?projectId=&source=&tag=` (내부 permitAll).
  provided-but-invalid 필터는 400, 아니면 파생 쿼리로 조회. **이건 Phase 2 서칭 이전의 임시 조회다.**

---

## 3. tag 설계 확정 (2026-07-28)

### 3.1 원칙
1. **tag = 지식의 내재적 성질(topic)**. "무슨 종류인가". 검색의 1차 하드 필터.
2. **쓰임새(purpose)로 나누지 않는다.** 하나의 지식은 여러 태스크가 공유(다대다)한다 →
   purpose는 문서가 아니라 **질의(query) 쪽**에서 매핑한다. 각 소비자가 "나는 이 tag를 뽑는다"를 정한다.
3. **단일축 enum, 항목당 1개.** 경계가 자명한 **최소 집합(≤5)** 이라야 Agent 분류 오류가 준다.
4. **확장은 값 추가가 아니라 직교 facet 컬럼 추가**로(YAGNI, 지금은 단일축).

### 3.2 값 (V15)
| tag | 의미 |
|---|---|
| `CONTROL` | 입력·조작 방식(이동/버튼/액션). "어떻게 조작하나" |
| `RULE` | 시스템·규칙·수치·제약. "게임이 어떻게 굴러가나" |
| `OBJECTIVE` | 목표·성공/실패 조건·진행. "무엇이 일어나야 하나" — QA 판정·Issue 기대동작의 핵심 |
| `UI` | 화면·HUD·메뉴 요소 |
| `MISC` | 기타 fallback(버리지 않고 담아 검색 대상엔 남김) |

- 기존 `INFO`가 너무 넓어 **`RULE`(규칙/수치) + `OBJECTIVE`(목표/기대결과)** 로 분리한 게 핵심.
  "실제 vs 기대" 판정(QA)과 기대동작 서술(Issue)을 정조준하기 위함.
- **`ENTITY`(캐릭터/아이템/맵 등 명사)는 일부러 제외** — 거의 모든 tag에 걸쳐 나타나 오분류를 유발.
  고유명사는 어차피 lexical/vector가 본문에서 잘 잡는다. tag는 "명사"가 아니라 "성질(aspect)"로.

### 3.3 소비자별 tag 매핑 (purpose = query-side)
| 소비자 | 뽑는 tag |
|---|---|
| 시나리오 작성 | OBJECTIVE, CONTROL, RULE, UI |
| QA 진행 | CONTROL, OBJECTIVE, RULE |
| 보고서 | OBJECTIVE, RULE |
| Issue 작성 | OBJECTIVE, RULE, UI |

---

## 4. Phase 2 과제 — Agent 전용 지식 서칭

### 4.1 목적
Phase 1은 적재만 했다. Phase 2 = **Agent가 QA/시나리오/보고서/Issue 중 필요한 지식을 정확히 꺼내는
retrieval**(RAG의 검색 단계). §2.5의 단순 필터 조회를 하이브리드 검색으로 대체한다.

### 4.2 검색의 두 축 + 융합
- **Lexical(BM25 계열)**: 단어가 그대로 겹치는지. 고유명사·용어·수치에 강함, 동의어/의역에 약함.
- **Vector(임베딩)**: 의미가 가까운지. 의역/동의어에 강함, 정확한 용어에 약함.
- **하이브리드 = 둘을 각각 돌려 RRF(Reciprocal Rank Fusion)로 합침**:
  `score = Σ 1/(k + rank_i)` (k≈60). 점수 스케일 무관, 양쪽에서 두루 상위인 것이 올라옴.
- **tag는 3번째 신호**: 하드 필터(`WHERE tag IN (...)`) 또는 질의 성격별 부스팅.

### 4.3 "Agent가 정확히 원하는 정보를 찾기에 최적인 포맷" (설계 지향)
1. **하드 필터는 작고 안정적인 단일축 tag enum**(§3) — graph 노드가 결정적·저비용으로 후보를 좁힘.
2. **정밀도는 필터 후 의미검색**에 맡김 — 거칠게 자르고 그 안에서 semantic이 찍음(역할 분담).
3. **summary/description 2단 조회**: 노드가 `summary`만 스캔해 랭킹 → 고른 것만 `description` 펼침
   (토큰 효율). 컬럼 분리가 이미 되어 있어 그대로 활용 가능.
4. **진화는 facet 컬럼 추가로**(값 폭증 금지).

### 4.4 RDS 제약 하의 결정
| 항목 | 선택 | 이유 |
|---|---|---|
| Lexical | **Postgres FTS**(`tsvector`/`ts_rank` + GIN) | RDS 네이티브, 즉시 구현 가능("BM25-ish") |
| (배제) 진짜 BM25 | ParadeDB `pg_search` | RDS에 확장 못 올림 |
| Vector | **일단 보류** | pgvector RDS 가용성 + 임베딩 생성 주체 결정 후 |

### 4.5 미결 결정 (착수 전)
1. **임베딩 생성 주체** — Agent가 벡터까지 보냄(계약 추가) vs Orche가 임베딩 API 호출(비용·의존성 Orche).
2. **pgvector RDS 가용성/버전** 확인.
3. **검색 질의 transport** — Agent가 WS로 검색 요청 vs REST 조회. (API 형태 결정)

### 4.6 단계 계획
- **Phase 2-a (즉시 가능)**: `search_tsv tsvector`(GIN) + tag 필터 + 검색 API. **임베딩 결정 불필요.**
  하이브리드 합류 지점(RRF)을 미리 만들어 vector를 나중에 "소스 하나 추가"로 끼움.
- **Phase 2-b**: pgvector 가용성 확인 + 임베딩 주체 결정 → `embedding vector(N)`(HNSW) 추가.
- **Phase 2-c**: RRF로 lexical + vector 융합.

---

## 5. 선행 설계본 대비 바뀐 점 (07-24 / 07-26 → 07-28)
- **metadata**: 설계본은 "JSONB 한 컬럼"을 제안 → 실제론 **컬럼 승격**(`source_id`, `content_hash`).
  filename은 저장 안 함(보류).
- **tag**: 설계본은 조작/정보/MISC(값 미정) → **CONTROL/RULE/OBJECTIVE/UI/MISC 확정**(topic 단일축).
  purpose-in-query 원칙 명문화, ENTITY 제외 근거 추가.
- **hash 소유권**: 업로드 레이어(Document 도메인)가 authoritative — 요약 이전 선행, knowledge는 참조만.

## 6. 참고 소스
- 저장: `knowledge/service/KnowledgeService.kt`, `DocumentKnowledgeExtractionService.kt`,
  `qa/service/QaAgentInboundRouter.kt`(KNOWLEDGE 라우팅).
- hash: `project/storage/S3DocumentStorage.sha256`, `project/service/ProjectDocumentService`.
- 계약: `knowledge/dto/KnowledgeDtos.kt`, `knowledge/agent/ExtractDtos.kt`.
