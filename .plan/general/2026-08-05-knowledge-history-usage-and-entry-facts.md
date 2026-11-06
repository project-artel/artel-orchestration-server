# 2026-08-05 — knowledge 이력과 검색 사용 로그를 남기고 항목별 집계 view를 만든다

- Date: 2026-08-05
- Jira: ARTEL-255 (Epic ARTEL-12 [Backend] Orchestration 서버 개발)
- Branch: `feat/knowledge-이력과-검색-사용-로그를-남기고-항목별-집계-view를-만든다-ARTEL-255`
- Worktree: `.worktrees/ARTEL-255`
- Status: Done

## Goal

QA 에이전트를 **모델 / 프롬프트 / 구조 / reasoning** 축(V25)으로 비교할 때 붙일 **결과** 근거를
만든다. 축은 이미 `qa_try`에 있고, 빠진 것은 "이 런이 만든 지식이 쓸모 있었나"다.

착상은 **후속 런이 공짜 심판**이라는 것이다. 어떤 런이 만든 지식을 나중 런이 지우면 그것이 부정
신호다. 심판 LLM도 정답지도 필요 없고 운영 트래픽만으로 지표가 나온다. 대신 **로그는 소급이
안 되므로 지금 켜는 것이 이 작업의 전부**다.

셋만 한다.

1. `knowledge` 이력(`knowledge_event`) — 누가 어느 버전을 만들고 지웠나.
2. 검색 사용 로그(`knowledge_usage`) — 검색이 무엇을 내보냈나.
3. 항목별 사실 view(`knowledge_entry_facts`) + 축별 집계(`KnowledgeStatsRepository`).

## Non-goals

- Agent 서버(`artel-agent-server`) 수정. 이번 범위는 Orchestration 단독이다.
- `replaces_id`를 채우는 로직, `KnowledgeCreatePayload` 변경, `update_knowledge` 툴 복원.
- `cited`를 채우는 인용 기능, 프롬프트 변경.
- admin-page 대시보드 UI, 그리고 **조회 HTTP API**(아래 "Rejected/Deferred" 참조).
- `knowledge_judgment`, `qa_try` 점수, 실험 엔티티.
- 기존 행에 대한 이벤트 백필.
- 기존 검색·임베딩 **동작** 변경. 이 작업은 순수 추가여야 하고, 기존 읽기 경로가 돌려주는
  결과(WS `KNOWLEDGE_SEARCH_RESULT` payload 포함)가 달라지면 안 된다.

## Context / Constraints

기준 `origin/develop` = `65897ab`. develop 최대 마이그레이션은 **V25**이므로 신규는 **V26**.
미머지 브랜치(ARTEL-243/245/254)도 전부 V25가 최대라 이번 번호는 비어 있다.
(V25 주석의 V24 충돌 사례처럼, 머지 시점에 번호가 겹치면 내용은 그대로 두고 번호만 민다.)

### 착수 전 확인한 사실 — 지시서의 전제와 다른 셋

1. **`QaStatsRepository`는 `develop`에 없다.** ARTEL-243 브랜치
   (`feat/QA-런을-실행-설정-축으로-집계하는-통계-API를-추가한다-ARTEL-243`, `563fdc9`)에만 있다.
   이번 작업은 그 브랜치에 의존하지 않는다 — 형태만 따르고 코드는 공유하지 않는다.

2. **복원(RESTORE) 경로가 코드에 없다.** `KnowledgeService`는 생성/수정/소프트삭제뿐이고
   `KnowledgeController`는 조회 전용이다. 마이그레이션의 `CHECK`에는 `RESTORE`를 넣되
   **쓰는 곳은 없다.** 복원 기능 추가는 범위 밖이므로 값만 열어 둔다.

3. **사람/문서의 *수정* 경로가 없다.** 비-QA 쓰기는 `KnowledgeService.store()`(문서 추출 배치)
   하나뿐이다. 따라서 "사람/문서 경로"가 남기는 이벤트는 **`CREATE`뿐**이고, 계측할 사람 쪽
   UPDATE/DELETE는 존재하지 않는다.

