# 2026-08-05 — QA 런별 지식창고 스코프 격리

- Date: 2026-08-05
- Jira: ARTEL-256
- Status: Done

## Goal

QA 런이 쓰는 지식창고를 **스코프 하나로 격리**해 두 가지를 동시에 막는다.

1. **순서 효과.** 설정 A로 돈 런이 쌓은 지식을 설정 B로 돈 런이 물려받으면, 축별 점수 차이가
   설정 때문인지 실행 순서 때문인지 갈리지 않는다. V25(ARTEL-239)가 비교 축을 `qa_try`에
   남겨도 이 오염이 있는 한 비교 자체가 무의미하다.
2. **운영 오염.** 실험 런이 쓴 지식이 운영 지식창고에 섞이면 되돌릴 방법이 없다.

## Non-goals

- Agent 서버(artel-agent-server) 수정. Orchestration 단독 작업이다.
- 실험 엔티티(`qa_experiment` / `qa_experiment_arm`), 실행기, game_instance 풀.
- `knowledge_event` / `knowledge_usage` 로깅(ARTEL-255, 병행 진행 중).
- 점수·채점(`qa_try_score`), 대시보드 UI, 임베딩 파이프라인 개편.
- 시점 고정(freeze) — 아래 "Risks" 참조.

## Context / Constraints

**copy-on-write, 스냅샷 복사 아님.** 런 시작 시 baseline을 통째로 복사하면 `knowledge_embedding`까지
복사해야 하는데 벡터 생성은 비동기 백필이라 즉시 못 쓰고, arm 수만큼 지식창고가 불어난다.
읽기에서 baseline을 그대로 보고 쓰기만 스코프로 가르면 같은 격리를 복사 없이 얻는다.

- 읽기: `scope_id IS NULL OR scope_id = :scope` — 스코프 런은 baseline + 자기 것을 본다.
- 쓰기: 항상 `:scope` — 운영 런은 `:scope`가 NULL이라 지금 동작과 같다.

**scope의 출처는 `qa_try.knowledge_scope_id`다.** 세션 개설 시점에 정해지고 런 도중 안 바뀐다.
실험 엔티티가 아직 없으므로 이 컬럼은 QA 런 생성 API로 채운다.

**baseline 수정·삭제는 그림자 행으로.** 스코프 런이 baseline(`scope_id IS NULL`)을 지우거나 고칠 때
그 행을 직접 건드리면 운영 지식창고가 실험 때문에 깎여나간다. `shadows_id`로 baseline 하나를
가리는 스코프 행을 만든다.

- 내용이 있는 그림자 = 그 스코프 안에서의 수정
- `deleted_at`이 찍힌 그림자 = 그 스코프 안에서의 삭제(툼스톤)

읽기 질의는 **그림자가 가리는 baseline 행을 결과에서 빼야 한다.** 수정이든 삭제든 마찬가지다.
빠뜨리면 스코프 런이 자기가 지운 항목을 계속 돌려받고, 수정한 항목은 원본과 수정본이 둘 다 나온다.

**기존 데이터.** 기존 행은 전부 `scope_id` NULL이고 그게 맞다. 운영 런의 동작은 이 작업 전후로
완전히 동일해야 한다.

**Flyway 번호.** develop과 열린 모든 원격 브랜치를 확인한 결과 최고 번호는 V25다. V26을 쓴다.

## Approach (Checklist)

- [x] **Step 0: Recon** — 완료. knowledge를 읽는 경로 전수 조사 결과:
  - `KnowledgeRepository`의 파생 쿼리 4개 (`findByProjectId...`) — HTTP 목록 API
  - `KnowledgeRepository.findByIdAndProjectIdAndDeletedAtIsNull` — 수정·삭제 대상 조회
  - `KnowledgeVectorSearchRepository.searchNearest` — 벡터 검색(WS `KNOWLEDGE_SEARCH`)
  - `KnowledgeEmbeddingSource.loadLiveKnowledge` → `findAllById` — 백필. **스코프 무관이 맞다**
    (그림자 행도 자기 벡터가 필요하다). 프로젝트 목록 조회가 아니라 id 조회다.
  - `EmbeddingQueueRepository.seedPending` — `knowledge`를 `deleted_at IS NULL`로 조인. 툼스톤은
    이미 제외된다.

- [x] **Step 1: 마이그레이션 V26**
  - `knowledge.scope_id BIGINT`, `knowledge.shadows_id BIGINT` (논리참조, FK 안 검 — V13/V19 관례)
  - `qa_try.knowledge_scope_id BIGINT`
  - 인덱스 교체: `idx_knowledge_project_alive` → `(project_id, scope_id) WHERE deleted_at IS NULL`,
    `idx_knowledge_project_tag`/`_source`에 `scope_id` 삽입
  - `uq_knowledge_scope_shadow ON knowledge (scope_id, shadows_id) WHERE shadows_id IS NOT NULL` —
    그림자 조회 인덱스이자 "스코프당 baseline 하나에 그림자 하나" 불변식

