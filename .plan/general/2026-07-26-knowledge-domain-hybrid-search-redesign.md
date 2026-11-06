# 2026-07-26 — Knowledge 도메인 재설계 (reference_context 대체 + 하이브리드 검색)

- Date: 2026-07-26
- Jira: (TBD)
- Status: **설계 확정 진행 중** (구현 착수 전). tag enum은 미확정(구현 완료 전 반드시 확정).
- 대체 대상: 기존 `reference_context` (V10, develop 머지·배포됨) → **삭제하고 새 구조로 갈아엎음**

## 결정 요약 (팀 회의 확정)
- **테이블 통합**: 문서/QA/시나리오를 별도 테이블로 안 나눔. 입력부가 어차피 다 Agent라 분리 안 함.
- **신뢰도(문서 사실 vs Agent 추론)는 코드 레이어에서 제어** — 스키마 분리 대신 `source`/`tag`로 필터·가중.
- **검색 재설계**: 기존 타입별 조회로는 "필요한 정보만 정확히" 못 잡음 → **벡터 + BM25 하이브리드** 검색으로.
  - **BM25**: 직접 구현 X, 외부 알고리즘 가져올 예정. **지금은 PLAN만**, 나중 구현.
  - **pgvector(벡터)**: 마찬가지로 **PLAN만**, 나중 구현.
- **저장 파이프라인은 기존 방식 유지**, Agent가 **배치(리스트)** 로 전달.
- **DB 제약은 약하게** (강한 유니크/제재 안 함). 파일 중복은 업로드 레이어에서 hash로(아래).

## Agent → Orchestration 전달 구조 (계약)
```json
{
  "source": "docs" | "qa" | "scenario" | ...,   // 현재 후보: docs, qa (scenario는 파이프라인 아직 없음)
  "metadata": {            // source별 가변. 있을 때도 없을 때도 있음(nullable)
    // docs: { "filename"?: string, "hash"?: string, "id"?: number }
    // qa  : { "qaTryId": ... }   // 사실상 envelope.qaTryId로 이미 옴
  },
  "game_context": [        // ★ 리스트 (Agent가 한 번에 여러 개 전달)
    {
      "tag": "조작" | "정보" | "misc" | ...,   // ★ enum, 우리가 사전 정의 (값 TBD, 아래)
      "summary": "string",       // Agent 생성
      "description": "string"    // Agent 생성
    }
  ]
}
```
- `summary`/`description` 둘 다 Agent 생성, DB엔 **String(TEXT)** 로 저장.
- `tag`가 매우 중요 — "이 정보가 어떤 성질인가"의 1차 이정표. **enum으로 사전 정의 필수**(값은 미정, 별도 심층 논의 예정, **구현 완료 전 반드시 확정**).
- `metadata`는 source에 따라 형태가 달라짐 → **JSONB 한 컬럼**으로 흡수(폴리모픽/테이블분리 회피).

## Source별 전송 경로 (transport)
| source | 경로 | 상태 |
|---|---|---|
| **docs** | FE 업로드 → (기존 pull 파이프라인) Orche가 Agent `/extract` 호출 → Agent가 위 구조로 응답 → 저장 | 파이프라인 **이미 있음**(리팩터 필요: 응답 구조가 새 형태) |
| **qa** | QA Cycle 중 Agent가 필요 판단 시 **WS로 push** → `QaAgentInboundRouter`가 `type:"KNOWLEDGE"`로 인식 → KnowledgeService 저장 | **신규 배선 필요** |
| **scenario** | 파이프라인 없음 | **미정(후속)** |

### QA WS 인바운드 연동 (핵심 파악 완료)
- 봉투: `QaAgentEnvelope { qaTryId, messageId, type, payload: JsonNode, correlationId }`
- 라우터: `qa/service/QaAgentInboundRouter.kt` — `SUPPORTED_TYPES`에 타입 추가하고 `when(type)` 분기.
  - 현재 타입: `LOG, ACTION, STATUS, ERROR, CHAT, REQUEST_GAME_STATE, ISSUE`.
  - **추가할 것**: `"KNOWLEDGE"` → `routeKnowledge()` → `KnowledgeService.store(...)`. (ISSUE 라우팅이 그대로 템플릿)
  - `payload`에 `{source, metadata, game_context[]}`가 실려 옴. qa source면 metadata.qaTryId = envelope.qaTryId.
  - 검증 실패(잘못된 tag/빈 summary 등)는 throw 금지 → `appendError`(ORCHE_INTERNAL ERROR 로그), 행 미생성, 런은 실패 처리 안 함(ISSUE와 동일 원칙).