### 함정 확인 결과

- **`knowledge_embedding` 낡음(stale) 여부**: 지금은 문제가 없다. content를 바꾸는 유일한 경로인
  `KnowledgeService.updateFromQaTry`가 **같은 트랜잭션에서** `embeddingRepository.discardFor`를
  부른다. 사람/문서 수정 경로 자체가 없으므로 벡터가 낡은 텍스트를 가리키는 상태가 만들어지지
  않는다. 별건으로 발견한 사실 하나: 문서 재추출은 기존 행을 고치지 않고 **새 행을 넣는다**
  (`store()`는 INSERT 전용). 벡터-본문 불일치는 아니고 중복 항목이 쌓이는 문제라 이번 범위 밖.
  → 후속에서 사람/문서 수정 경로를 만든다면 그 경로도 `discardFor`를 같은 트랜잭션에 걸어야 한다.
- **이벤트 이력이 없는 기존 행**: 백필하지 않는다. 대신 view가 그 행들을 **버전 1 한 줄로
  합성**해 포함시키되 `created_by_qa_try_id`를 NULL로 둔다(아래 Step 1e). 이력을 지어내지 않으면서
  그 행에 대한 검색 사용량이 집계에서 사라지지 않게 하는 유일한 방법이다. 축별 롤업은
  `qa_try` INNER JOIN이라 이 합성 행이 자동으로 빠진다 — 축을 모르는 행이 축 통계를 오염시키지 않는다.
- **`QaAgentInboundRouter` 규칙**: 프레임 처리 중 예외가 WS 수신 체인 밖으로 나가면 소켓이 닫히고
  런 전체가 실패한다. 새 코드도 `CancellationException` 먼저 rethrow → 넓은 catch → ERROR 로그.

## Approach (Checklist)

- [x] **Step 0: Recon** — 완료. 대상 파일:
      `db/migration/V26__*.sql`(신규),
      `knowledge/entity/KnowledgeEntity.kt`,
      `knowledge/entity/KnowledgeEventEntity.kt`(신규), `knowledge/entity/KnowledgeUsageEntity.kt`(신규),
      `knowledge/repository/KnowledgeEventRepository.kt`(신규), `knowledge/repository/KnowledgeUsageRepository.kt`(신규),
      `knowledge/repository/KnowledgeStatsRepository.kt`(신규),
      `knowledge/repository/KnowledgeVectorSearchRepository.kt`,
      `knowledge/service/KnowledgeService.kt`, `knowledge/service/KnowledgeSearchService.kt`,
      `qa/service/QaAgentInboundRouter.kt`.

### Step 1a (완료): `V26__create_knowledge_history_and_usage.sql`

세 덩어리를 한 파일에 둔다 — view가 두 테이블 모두에 기대므로 쪼개면 순서 의존만 생긴다.

```sql
ALTER TABLE knowledge
    ADD COLUMN IF NOT EXISTS version     INT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS replaces_id BIGINT;

CREATE TABLE IF NOT EXISTS knowledge_event (...);
CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_event_version
    ON knowledge_event (knowledge_id, version) WHERE after IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_knowledge_event_try ON knowledge_event (qa_try_id);

CREATE TABLE IF NOT EXISTS knowledge_usage (...);
CREATE INDEX IF NOT EXISTS idx_knowledge_usage_knowledge ON knowledge_usage (knowledge_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_usage_try ON knowledge_usage (qa_try_id);

CREATE OR REPLACE VIEW knowledge_entry_facts AS ...;
```

주석에 녹일 결정(밀도는 V13/V18/V19/V25와 같게):

- **FK를 걸지 않는다** — V13/V19의 논리참조 관례. QA 런 정리가 지식창고에 막히면 안 된다.
- **`version`은 content가 바뀔 때만 오른다**(CREATE/UPDATE). DELETE/RESTORE는 안 올린다.
  소프트삭제는 본문을 안 건드리고, 복원이 `deleted_at = NULL`만으로 되어야 한다는 V18 결정을 지킨다.