- [x] **Step 2: 스코프 술어를 한 곳으로**
  - `KnowledgeScope` 값 타입 (`Long?` 래핑, `PRODUCTION` / `of(id)`). 기본값 없는 필수 파라미터라
    빠뜨리면 컴파일이 안 된다.
  - `KnowledgeScopeSql.VISIBLE` — 가시성 술어 SQL 조각 하나. 목록 쿼리와 벡터 검색이 같은 것을 쓴다.
  - `KnowledgeRepository`의 파생 쿼리 4개를 스코프 인지 `@Query` 하나로 대체(파생 쿼리로는
    `NOT EXISTS`를 표현할 수 없다). 스코프 없는 프로젝트 목록 조회 메서드를 인터페이스에서 없앤다.

- [x] **Step 3: 쓰기 경로**
  - `KnowledgeService.store` / `createFromQaTry` — `scope`를 받아 그대로 기록
  - `updateFromQaTry` / `softDeleteFromQaTry` — 대상이 baseline이고 호출자가 스코프 런이면
    그림자 생성/툼스톤, 그 외에는 지금처럼 직접 수정·소프트삭제
  - 그림자가 이미 있으면 새로 만들지 않고 그 그림자를 고친다(중복 그림자 = 읽기 중복)

- [x] **Step 4: `knowledge_mode` 게이트**
  - `qa_try.run_config.knowledge_mode`: `learning`(기본) / `frozen`(읽기만) / `off`(검색 빈 결과)
  - `QaAgentInboundRouter`가 게이트한다. **Agent는 건드리지 않는다** — arm마다 Agent 프롬프트가
    바이트 단위로 같아야 변수가 "지식 가용성" 하나로 좁혀진다.
  - 거부 경로도 throw하지 않는다. 프레임 하나가 WS 수신 체인을 끊으면 런 전체가 실패한다.

- [x] **Step 5: 런 생성 API**
  - `CreateQaTryRequest`에 `knowledgeScopeId`, `knowledgeMode` 추가 → `qa_try`에 기록.
    실험 엔티티가 생기면 그쪽이 이 값을 채우는 주체가 될 뿐, 이 작업은 그것을 기다리지 않는다.

- [x] **Step 6: 테스트**

## Validation

- **Commands to run:** `./mvnw test` (전체). 마이그레이션은 별도로 임시 Postgres에 V1~V25 를
  적용하고 기존 knowledge 행·벡터를 넣은 뒤 V26 을 올려 확인했다.
- **Result:** `Tests run: 302, Failures: 0, Errors: 0, Skipped: 0`. 기존 데이터가 있는 DB 에
  V26 을 올렸을 때 기존 행 2/2 가 `scope_id IS NULL AND shadows_id IS NULL`(baseline)로 남고,
  인덱스가 scope 포함본으로 교체되며, 같은 baseline 에 그림자를 둘 만들면
  `uq_knowledge_scope_shadow` 가 거절했다.
- **검증한 성질:**
  - 운영 런(scope NULL)의 읽기·쓰기·삭제 결과가 이 변경 전과 같다 (회귀)
  - 스코프 런이 baseline을 읽는다
  - 스코프 런이 쓴 것이 운영 런에 안 보인다
  - 스코프 A가 쓴 것이 스코프 B에 안 보인다
  - 스코프 런이 baseline을 지우면 그 런에서는 사라지고 운영에는 남는다
  - 스코프 런이 baseline을 고치면 그 런에서 수정본 하나만 나온다 (원본과 중복 없음)
  - `frozen`에서 쓰기가 거부되고 런이 안 죽는다, `off`에서 검색이 빈 결과다

## Risks & Rollback

- **읽기 경로 누락.** 하나라도 빠지면 격리가 뚫리고, **뚫린 격리는 조용하다** — 결과가 그럴듯해서
  아무도 못 알아챈다. 그래서 파생 쿼리를 없애고 술어를 SQL 조각 하나로 모은다. 스코프를 안 주면
  컴파일이 안 된다.
- **재현성 한계(고쳐지지 않음).** copy-on-write라 실험 중 운영 런이 baseline을 바꾸면 그 변경이
  실험 런에 그대로 보인다. 완전한 재현성은 시점 고정이 필요하고 그건 이 범위 밖이다. 나중에
  실험 결과가 안 맞을 때 여기가 원인 후보다 — 코드 주석과 PR 본문에 남긴다.
- **임베딩 지연(기존과 동일).** 벡터 생성이 비동기 백필이라 방금 쓴 지식은 같은 런에서 검색에
  안 잡히는 구간이 있다. 스코프 런에만 생기는 문제가 아니라 지금도 그렇다. 이번 범위에서
  고치지 않고 사실만 남긴다.
- **그림자 임베딩 비용.** 스코프 런이 baseline을 고치면 그림자 행이 자기 벡터를 새로 청구한다.
  arm 수 × 수정 건수만큼 임베딩이 늘어난다.
- **Rollback:** `git revert`. V26은 `ADD COLUMN IF NOT EXISTS`뿐이라 컬럼이 남아도 무해하다
  (전부 nullable, 읽는 코드가 없으면 무시된다). 인덱스만 이전 정의로 되돌리면 된다.

## Open Questions

- 없음. 스코프 4번(`knowledge_mode`)은 선택 범위였으나 포함하기로 확인받았다.