## 스키마 (확정 2026-07-26) — Phase 1은 저장만, 검색 컬럼은 Phase 2
브랜치 `refactor/migration-referencecontext-form-ARTEL-139` (V10/V11 존재, develop 동일선상).
마이그레이션은 **V13** — V12는 pending issue PR #46 선점이라 충돌 회피.
```sql
-- V13__replace_reference_context_with_knowledge.sql (Phase 1)
DROP TABLE IF EXISTS reference_context;
CREATE TABLE knowledge (
  id           BIGSERIAL PRIMARY KEY,
  project_id   BIGINT NOT NULL,
  source       VARCHAR(20) NOT NULL CHECK (source IN ('DOCS','QA')),
  source_id    BIGINT,          -- nullable: DOCS→project_document.id, QA→qa_try.id
  content_hash VARCHAR(64),     -- nullable: DOCS 파일 hash (멱등키 아님, 저장만)
  tag          VARCHAR(20) NOT NULL CHECK (tag IN ('CONTROL','INFO','MISC')),
  summary      TEXT NOT NULL,
  description  TEXT NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
  -- 강한 유니크 없음(팀 결정: DB 제재 약하게). hash는 멱등키 아님.
);
CREATE INDEX idx_knowledge_project_tag ON knowledge (project_id, tag);
CREATE INDEX idx_knowledge_project_source ON knowledge (project_id, source);
```
- **metadata를 JSONB 대신 컬럼으로 승격**: `source_id`(문서 id 또는 qa_try id), `content_hash`(문서). **filename은 저장 안 함(보류).**
- **source enum: 현재 DOCS, QA 둘뿐.** **tag enum(Phase 1 최소): CONTROL(조작), INFO(정보), MISC** — 영문 토큰 저장, Agent 계약도 이 토큰. (확장은 후속)

## Phase 2 (PLAN ONLY, 나중): 하이브리드 검색
- `content_tsv tsvector` → BM25(외부 알고리즘 도입, ≈FTS) / GIN 인덱스
- `embedding vector(N)` → pgvector / HNSW (임베딩 생성 주체 미정)
- 하이브리드 = FTS 점수 + 벡터 점수 RRF(reciprocal rank fusion)

## 중복(dedup) 방침
- **파일 단위**: 업로드 시 파일 전체 hash를 DB에 저장 → 이후 같은 hash 업로드면 **요청 자체를 Deny**(업로드 레이어). ← 팀 아이디어, 향후.
- **Agent 산출물**: 내용이 매번 달라져 dedup 어려움 → 그때 가서 판단.
- **DB 자체는 강한 제약 안 검** (knowledge 테이블에 강한 유니크 없음).

## 마이그레이션 전략 (배포 DB 갈아엎기)
- ⚠️ **V10(reference_context)을 편집하면 안 됨** — 이미 적용된 마이그레이션 수정 시 Flyway 체크섬 불일치로 부팅 실패(validate). (메모리 [[flyway-and-develop-merge-hazards]] 참고)
- **새 마이그레이션 추가**로 갈아엎음:
  ```sql
  -- V13 (또는 그 시점 최신+1). V12는 pending issue PR #46이 선점 → 충돌 피하려 V13.
  DROP TABLE IF EXISTS reference_context;
  CREATE TABLE knowledge (...);
  ```
- 배포 DB는 다음 기동 시 Flyway가 **V13 미적용 감지 → 자동 실행**(old drop + new create). "V10을 다시 마이그레이션"이 아니라 **새 버전 추가**가 자동 적용의 정답.
- reference_context 데이터는 drop으로 사라짐(신규/소량이라 허용 — 배포 데이터 확인 필요).
- **코드도 갈아엎어야 함**(마이그레이션만으론 안 됨):
  - 삭제/개편 대상: `referencecontext/` 도메인(entity/repo/service/dto/controller), pull 파이프라인 `AgentExtractClient`·`ReferenceContextExtractionService`·`ProjectDocumentService`의 추출 배선(응답 구조가 새 형태로 바뀜).
  - 신규: `knowledge/` 도메인 + KnowledgeService(store 배치) + QaAgentInboundRouter의 KNOWLEDGE 라우팅 + docs pull 파이프라인 리팩터.

## 미해결/보류 (구현 전 확정 필요)
1. **tag enum 값** — 별도 심층 논의(item 7). 구현 완료 전 반드시.
2. **임베딩 생성 주체** — Agent가 벡터까지 주나 vs Orche가 임베딩 API 호출. (Phase 2, 벡터 구현 시)
3. **BM25 외부 알고리즘** 무엇으로/어디서 (Phase 2).
4. **RDS 제약 확인**: 진짜 BM25(pg_search=ParadeDB)는 RDS 불가 → FTS로 갈지/별도 검색엔진 갈지. pgvector 활성화 가능 버전인지.
5. **scenario source 파이프라인** (후속).

## 단계 계획
- **Phase 1 (tag 확정 후 착수)**: knowledge 테이블(저장 컬럼) + V13 drop/create + docs pull 리팩터 + QA WS KNOWLEDGE 라우팅 + 기본 조회. reference_context 도메인 제거.
- **Phase 2 (PLAN)**: 하이브리드 검색(tsvector/BM25-ext + pgvector + RRF).

## 참고 소스 (구현 시)
- QA 인바운드 패턴: `qa/service/QaAgentInboundRouter.kt`(ISSUE 라우팅), `qa/dto/QaDtos.kt`(QaAgentEnvelope), `issue/service/IssueService.kt`(recordAgentIssue 멱등 저장).
- 기존 docs 파이프라인: `referencecontext/service/ReferenceContextExtractionService.kt`, `referencecontext/agent/AgentExtractClient.kt`, `project/service/ProjectDocumentService.kt`.