- **`after`만 저장하고 `before`는 저장하지 않는다.** 최신 content가 `knowledge` 행과 한 번
  중복되지만, 그 대가로 모든 버전이 `(knowledge_id, version)` 단건 조회로 통일된다. before
  방식은 "최신은 행에서, 나머지는 다음 이벤트에서"로 조회가 갈리고, 최신을 읽는 사이 mutation이
  끼어들면 엉뚱한 버전을 조용히 읽는 경합이 생긴다. 이벤트 행은 불변이라 그 경합이 없다.
  덤으로 `knowledge.version == max(event.version)` 불변식이 생겨 쓰기 버그가 검출된다.
- **부분 유니크 인덱스인 이유**: DELETE/RESTORE는 version을 안 올리므로 `(knowledge_id, version)`이
  전체에서는 유일하지 않다. content 이벤트만 유일해야 한다. 목적은 성능이 아니라 **재시도나
  동시 쓰기로 같은 버전이 두 번 기록되는 것을 막는 것**이다.
- **`replaces_id`는 이번에 쓰지 않는다.** 컬럼만 만든다 — V18이 ARTEL-188의 `deleted_at`을 미리
  만들어 둔 것과 같은 판단.
- **`cited`는 nullable, 기본값 없음.** NULL = 인용을 보고할 수단이 없었다 / false = 보고 가능했는데
  안 했다 / true = 했다. `NOT NULL DEFAULT false`면 인용 기능 이전 런 전부가 "아무도 안 쓴 지식"으로
  보이고 그 오류는 조용히 지나간다(V25의 축 컬럼이 nullable인 것과 같은 이유).
- **`step`도 이번엔 항상 NULL** — `KnowledgeSearchPayload`가 step을 안 싣는다.

### Step 1b: 엔티티/리포지토리

- `KnowledgeEntity`에 `version: Int = 1` 추가. `replacesId`는 **매핑하지 않는다** — 쓰지 않는
  컬럼을 엔티티에 실으면 `save()`가 매번 NULL로 덮어써도 무해하지만, "쓰지 않는다"는 결정이
  코드에서 안 보이게 된다. R2DBC는 매핑 안 된 컬럼을 건드리지 않는다.
- `KnowledgeEventEntity` / `KnowledgeEventRepository`(`CoroutineCrudRepository`).
  `after`는 `io.r2dbc.postgresql.codec.Json`(`qa_log.payload` 선례).
- `KnowledgeUsageEntity` / `KnowledgeUsageRepository`.
- `createdAt` / `retrievedAt`은 **`Clock`으로 직접 stamp**한다. 컬럼 기본값에 맡기면 R2DBC가
  insert 후 값을 다시 읽지 않아 저장 결과가 null이다(`QaLogService`·`LlmUsageService`와 동일).

### Step 1c: `KnowledgeService` — 쓰기 지점 전부에 이벤트

| 경로 | event | version | qa_try_id |
|---|---|---|---|
| `store()` (문서 추출 배치) | CREATE | 1 | NULL |
| `store()` (QA `KNOWLEDGE` 배치) | CREATE | 1 | 그 런 |
| `createFromQaTry` | CREATE | 1 | 그 런 |
| `updateFromQaTry` (content 변경 시) | UPDATE | +1 | 그 런 |
| `softDeleteFromQaTry` | DELETE | 현재값 유지 | 그 런 |

- **행 갱신과 이벤트 삽입은 반드시 같은 트랜잭션.** `store()`와 `createFromQaTry`는 지금
  트랜잭션이 없으므로 `transactionalOperator.executeAndAwait`로 감싼다.
- **여기서 "content"는 `(tag, summary, description)` 셋 전부다.** `after`가 셋을 스냅샷하므로,
  tag만 바뀐 변경에 version을 안 올리면 버전 N의 `after`가 `knowledge` 행과 어긋나 위 불변식이
  깨진다. **임베딩 무효화 판정은 지금대로 `summary`/`description`만 본다** — 임베딩 입력이 그
  둘뿐이라 tag 변경으로 벡터를 다시 청구할 이유가 없다. 두 판정이 다른 것이 의도다.
- 값이 실제로 하나도 안 바뀐 UPDATE는 version을 올리지 않고 이벤트도 안 남긴다(같은 `after`를
  가진 이벤트가 쌓이는 것을 막는다). 행 저장 자체와 `updated_by_qa_try_id` 기록은 지금 동작 그대로.

### Step 1d: 검색 사용 로깅

- `KnowledgeVectorSearchRepository.searchNearest`의 SELECT/GROUP BY에 `k.version` 추가 →
  `KnowledgeSearchRow.version`. 순위·필터는 건드리지 않는다.
- `KnowledgeSearchService.search`가 **`KnowledgeSearchOutcome(response, retrievals)`**를 돌려준다.
  `response`는 지금과 **바이트 단위로 같은** `KnowledgeSearchResponse`다 — `KnowledgeSearchHit`에
  `version`을 얹으면 Agent로 나가는 WS payload가 달라지므로 얹지 않는다. `retrievals`는
  `(knowledgeId, version, rank, score)`로 라우터만 본다.
- `QaAgentInboundRouter.routeKnowledgeSearch`가 **응답 전송 전에** usage를 기록한다.
  전송 후로 미루면 "Agent에는 갔는데 기록은 안 된" 창이 생기고, 이 로그가 곧 지표의 분모다.
  추가 비용은 INSERT 한 문장이고 이미 임베딩 왕복을 한 뒤라 무시할 수준.
- **기록 실패는 삼킨다.** `CancellationException`은 먼저 rethrow, 나머지는 `ORCHE_INTERNAL/ERROR`
  qa_log만 남기고 진행한다. Agent에 ERROR 프레임을 보내지 **않는다** — 도구가 기다리는 것은
  검색 결과이고, 그것은 이미 만들어졌다. 실패로 답하면 멀쩡한 검색이 실패로 뒤집힌다.
- 결과가 비면 행을 남기지 않는다.

### Step 1e: `knowledge_entry_facts` view

`(knowledge_id, version)`당 한 줄. 컬럼 **순서 고정**(`CREATE OR REPLACE`는 타입·순서 변경을
거부한다 — 나중 추가는 끝에만 붙인다):

```
project_id, knowledge_id, version, is_current,
created_by_qa_try_id, created_at,
deleted_at, deleted_by_qa_try_id,
retrieval_count, citation_count, citation_known_count
```

- 버전 목록의 원천은 `knowledge_event WHERE after IS NOT NULL`.
- 이벤트가 없는 기존 행은 **버전 1을 합성**한다(`created_by_qa_try_id` NULL, `created_at`은
  `knowledge.created_at` — 실제 값이라 지어낸 것이 아니다).
- `deleted_at` / `deleted_by_qa_try_id`는 **현재 버전 줄에만, 그리고 실제로 삭제 상태일 때만**
  채운다. 모든 버전에 달면 삭제 하나가 버전 수만큼 세어지고, 복원된 항목은 V19가 감사용으로
  남겨 둔 `deleted_by_qa_try_id` 때문에 "지워진 것"으로 읽힌다.
- **롤업은 view에 넣지 않는다.** 기간 필터가 파라미터인데 view는 파라미터를 못 받는다.
  view는 파라미터 없는 "항목별 사실"까지만 맡는다.
- **"수리(repaired) vs 폐기(repudiated)" 구분 컬럼은 만들지 않는다.** `replaces_id`가 채워져야
  계산되고, 지금 넣으면 전부 false가 나와 "수리가 한 번도 없었다"로 읽힌다.

### Step 1f: `KnowledgeStatsRepository`

view를 `qa_try`에 `created_by_qa_try_id`로 조인해 축으로 접는다. `QaStatsRepository`와 같은 형태:
`DatabaseClient` 생 SQL, `Readable` 매핑, `GROUPING SETS`로 총계 동시 수집, 셀 `LIMIT` + `truncated`.

**축 이름을 파라미터로 받지 않는다.** `QaStatsRepository`가 그렇게 한 이유를 그대로 따른다 —
`(model, reasoning_effort, prompt_version, agent_arch)` 4-튜플로 한 번 접어 두면 단일 축 분해도
두 축 매트릭스도 클라이언트에서 부분합으로 나오고, 축을 파라미터로 받으면 컬럼 이름을 SQL에
끼워 넣는 자리가 생겨 화이트리스트를 한 번 틀리는 순간 주입 지점이 된다.
축이 늘어도 **view가 아니라 이 쿼리만** 바뀐다 — 지시서가 경계한 "축을 view에 접어 넣는" 함정은
여기서 피한다.

셀당 지표:

| 컬럼 | 뜻 |
|---|---|
| `entry_versions` | 이 축 조합의 런들이 만든 content 버전 수 |
| `current_versions` | 그중 아직 최신인 것 |
| `deleted_versions` | 그중 현재 삭제 상태인 것 |
| `repudiated_versions` | 삭제하되 **만든 런과 다른 런이** 지운 것 — 이것이 "공짜 심판" 신호 |
| `retrieval_total` | 검색으로 나간 횟수 |
| `citation_known_total` / `citation_total` | 인용 판정이 가능했던 횟수 / 인용된 횟수 |

`citation_*`은 이번 범위에서 항상 0이다(`cited`가 항상 NULL). 0으로 보이는 것이 "인용이 없었다"가
아니라 "아직 못 잰다"임은 `citation_known_total = 0`으로 구분된다 — 그래서 두 컬럼을 함께 낸다.

### Step 2: Tests

`.agents/docs/testing.md`. 스키마·트랜잭션·WS 라우팅이 걸리므로 **통합 테스트**(Testcontainers).

- `KnowledgeEventIntegrationTest`(신규)
  - 생성/수정/삭제 각각이 올바른 `event`와 `version`을 남긴다.
  - tag만 바꿔도 version이 오르고, 아무것도 안 바뀌면 안 오른다.
  - 삭제는 version을 올리지 않는다.
  - 같은 `(knowledge_id, version)` content 이벤트 중복 삽입이 유니크 인덱스에 걸린다.
  - **이벤트 삽입이 실패하면 행 갱신도 롤백된다**(같은 트랜잭션의 관찰 가능한 정의).
  - 문서 추출 배치는 `qa_try_id`가 NULL인 CREATE를 남긴다.
- `KnowledgeSearchRouterIntegrationTest`(기존에 추가)
  - 검색 한 번이 히트 수만큼 usage 행을 rank/score와 함께 남긴다.
  - 빈 결과는 행을 남기지 않는다.
  - **usage 기록이 실패해도 런은 RUNNING이고 `KNOWLEDGE_SEARCH_RESULT`는 정상 전달된다.**
  - 기존 단언(payload 모양, qa_log 비어 있음)이 그대로 통과 = 읽기 경로 무변경의 증거.
- `KnowledgeStatsIntegrationTest`(신규)
  - 두 런이 각각 만든 지식을 서로 지웠을 때 `repudiated_versions`가 축별로 갈린다.
  - 이벤트 없는 기존 행이 view에 버전 1로 나오고, 축 롤업에서는 빠진다.
- 마이그레이션: 기존 데이터가 있는 상태에서 올라가는지. 위 통합 테스트가 매번 전체 체인을
  돌리므로 자동으로 덮이고, 별도로 "V25까지 적용 → 행 삽입 → V26 적용" 시나리오를 확인한다.

### Step 3: Rollout / Rollback

- 배포 순서 의존 없음 — Agent 계약을 건드리지 않는다.
- 롤백: 코드만 revert 하면 스키마는 무해하게 남는다(추가 컬럼은 DEFAULT 있고, 새 테이블은
  아무도 안 읽는다). 마이그레이션 되돌림 불필요.
- 부피는 런당 (검색 횟수 상한 × 결과 상한)으로 유계라 정리 잡이 필요 없다.

## Validation

- **Commands to run:** `./mvnw -o test` (Testcontainers가 pgvector/redis를 띄운다).
- **Expected output:** 신규 통합 테스트 통과 + 기존 knowledge/QA 통합 테스트 무변경 통과.

## Risks & Rollback

- **Risk:** `store()`를 트랜잭션으로 감싸면서 문서 추출 배치의 실패 모드가 "일부 저장"에서
  "전부 롤백"으로 바뀐다. → 현재도 `saveAll` 한 번이라 사실상 전부 아니면 전무였고, 실패는
  `DocumentKnowledgeExtractionService`가 이미 삼켜 `parse_status=FAILED`로 남긴다. 동작 차이 없음.
- **Risk:** 검색 경로에 INSERT가 하나 늘어 Agent 도구 응답이 그만큼 늦는다. → 이미 임베딩 왕복
  (네트워크)을 한 뒤이므로 로컬 INSERT 한 문장은 무시할 수준.
- **Rollback:** `git revert`.

## Rejected / Deferred

- **조회 HTTP API를 만들지 않는다.** 지시서는 "만든다면 `QaStatsController`/`QaStatsDtos` 형태를
  따르라"고 했는데, 그 둘은 미머지 브랜치(ARTEL-243)에만 있다. 지금 만들면 같은 `/api/*-stats`
  자리에 두 컨트롤러가 서로를 모른 채 생기고, 머지 때 인증·기간 파싱 관례를 다시 맞춰야 한다.
  리포지토리까지 내고 API는 ARTEL-243 머지 후 후속으로 올린다.
- **축 이름 화이트리스트 파라미터화**: 위 Step 1f 사유로 기각.

- **Agent에 `update_knowledge`를 추가하지 않는다** (2026-08-05 결정, 사용자 지시).
  **이것은 이 지표의 알려진 한계이므로 PR 본문에 그대로 적는다.**

  Agent는 지금 항목을 고칠 때 지우고 다시 기록한다 — 이것은 실수가 아니라 ARTEL-189의 명시적
  결정이고, `artel-agent-server/app/agents/qa/knowledge.py:165`
  (`FORGET_KNOWLEDGE_DESCRIPTION`: "There is no update tool, and that is deliberate. ... Those two
  calls are one repair")와 `app/qa/envelope.py:40-43` 주석이 그 이유를 적어 두었다.

  결과: **수리 한 번이 DELETE + CREATE로 나가므로, 수리가 폐기로 집계된다.** 그 버전은
  `deleted_at`이 서고 `deleted_by_qa_try_id != created_by_qa_try_id`가 되어 `repudiated_versions`에
  잡힌다. 데이터만으로는 둘을 가를 수 없다.

  닫는 길은 둘이었다 — (a) Agent에 `update_knowledge`를 붙여 수리를 UPDATE로 만든다,
  (b) `replaces_id`를 채워 delete+create 쌍을 링크한다. 둘 다 이번 범위 밖으로 남긴다.
  V26 스키마는 (a)를 전제로 쓰였으므로(`version`은 content 변경 시에만 증가), 나중에 Agent에
  update 툴이 붙으면 이 리포지토리는 손대지 않고 지표가 스스로 맞아 들어간다.

  그때까지 `repudiated_versions`는 **"수리 + 폐기"의 합**으로 읽어야 한다. `retrieval_total`과
  `citation_known_total`은 이 한계와 무관하게 그대로 유효하다.

## Open Questions

- 없음. 설계 판단을 바꿔야 할 근거를 발견하면 임의로 바꾸지 않고 보고한다.
