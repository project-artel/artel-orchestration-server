# 2026-08-06 — knowledge edge 테이블로 지식창고를 그래프로 만든다

- Date: 2026-08-06
- Jira: (미발급)
- Status: Draft
- Base: `origin/develop` = `ad40453`

## Goal

지식창고 항목 사이의 **관계를 타입과 이유와 함께 저장**하고, 검색이 그 관계를 타고 이웃을
가져오게 한다.

지금 `knowledge`는 평평한 자루다. 항목마다 `tag` 하나를 달고, 도달 경로는 항목당 3개의
LLM 생성 질문에 대한 벡터 유사도뿐이다(`KnowledgeVectorSearchRepository.searchNearest`).
두 항목이 같은 메커니즘을 말한다는 것도, 하나가 다른 하나의 예외라는 것도, 서로 모순이라는
것도 어디에도 없다. 일반 규칙을 검색한 QA 런은 한 홉 옆에 더 구체적인 규칙이 있다는 것을
영영 모르고, 운영자는 지식창고가 내부적으로 모순이 됐다는 것을 볼 방법이 없다.

만드는 것 셋:

1. **`knowledge_edge`** — 에이전트가 명시한, 타입이 있고 이유가 적힌 관계.
2. **벡터 이웃** — 저장하지 않고 조회 시점에 계산. 임베딩 모델이 바뀌면 통째로 옳지 않게 되는
   파생값이라 테이블에 넣으면 갱신 책임이 생긴다. K-NN을 물화하면 부피도 N×K로 폭증한다.
3. **1홉 자동 확장 + `expand_knowledge` 도구 + 접기 미들웨어.**

## Non-goals

- 그래프 DB 도입. PostgreSQL + 재귀 없는 레벨별 BFS로 충분하다(깊이 상한 2).
- **edge의 `note`를 고치는 경로.** edge에서 고칠 수 있는 것은 `note`뿐이고(끝점과 relation은
  정체성이라 바꾸면 다른 edge다), 틀린 `note`는 unlink 후 다시 link하면 된다 — `knowledge`가
  `update_knowledge`를 따로 둔 이유(본문이 길어 재입력이 비싸다)가 여기엔 없다.
- admin-page 그래프 시각화(`GET /api/knowledge/{id}/graph`).
- edge에 대한 `knowledge_event` 이력. 아래 "Context" 참조.
- CONTENT 임베딩 백필. 아래 §벡터 이웃 참조 — 필요 없다.

## Context / Constraints

### ARTEL-256(V28)이 이 작업의 전제를 셋 뒤집었다

이 계획의 첫 판은 ARTEL-256 머지 **전**에 쓰였고, 아래 셋이 그때와 다르다. 착수 전 반드시 읽을 것:
`V28__add_knowledge_scope.sql`, `entity/KnowledgeScope.kt`, `entity/KnowledgeMode.kt`.

**(1) 마이그레이션 번호는 V29다.** V26 중복은 `7777710`이 해소했고(ARTEL-255 쪽을 V27로 밀었다),
V28은 ARTEL-256이 가져갔다. 착수 시점에 다시 확인한다 — 이 레포는 같은 사고를 세 번 냈고
(`V24`↔`V25`, `V26` 둘, 그리고 이 번호), `0f59a14`가 CI 검사를 붙였지만 브랜치가 갈린 동안은
여전히 사람이 확인해야 한다.

**(2) 하드 FK를 걸지 않는다 — 첫 판의 결정을 뒤집는다.** 첫 판은
`from_knowledge_id BIGINT NOT NULL REFERENCES knowledge(id) ON DELETE CASCADE`였다. V28이 `shadows_id`
(knowledge → knowledge 참조)를 논리참조로 두면서 그 이유를 명시했다: *"knowledge는 프로젝트·문서·
런·이제 다른 knowledge까지 전부 논리참조로 들고 있고, 여기에만 하드 FK를 걸면 baseline 정리가
스코프 행에 막힌다."* edge는 `shadows_id`와 정확히 같은 모양의 참조다. 여기만 하드 FK를 걸면
실험 스코프가 남긴 edge 때문에 baseline 행을 지울 수 없다. 관례를 따른다.

**(3) 읽기는 전부 `KnowledgeScopeSql.VISIBLE`을 탄다.** 스코프를 빠뜨린 읽기는 격리를 뚫고,
뚫린 격리는 조용하다. ARTEL-256이 파생 쿼리를 없애고 술어를 상수 하나로 모은 이유이며,
`KnowledgeScope`를 기본값 없는 value class로 만들어 **빠뜨린 호출이 컴파일되지 않게** 한 이유다.
새 traversal 질의도 예외가 아니다.

### 그림자와 edge — 이 작업에서 제일 틀리기 쉬운 자리

baseline `B1`이 있고 스코프 `S` 런이 그것을 고치면, `B1`은 그대로 두고 `scope_id=S, shadows_id=B1`인
그림자 `S1`이 생긴다. 스코프 `S`에서 `B1`은 안 보이고 `S1`이 보인다.

그런데 baseline에 `B1 REFINES B2` edge가 있다. 스코프 `S`에서 이 edge는 **`S1 REFINES B2`로 보여야
한다** — `S1`은 그 스코프에서 곧 `B1`이기 때문이다. `VISIBLE` 술어는 "가려진 baseline을 결과에서
뺀다"까지만 하고 "그림자로 갈아끼운다"는 하지 않으므로, 그것만 걸면 이 edge는 스코프 런에서
**사라진다**.

해소는 **정규 id(canonical id)** 하나다. 보이는 행마다 그것이 대표하는 baseline id를
`COALESCE(shadows_id, id)`로 구하고, edge는 양끝을 이 정규 id로 맞춘다.

- **쓰기**: 끝점을 정규화해 저장한다. 스코프 런이 `S1`을 링크해도 `from_knowledge_id`에는 `B1`이
  들어간다. 안 그러면 baseline 그래프와 스코프 그래프가 id 공간에서 갈라져, 실험이 끝난 뒤
  그 edge가 무엇을 가리켰는지 아무도 못 읽는다.
- **읽기**: 정규 id로 매칭하고, 결과로 내보낼 때 그 스코프에서 실제로 보이는 행(`S1`)의 내용을 싣는다.
- 툼스톤(그림자이면서 `deleted_at`이 찍힌 것)은 `VISIBLE`이 이미 baseline을 가리고, 그림자 자신은
  각 질의의 `deleted_at IS NULL`이 뺀다. 그래서 스코프 런이 지운 항목은 이웃으로도 안 나온다 — 맞다.

### `KnowledgeMode`가 새 도구 둘을 모두 게이트한다

`LEARNING` / `FROZEN` / `OFF`, `qa_try.run_config.knowledge_mode`에 남고 **Orchestration이 집행한다**
(arm마다 Agent 프롬프트가 바이트 단위로 같아야 하므로).

- `link_knowledge`는 쓰기다 → `mode.writable`(= `LEARNING`)일 때만. 아니면 기존 쓰기 프레임과 같이 거부.
- `expand_knowledge`는 읽기다 → `mode.readable`(= `OFF` 아님)일 때만. **`OFF`는 오류가 아니라 빈
  결과**로 답한다 — `KnowledgeSearchService.search`가 `OFF`를 그렇게 처리하는 것과 같은 이유다.
  오류로 답하면 Agent가 도구 실패로 보고 재시도해서 대조군 arm의 행동이 달라진다.
- 검색에 붙는 1홉 자동 확장은 `OFF`면 애초에 히트가 없으므로 따로 분기할 것이 없다.

### 그 밖의 선행 사실

- `knowledge.replaces_id`(V27:38)는 **한 번도 쓰인 적 없다.** `grep -rn "replaces_id\|replacesId" src/`가
  마이그레이션과 이것을 아쉬워하는 KDoc 둘(`KnowledgeStatsRepository`, `KnowledgeEntity`)만 낸다.
- `knowledge_entry_facts`는 V28에서 `WHERE k.scope_id IS NULL`이 붙었다. 컬럼 이름·타입·순서는
  `CREATE OR REPLACE`라 **하나도 못 바꾼다.** 이번 작업은 이 view를 건드리지 않는다.
- 벡터는 `kind='QUERY'`만 채워져 있다. 항목당 3개, 내용이 아니라 **그 항목이 답하는 질문**이다.
- WS 라우터는 projectId를 payload가 아니라 `qaTryId → gameInstanceId → projectId`로 푼다.
  scope/mode도 같은 경로(`qa_try`)에서 온다. 이 방향을 유지한다.

### relation 다섯 — 각 값이 통과해야 하는 시험은 "읽는 쪽이 그것 때문에 다르게 행동하는가"다

| 값 | 방향 | 뜻 | 자리를 얻는 이유 |
|---|---|---|---|
| `LEADS_TO` | `from` → `to` | `from` 화면에서 `to` 화면으로 가는 경로가 있다. `note`가 **무엇을 했는지**를 진다 | **화면 지도**의 전부. 거의 모든 런이 무언가를 시험하기 전에 거기 도달하는 법부터 알아내는 데 시간을 쓰고, 지금은 매 런이 그것을 맨바닥에서 다시 한다 |
| `CONTRADICTS` | **대칭** | 둘이 동시에 참일 수 없다 | 가장 값진 신호. 판정을 바꾸고(둘 다 맹신하면 안 된다) 운영자에게는 지식창고가 모순이 됐다는 경보다 |
| `REFINES` | `from` → `to` | `from`이 `to`의 더 좁은 경우·예외·조건 | 일반 규칙에 걸렸을 때 그 예외가 딸려 오는 것 — 의미 관계 쪽의 주력 |
| `DEPENDS_ON` | `from` → `to` | `from`은 `to`가 성립하는 동안만 성립한다(선행조건) | REFINES와 행동이 다르다: `from`을 쓰기 **전에** 선행조건이 지금 성립하는지 확인해야 한다 |
| `REPLACES` | `from` → `to` | `from`이 `to`를 대체한다(대상은 소프트삭제 예정) | `replaces_id`가 하려던 일. 유일한 생명주기 값 |

**대칭은 한 행 + 양방향 조회.** 두 행이면 쓰기가 두 배가 되고 unlink가 반쯤 실패할 수 있는
2행 연산이 되며 유니크 인덱스가 무의미해진다. `CONTRADICTS`는 쓰기 시점에 `from = min, to = max`로
정규화하고 `ck_knowledge_edge_symmetric_order`가 서비스 버그를 막는다. 렌더는 `contradicts`를
방향어 없이 찍고, 나머지 넷은 도착한 쪽에 따라 `leads to`/`reached from`, `refines`/`refined by`,
`depends on`/`required by`, `replaces`/`replaced by`로 찍는다.

**거부한 후보**: `RELATED_TO`/`SEE_ALSO`(벡터 채널이 이미 "이것과 비슷한 게 또 뭐냐"에 답하고,
그쪽은 계산값이라 오염되지 않는다. 게다가 catch-all은 기본값이 되어 — 쉬운 선택 하나와 어려운
넷이 있으면 쉬운 것이 골라진다 — 그래프를 무타입으로 퇴화시킨다. 도구 설명이 대신 "넷 중 맞는
것이 없으면 링크하지 말라"고 말한다); `PART_OF`/`SUBSUMES`(REFINES와 거의 겹치고, 두 런이 같은
쌍을 둘로 쪼갠다); `CAUSES`(게임 메커니즘에 대한 주장이라 `description`에 속한다);
`SAME_AS`/`DUPLICATE_OF`(중복은 병합할 것이지 영구화할 것이 아니다); `SUPERSEDED_BY`(REPLACES를
거꾸로 읽은 것); knowledge 아닌 것으로 가는 이종 edge(`to_type` 판별자가 필요해지고 traversal SQL이 갈라진다).

`SIMILAR`은 **CHECK에 없다** — 영영 저장될 수 없어야 한다(§벡터 이웃).

**fanout 우선순위**: `CONTRADICTS(0) < LEADS_TO(1) < REFINES(2) < DEPENDS_ON(3) < REPLACES(4)`.
모순이 맨 앞인 것은 판정을 바꾸는 유일한 신호이기 때문이고, `LEADS_TO`가 그다음인 것은 그 화면에
서 있는 순간 가장 실행 가능한 정보이기 때문이다. **이 순서는 추측이다** — "Risks" 참조.

## Approach (Checklist)

작업을 4개로 쪼갠다. 하나로 묶으면 레포 2개 + 마이그레이션 + WS 계약 + 도구 산문 + 미들웨어가
한 PR에 들어가 리뷰가 불가능하다. Jira 이슈도 4개다.

### A. Orchestration — 스키마와 쓰기 경로

- [ ] `V29__create_knowledge_edge.sql`

```sql
CREATE TABLE IF NOT EXISTS knowledge_edge (
    id                   BIGSERIAL PRIMARY KEY,
    -- 읽기가 항상 project로 먼저 자르므로 비정규화해 둔다. 쓰기 버그로 생긴 프로젝트 교차
    -- edge가 traversal 질의에서 knowledge를 두 번 조인하지 않고도 걸러진다.
    project_id           BIGINT NOT NULL,
    -- NULL이 운영 공용(baseline). knowledge.scope_id와 같은 뜻이고 같은 이유다(V28) —
    -- 실험 런이 주장한 관계가 운영 그래프에 섞이면 되돌릴 방법이 없다.
    scope_id             BIGINT,
    -- **정규 id로 저장한다.** 그림자를 링크해도 그것이 가리는 baseline id가 들어간다.
    -- FK를 걸지 않는 것은 V28의 shadows_id와 같은 판단이다. 여기만 하드 FK면
    -- 실험 스코프가 남긴 edge에 baseline 정리가 막힌다.
    from_knowledge_id    BIGINT NOT NULL,
    to_knowledge_id      BIGINT NOT NULL,
    relation             VARCHAR(20) NOT NULL
                         CHECK (relation IN ('LEADS_TO','REFINES','CONTRADICTS','DEPENDS_ON','REPLACES')),
    -- 이 edge가 왜 있는지, 주장한 런의 말로. NOT NULL인 이유는 감사할 수 없는 edge는
    -- 아무도 확신을 갖고 지울 수 없기 때문이다. 도구가 빈 문자열을 거부한다.
    note                 TEXT NOT NULL,
    -- **이 스코프 행이 가리는 baseline edge.** knowledge.shadows_id와 정확히 같은 장치다.
    -- 다만 edge에는 "수정 그림자"가 없다 — edge에서 고칠 수 있는 것은 note뿐이고 그것은
    -- unlink 후 re-link로 되므로, 이 컬럼이 있는 행은 **항상 툼스톤**이다(deleted_at이 찍힌다).
    -- 그래서 아래 CHECK로 그 불변식을 못박는다. 나중에 note 수정 그림자가 필요해지면 그때 푼다.
    shadows_edge_id      BIGINT,
    created_by_qa_try_id BIGINT,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- knowledge와 같은 소프트삭제. unlink가 이 컬럼을 쓴다.
    deleted_at           TIMESTAMP WITH TIME ZONE,
    -- V19가 knowledge에 남긴 것과 같은 감사용 컬럼. 복원해도 지우지 않는다.
    deleted_by_qa_try_id BIGINT,

    CONSTRAINT ck_knowledge_edge_no_self CHECK (from_knowledge_id <> to_knowledge_id),
    -- CONTRADICTS는 대칭이고 **한 행**으로 저장한다. from < to로 정규화하지 않으면
    -- 아래 유니크 인덱스가 (A,B)와 (B,A)를 같은 주장으로 못 본다.
    CONSTRAINT ck_knowledge_edge_symmetric_order CHECK (
        relation <> 'CONTRADICTS' OR from_knowledge_id < to_knowledge_id
    ),
    -- 툼스톤은 반드시 스코프 행이고 반드시 죽어 있다. baseline(scope_id IS NULL)이 무언가를
    -- 가린다는 것은 말이 안 되고(가릴 대상이 자기 자신뿐이다), 살아 있는 그림자는 "note 수정
    -- 그림자"를 뜻하게 되는데 그 기능이 없다. 둘 다 조용히 틀리는 종류라 DB가 막는다.
    CONSTRAINT ck_knowledge_edge_tombstone CHECK (
        shadows_edge_id IS NULL
        OR (scope_id IS NOT NULL AND deleted_at IS NOT NULL)
    )
);

-- scope_id가 키에 들어간다. 스코프 런은 baseline과 같은 관계를 자기 스코프에 다시 주장할 수
-- 있어야 하고(baseline edge를 지울 수단이 없으므로), 그것이 baseline의 유일성을 깨면 안 된다.
--
-- 목적은 성능이 아니라 **재시도나 동시 쓰기가 같은 주장을 두 번 파일하는 것을 막는 것**이다.
-- 위반은 트랜잭션을 되돌리고 KnowledgeLinkResult.Rejected가 된다 — WS 수신 체인의 예외가 아니다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_edge_live
    ON knowledge_edge (scope_id, from_knowledge_id, to_knowledge_id, relation)
 WHERE deleted_at IS NULL;

-- 양방향. traversal은 사실상 무향이다 — 일반 규칙에 걸렸을 때 그것을 REFINES 하는 구체적
-- 항목이 나와야지, 반대 방향만 나와서는 쓸모가 없다. scope_id를 두 번째에 끼우는 것은
-- 읽기 술어가 늘 `끝점 = ? AND (scope_id IS NULL OR scope_id = ?)` 모양이기 때문이다(V28 관례).
CREATE INDEX IF NOT EXISTS idx_knowledge_edge_from
    ON knowledge_edge (from_knowledge_id, scope_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_knowledge_edge_to
    ON knowledge_edge (to_knowledge_id, scope_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_knowledge_edge_try
    ON knowledge_edge (created_by_qa_try_id);

-- 한 스코프가 같은 baseline edge에 툼스톤을 둘 만들면 아래 NOT EXISTS가 두 번 참이 될 뿐이라
-- 결과는 안 틀리지만, 그것은 지금 우연이다. V28의 uq_knowledge_scope_shadow와 같은 자리이고
-- 같은 이유로 UNIQUE다 — 인스턴스 두 대가 같은 프레임을 동시에 처리하면 서비스의 사전 검사가
-- 경합에 진다. 이 유일성이 깨진 채로 굳으면 나중에 "툼스톤 하나당 baseline 하나"를 가정하는
-- 질의(예: 실험 arm별 edge 차이)가 조용히 틀린다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_edge_scope_tombstone
    ON knowledge_edge (scope_id, shadows_edge_id) WHERE shadows_edge_id IS NOT NULL;

-- 한 번도 쓰인 적 없는 컬럼이다(grep으로 확인). 같은 사실의 집이 둘이면 조용히 어긋난다 —
-- KnowledgeSearchProperties에 model 필드를 두지 않은 것과 같은 이유다. REPLACES가 이 자리를
-- 가져가고, 컬럼과 달리 쓰기 경로가 있다. knowledge_entry_facts는 이 컬럼을 select하지 않으므로
-- view의 고정된 컬럼 순서는 건드려지지 않는다.
ALTER TABLE knowledge DROP COLUMN IF EXISTS replaces_id;
```

- [ ] `entity/KnowledgeRelation.kt` — `fromWire`/`NAMES`, `KnowledgeTag` 본을 그대로.
      우선순위 `CONTRADICTS(0) < REFINES(1) < DEPENDS_ON(2) < REPLACES(3)`를 여기 둔다(§B가 쓴다).
- [ ] `entity/KnowledgeEdgeEntity.kt`
- [ ] `repository/KnowledgeEdgeRepository.kt` — `CoroutineCrudRepository`.
      **파생 쿼리를 만들지 않는다** — ARTEL-256이 `KnowledgeRepository`에서 파생 쿼리를 없앤 것과
      같은 이유로, 스코프 술어가 이름에 안 들어가는 finder는 격리를 뚫는다. 중복 사전 검사도
      `DatabaseClient` SQL로 스코프를 명시해 건다.
- [ ] **`entity/KnowledgeEdgeScopeSql.kt`** — `KnowledgeScopeSql.VISIBLE`과 똑같은 이유로 존재하는
      상수 하나. edge를 읽는 **모든** 질의가 이것을 쓴다. 손으로 적으면 언젠가 한 곳이 빠지고,
      빠진 격리는 조용히 틀린 결과를 낸다(ARTEL-256이 파생 쿼리를 없앤 이유 그대로).

```kotlin
/** `e` 별칭의 knowledge_edge 행이 `:scopeId` 스코프에서 보이는가. */
const val VISIBLE = """
    (e.scope_id IS NULL OR e.scope_id = :scopeId)
    AND e.deleted_at IS NULL
    AND NOT EXISTS (
        SELECT 1 FROM knowledge_edge te
         WHERE te.scope_id = :scopeId
           AND te.shadows_edge_id = e.id
    )
"""
```

      `deleted_at IS NULL`을 술어 **안에** 넣는 것이 `KnowledgeScopeSql.VISIBLE`과 다른 점이다.
      거기서는 그림자 자신이 결과에 남을지를 각 질의가 정해야 했지만(수정본은 남고 툼스톤은
      빠진다), edge에는 수정 그림자가 없어 죽은 edge가 결과에 남아야 할 경우가 없다.
      단, **통계 질의는 이 상수를 쓰지 않는다** — "수리 vs 폐기"는 지워진 edge도 세어야 한다.
- [ ] `service/KnowledgeGraphService.link()` + `sealed interface KnowledgeLinkResult { Applied | Rejected }`
      - `KnowledgeMutation`을 재사용하지 않는다 — `Applied(knowledgeId)`가 edge id를 잘못 부른다.
      - **거부는 예외가 아니라 값이다.** 나쁜 프레임 하나가 WS 런을 죽이면 안 된다(기존 관례).
      - 끝점 검증: 둘 다 같은 프로젝트 + 이 스코프에서 보일 것. 정규 id로 접기.
      - `relation = REPLACES`일 때 **`to` 끝점만** 소프트삭제된 행을 허용한다. `KnowledgeRepository`에
        그 용도의 finder를 하나 추가하되, 왜 존재하는지 KDoc에 못 박아 "필터를 빠뜨릴 수 없다"는
        그 파일의 성질을 지킨다.
      - `mode.writable`이 아니면 거부.
- [ ] **`service/KnowledgeGraphService.unlink()`** — `KnowledgeService.softDeleteFromQaTry`와 같은 모양.

      끝점을 **id가 아니라 `(from, to, relation)` 삼중조로 받는다.** 에이전트는 edge id를 본 적이
      없고(이웃 줄에 찍히는 id는 knowledge id다) 그것을 노출하면 도구 하나 때문에 새 id 공간이
      생긴다. `CONTRADICTS`는 조회 전에 쓰기와 같은 정규화(`from = min, to = max`)를 거친다 —
      안 그러면 에이전트가 본 방향으로 부른 unlink가 아무것도 못 찾는다.

      `KnowledgeService.shadowRequired`와 같은 분기:
      - 운영 런이거나 대상이 이미 자기 스코프 행이면 → 그 행에 `deleted_at`·`deleted_by_qa_try_id`를 찍는다.
      - 스코프 런이 baseline edge(`scope_id IS NULL`)를 지우면 → **툼스톤 행을 만든다**
        (`scope_id = S`, `shadows_edge_id = <baseline id>`, `deleted_at = now`,
        `from`/`to`/`relation`/`note`는 baseline에서 복사). baseline은 한 행도 안 바뀐다.
      - 반환은 `KnowledgeUnlinkResult { Applied | Rejected }`. 거부는 값이지 예외가 아니다.
      - `mode.writable`이 아니면 거부.
- [ ] `dto/KnowledgeGraphDtos.kt` — `KnowledgeLinkRequest`, `KnowledgeUnlinkRequest`
- [ ] `QaAgentInboundRouter`: `SUPPORTED_TYPES` += `KNOWLEDGE_LINK`, `KNOWLEDGE_UNLINK`.
      **`KNOWLEDGE_MUTATION_TYPES`에는 넣지 않는다** — 그 집합은 `KnowledgeMutationRequest`를 파싱하는
      단일 디스패치를 몰고, link의 필드는 그 스키마에 안 맞는다. `routeKnowledgeMutation` 모양으로
      별도 분기: 파싱 → 런에서 project/scope/mode 해석 → 서비스 → `Rejected`는 `appendError`.
      단방향, 응답 없음, `CancellationException`은 재던짐.
- [ ] `KnowledgeStatsRepository` / `KnowledgeEntity`의 `replaces_id` KDoc을 `knowledge_edge`로 고침.

**edge에 `knowledge_event`를 남기지 않는다.** 그 테이블은 한 항목의 *content* 버전 이력이고,
link은 둘 중 어느 것도 아니다 — `after` 스냅샷에 담을 것이 없고 `(knowledge_id, version)` 부분
유니크가 붙잡을 것도 없다. 출처는 이미 `created_by_qa_try_id` + `created_at`이 답한다.

### B. Orchestration — 읽기와 traversal

- [ ] `config/KnowledgeGraphProperties.kt` (`artel.knowledge.graph`),
      `KnowledgeSearchProperties`처럼 `init { require(...) }` 검증

| 항목 | `expand_knowledge` | 검색 자동 1홉 |
|---|---|---|
| depth | 기본 1, 상한 **2** | 1 고정 |
| 노드당 fanout | 3 | **2** |
| 총 노드 예산 | 20 | **검색 한 번당 8** |
| visited 초기값 | 요청한 id | **모든 히트 id** |
| 벡터 이웃 | 요청 시 최대 3 | **없음** |

- 깊이 2이지 3이 아닌 이유: fanout 3에서 깊이 2는 13노드, 깊이 3은 40노드. "호출 하나가
  프로젝트를 통째로 컨텍스트에 끌어오면 안 된다"(ARTEL-180)가 선을 2에 긋는다.
- fanout 초과 시 **결정적 순서**: relation 우선순위 → `knowledge_edge.id ASC`. 모순을 맨 앞에
  두는 이유는 모순이 판정을 바꾸는 유일한 신호이기 때문이다. 결정성은 벡터 검색이
  `ORDER BY distance ASC, k.id DESC`로 동점을 못박은 것과 같은 이유다.
- fanout은 **SQL에서** 자른다(`ROW_NUMBER() OVER (PARTITION BY via_id ...) <= :fanout`).
  애플리케이션에서 자르면 그 상한이 접기 후 몇 행이 될지 모른다 — 벡터 검색이 `LIMIT` 전에
  접는 것과 같은 이유다.
- 사이클은 visited 집합만으로 죽는다(`A REFINES B`, `B CONTRADICTS A`는 한 행을 양방향에서
  읽으므로 없으면 핑퐁한다). 자기 참조는 CHECK로 불가능.

- [ ] `repository/KnowledgeGraphTraversalRepository.kt` (`DatabaseClient`). 레벨당 SQL 한 번,
      노드당이 아니다. 정규 id 접기가 여기 산다:

```sql
WITH visible AS (
    -- 이 스코프에서 보이는 행과, 그것이 대표하는 정규 id.
    -- 술어는 KnowledgeScopeSql.VISIBLE 그대로다 — 손으로 다시 적지 않는다.
    SELECT k.id,
           COALESCE(k.shadows_id, k.id) AS canonical_id,
           k.tag, k.source, k.summary, k.version
      FROM knowledge k
     WHERE k.project_id = :projectId
       AND k.deleted_at IS NULL
       AND <KnowledgeScopeSql.VISIBLE>
)
SELECT * FROM (
  SELECT e.id AS edge_id, e.relation, e.note,
         CASE WHEN e.from_knowledge_id IN (:seed0,…) THEN e.from_knowledge_id
              ELSE e.to_knowledge_id END AS via_canonical_id,
         CASE WHEN e.from_knowledge_id IN (:seed0,…) THEN 'OUT' ELSE 'IN' END AS direction,
         v.id AS knowledge_id, v.tag, v.source, v.summary, v.version,
         ROW_NUMBER() OVER (
             PARTITION BY CASE WHEN e.from_knowledge_id IN (:seed0,…)
                               THEN e.from_knowledge_id ELSE e.to_knowledge_id END
             ORDER BY CASE e.relation WHEN 'CONTRADICTS' THEN 0 WHEN 'LEADS_TO' THEN 1
                                      WHEN 'REFINES' THEN 2 WHEN 'DEPENDS_ON' THEN 3
                                      ELSE 4 END, e.id
         ) AS rn
    FROM knowledge_edge e
    -- 반대쪽 끝점을 **정규 id로** 조인한다. 그 스코프에 그림자가 있으면 그림자 행이 실리고,
    -- 툼스톤이면 visible에 아예 없어 자동으로 빠진다.
    JOIN visible v
      ON v.canonical_id = CASE WHEN e.from_knowledge_id IN (:seed0,…)
                               THEN e.to_knowledge_id ELSE e.from_knowledge_id END
   WHERE e.project_id = :projectId
     -- 스코프·소프트삭제·툼스톤이 전부 이 상수 하나다. 손으로 다시 적지 않는다.
     AND <KnowledgeEdgeScopeSql.VISIBLE>
     AND (e.from_knowledge_id IN (:seed0,…) OR e.to_knowledge_id IN (:seed0,…))
     AND v.canonical_id NOT IN (:visited0,…)
) ranked WHERE rn <= :fanout
```

  - seed와 visited는 **정규 id**로 넘긴다. 그림자 id로 넘기면 baseline edge를 못 찾는다.
  - **⚠️ 같은 레벨 안의 중복을 접어야 한다.** 툼스톤이 생겼어도 이 문제는 남는다:
    `uq_knowledge_edge_live`가 `scope_id`를 키에 갖고 있어 스코프는 baseline과 **같은** 관계를
    (예컨대 다른 `note`로) 자기 스코프에 나란히 둘 수 있고, 그 경우 툼스톤을 안 찍었다면
    baseline edge와 스코프 edge **둘 다** 이 질의를 통과해 같은 이웃이 두 줄로 나온다.
    `visited`는 레벨 *사이*만 막지 레벨 *안*은 못 막는다.
    (툼스톤을 찍으면 baseline이 빠져 문제가 없다. 즉 이 접기는 "덮어쓰기를 안 한 스코프"를 위한
    안전망이지 유일한 수단이 아니다 — unlink가 생기면서 성격이 그렇게 바뀌었다.)
    → `ROW_NUMBER()`의 `PARTITION BY`를 `(via_canonical_id, 상대 정규 id, relation)`으로 두고
    `ORDER BY (e.scope_id IS NULL), e.id`를 앞세워 **스코프 행이 baseline을 이기게** 한 뒤,
    바깥에서 via별 fanout을 다시 자른다. 창 함수 두 단이다.
    스코프가 baseline을 덮는 유일한 수단이 이 우선순위이므로, 이것은 최적화가 아니라 의미다.
  - id 바인딩은 `:seed0, :seed1, …`로 생성한다 —
    `KnowledgeVectorSearchRepository.searchNearest`의 `tagBindings` 관용구 그대로. 문자열 보간도
    Postgres 배열도 아니다. 목록은 유계다(깊이 1에서 ≤5, 깊이 2에서 ≤20).

- [ ] **히트끼리의 edge.** 양끝이 모두 히트인 edge는 visited에 걸려 빠지는데, "히트 1이 히트 3과
      모순"은 이 기능이 말할 수 있는 가장 값진 것이다. 자동 확장 경로에 작은 두 번째 질의를 더해
      이웃 줄이 아니라 히트의 주석으로 렌더한다. ~15줄. PR을 줄여야 하면 제일 먼저 잘라낼 것.

- [ ] **벡터 이웃** — `kind='QUERY'`로 충분하고 **백필 워커에 대한 의존이 없다.**
      `searchNearest`는 임베딩된 자연어 *질문*을 QUERY 벡터와 견준다. 색인 전체가 질문 공간에
      살고, `SEARCH_KNOWLEDGE_DESCRIPTION`이 에이전트에게 키워드가 아니라 질문을 쓰라고 가르치는
      이유가 그것이다. 항목↔항목도 양쪽을 그 공간에 두면 된다 — 같은 메커니즘을 다루는 두 항목은
      겹치는 질문을 낳고, 그 겹침이 곧 신호다. LLM이 이미 표현을 정규화해 뒀으므로 content↔content
      보다 나을 여지도 있다.

```sql
WITH seed AS (
    SELECT e.embedding FROM knowledge_embedding e
     WHERE e.knowledge_id = :knowledgeId AND e.kind = 'QUERY'
       AND e.model = :model AND e.embedding IS NOT NULL
)
SELECT k.id AS knowledge_id, k.tag, k.source, k.summary, k.version,
       MIN(e.embedding <=> s.embedding) AS distance
  FROM knowledge_embedding e
  JOIN knowledge k ON k.id = e.knowledge_id
 CROSS JOIN seed s
 WHERE k.project_id = :projectId AND k.deleted_at IS NULL
   AND <KnowledgeScopeSql.VISIBLE>
   AND e.kind = 'QUERY' AND e.model = :model AND e.embedding IS NOT NULL
   AND k.id <> :knowledgeId AND k.id NOT IN (:exclude0,…)
 GROUP BY k.id, k.tag, k.source, k.summary, k.version
HAVING MIN(e.embedding <=> s.embedding) <= :maxDistance
 ORDER BY distance ASC, k.id DESC
 LIMIT :limit
```

  - `CROSS JOIN seed` + `GROUP BY` + `MIN` = 두 항목의 질문 벡터 3×3 곱집합의 최소.
    기존 검색이 3개에 대해 `MIN`을 쓰는 것과 같은 공격성이고, 일부러 일치시킨다.
  - 자기 제외는 선택이 아니다 — 자기 벡터는 거리 0이다.
  - `model`은 **`KnowledgeBackfillProperties.model`**에서 온다. 그래프 전용 설정을 만들면
    읽기/쓰기 파티션이 갈라지고, 그 실패는 조용히 빈 결과다(`KnowledgeSearchProperties` KDoc).
  - 비용은 프로젝트당 3×N 거리 계산. 기존 검색의 3배 규모이고 인덱스는 필요 없다(V18 판단 유지).
  - **자동 1홉에는 벡터 이웃을 싣지 않는다.** 히트를 낸 검색 자체가 벡터 검색이므로 히트의
    벡터 이웃은 `limit`을 올렸으면 나왔을 것에 가깝다. 자동으로 붙이는 것은 "limit 올리기"가
    분장한 것이고, 새 정보 없이 전사 비용만 두 배가 된다.
  - 표시는 `relation = "SIMILAR"`이되 **표시용 라벨일 뿐**이다. 진짜 판별자는 DTO의
    `origin: EDGE|VECTOR`이고 `note`는 null이다. `SIMILAR`를 CHECK에 넣지 않는 것은 그것이
    영영 저장될 수 없게 하기 위해서다.

- [ ] `KnowledgeGraphService.expand()` + `KnowledgeExpandOutcome(neighbours, retrievals, truncated)` —
      `KnowledgeSearchOutcome`의 선례대로 **WS 계약과 기록용 사실을 가른다.**
- [ ] `KnowledgeSearchHit`에 `neighbors: List<KnowledgeNeighbour> = emptyList()` **순수 추가**.
      JSON 객체에 필드를 더하는 것이고 Agent의 pydantic `KnowledgeSearchHit`은 `extra="allow"`라
      이 변경 전 Agent는 통째로 무시한다 — §롤아웃의 비대칭이 여기서 나온다.
      `version`을 히트에 얹지 않은 기존 판단과의 차이도 의도다: `version`은 기록용 사실이고
      `neighbors`는 에이전트가 읽을 것이라 WS 계약 객체에 속한다.
- [ ] 자동 확장은 **`KnowledgeSearchService` 안**에 둔다(`KnowledgeGraphService` +
      `KnowledgeGraphProperties`를 생성자 주입). 단방향 의존이라 순환이 없고, 이미 400줄인
      `QaAgentInboundRouter`가 얇게 남는다.
- [ ] **이웃도 `knowledge_usage`에 남긴다.** 런의 컨텍스트에 들어간 것이 로그를 우회하면
      ARTEL-255의 "이 지식이 쓸모 있었나" 분모가 조용히 모자란다. `rank = NULL`(순위 매긴 것이
      아니다), `score`는 VECTOR면 유사도 EDGE면 NULL. 두 컬럼 다 nullable이고 "null = 모른다"는
      이미 그 파일들의 관례다.
- [ ] `KNOWLEDGE_EXPAND` / `KNOWLEDGE_EXPAND_RESULT` + `routeKnowledgeExpand`.
      `routeKnowledgeSearch` 모양: **세션 검사 먼저**(답할 곳이 없으면 일을 시작하지 않는다),
      모든 실패에 `failExpand`(감사 로그 + `ERROR` 프레임)로 멈춰 있는 도구를 풀어 주고,
      응답 **전에** 사용 기록, 그다음 결과 프레임. `mode.readable`이 아니면 빈 결과.
      오류는 `common/error/ApiException.kt` 타입 예외로(신규 `ResponseStatusException` 금지).

### C. Agent — 도구

- [ ] `app/qa/envelope.py`: `MessageType` += `KNOWLEDGE_LINK`, `KNOWLEDGE_EXPAND`,
      `KNOWLEDGE_EXPAND_RESULT`(라우터가 기대하는 철자 그대로, 단방향/응답 있음 주석 관례 유지).
      `KnowledgeLinkPayload`, `KnowledgeExpandPayload`, `KnowledgeNeighbour`,
      `KnowledgeExpandResultPayload` — 전부 기본값, `extra="allow"`. 검증 실패가 기다리는 도구를
      20초 매달아 두기 때문이라는 기존 `KnowledgeSearchHit` 논거 그대로.
      `KnowledgeSearchHit` += `neighbors: list[KnowledgeNeighbour] = Field(default_factory=list)`.
- [ ] `app/qa/channel.py`: `expand_knowledge()` — `search_knowledge()`를 본뜨되 **세 번째 waiter**를
      쓴다(공유하지 않는 이유는 기존 주석대로: 지식 검색과 게임 액션이 서로의 future를 풀면 안 된다).
      같은 20초, 같은 3결과(`payload | KnowledgeSearchFailed | None`).
      `link_knowledge`는 기존 단방향 `write_knowledge()`를 탄다.
- [ ] `app/qa/service.py` ~L178에 `KNOWLEDGE_EXPAND_RESULT` 디스패치 분기.
- [ ] `unlink_knowledge(step, thought, from_knowledge_id, to_knowledge_id, relation)` 도구와
      `UNLINK_KNOWLEDGE_DESCRIPTION`. edge id가 아니라 삼중조를 받는 이유는 §A와 같다.
      문턱은 `forget_knowledge`보다 **낮다** — edge를 지워도 항목 둘은 그대로 남고, 잃는 것은
      연결 하나와 `note` 하나다. 그래도 조용하기는 마찬가지라(지운 경로는 그냥 없어지고 아무도
      안 물어본다) 설명이 가르칠 것: 화면이 **바뀐 것**과 화면이 **깨진 것**을 가릴 것 —
      경로가 사라진 것처럼 보이는 이유는 대개 빌드가 깨진 것이고 그것은 `report_issue`다;
      한 번 안 됐다고 지우지 말 것; `LEADS_TO`는 조건부 경로일 수 있으니 `note`의 조건을 먼저 읽을 것.
- [ ] `app/agents/qa/arch.py`: `MAX_LINKS_PER_RUN = 3`, `MAX_EXPANDS_PER_RUN = 3`,
      `MAX_UNLINKS_PER_RUN = 2`(삭제 계열이라 `MAX_FORGETS_PER_RUN`과 같은 자리에 둔다).
      새 validator `unlinks_need_searches` — 이웃은 검색에 딸려 오므로 검색 없이 unlink만 허용한
      spec은 호출될 수 없는 도구를 켜 둔 것이다.
      `QaArchSpec`(`ge=0, le=50`)·`ResolvedArch`·`tool_call_limit` 기본 합에 더한다(둘 다 런 단위
      허용량이라 스텝당이 아니라 기본에 속한다 — 검색에 대한 기존 KDoc 논거 그대로).
      사다리 근거: link는 파괴적이지 않지만 하나하나가 지속되는 주장이라 기록(5)보다 아래인 3.
      expand는 읽기이고 검색 뒤에만 쓸모가 있으며 자동 1홉이 흔한 경우를 이미 덮으므로 검색(6)보다
      아래인 3. `forgets_need_records`와 나란한 새 validator:

```python
@model_validator(mode="after")
def links_need_searches(self) -> "QaArchSpec":
    """검색이 보여준 id만 링크할 수 있으므로, 검색 없이 링크만 허용한 spec은
    법적으로 호출될 수 없는 도구를 켜 둔 것이다."""
```

      **핑거프린트**: `ResolvedArch` 필드와 `build_tools`의 도구가 늘면 `arch_fingerprint`가 움직인다.
      구조가 실제로 바뀌었으니 맞다. `QA_ARCH_LABEL`은 **올리지 않는다**(구조의 *종류*는
      그대로다 — 여전히 `create_agent` 도구 루프이고, 핑거프린트가 바로 이걸 잡으려고 있다).
      `_FINGERPRINT_SCHEME`도 **올리지 않는다**(해시하는 사실의 집합은 그대로, `arch`의 내용만 달라졌다).
- [ ] `app/agents/qa/knowledge.py`: `KNOWLEDGE_RELATIONS`, `MAX_NEIGHBOUR_SUMMARY_CHARS = 120`,
      이웃 블록 마커 상수. `LINK_KNOWLEDGE_DESCRIPTION` / `EXPAND_KNOWLEDGE_DESCRIPTION`을
      이 모듈의 산문 스타일로(도구 설명이 사용 정책의 단일 출처, 시스템 프롬프트는 안 건드림 — ARTEL-192).
      - link 설명이 가르칠 것: relation별 한 줄 판별법(`LEADS_TO`의 `note`는 *왜*가 아니라
        **무엇을 했는지** — 경로를 쓸 수 있게 만드는 것이 그 문장이다); `note`는 유일한 감사 기록;
        **넷 중 맞는 것이 없으면 링크하지 말 것**(거부한 `RELATED_TO`를 대신하는 문장);
        link은 지속되고 이후 모든 런이 읽는다는 것; 양끝이 이 런이 본 id여야 한다는 것.
      - expand 설명이 가르칠 것: 1홉 이웃은 검색과 함께 이미 왔으므로 이것은 더 가거나 유사 항목을
        보려는 것; `SIMILAR`는 기계의 짐작이고 타입 있는 넷은 런이 이유를 적어 주장한 것이라
        같은 무게로 재면 안 된다는 것.
      - `RECORD_KNOWLEDGE_DESCRIPTION`에 **화면 항목을 명시적으로 초대하는 문단**을 더한다
        (화면 하나 = 항목 하나, `UI` 태그, 무엇을 위한 화면이고 거기서 무엇을 할 수 있는가).
        런 상태를 배제하는 기존 문장("플레이어가 골드 500을 갖고 있다")은 **그대로 둔다** —
        화면 항목과 런 상태를 가르는 선이 정확히 거기다.
      - 렌더러: `render_neighbour`, `render_neighbours`, `render_expansion`. 히트 안의 이웃 한 줄:
        `   ↳ [id 412 · contradicts] Purchases are blocked below the item price`
      - **접힌 줄에 `note`를 싣지 않는다.** 감사자의 필드이고, 인라인하면 이웃당 비용이 두 배가
        된다. expand 출력에서는 `note`를 **찍는다**. 벡터 이웃은 `~ [id 88 · similar 0.71] …`.
- [ ] `QaRunState`: `knowledge_links_attempted`, `knowledge_expands_attempted`,
      **`knowledge_glimpsed: dict[str, str]`**.

      **이 작업에서 제일 날카로운 통합 위험이다.** `tools.py:391`의 `knowledge_seen` 채우는 루프가
      `entry.neighbors`와 expand 결과의 이웃도 훑어야 한다 — 아니면 에이전트는 보여 준 id를
      아예 쓸 수 없다. 그런데 이웃은 **`knowledge_glimpsed`에만** 넣고 `knowledge_seen`에는 넣지 않는다:

      - `knowledge_seen`의 취지는 "읽지 않은 것은 고치거나 지울 수 없다"이다. 120자로 잘린 한 줄은
        읽은 것이 아니다. `FORGET_KNOWLEDGE_DESCRIPTION`은 삭제를 "런에서 할 수 있는 가장 파괴적인
        일"이라 부르는데, 그 전제조건을 한 줄짜리로 낮추는 것이 이 기능이 낼 첫 회귀가 된다.
      - `update_knowledge` / `forget_knowledge`는 계속 `knowledge_seen`을 요구한다. 다만 거부 메시지에
        분기가 필요하다: `knowledge_glimpsed`에는 있고 `knowledge_seen`에는 없으면
        *"이웃 줄로만 봤다 — 검색해서 전문을 읽은 뒤 다시 부르라"*. 아니면 에이전트가 설명할 수 없는
        거부를 맞는다.
      - `link_knowledge`는 **둘 중 아무 쪽**의 끝점이든 받는다(관계를 주장하는 것은 파괴적이지 않고,
        두 항목이 모순인지 알기에는 요약으로 충분하다). `expand_knowledge`의 seed도 마찬가지.
      - 이름이 구분을 진다: `seen` = 전문을 읽음, `glimpsed` = 한 줄로 봄.
      - `forget_knowledge`는 `knowledge_seen`에서 pop한다(`tools.py:673`). `knowledge_glimpsed`에서는
        **pop하지 않는다.**
      - 부수 효과로, `CONTRADICTS` 이웃을 발견한 에이전트는 그것을 지우기 전에 검색 하나를 써서
        읽어야 한다. 이것은 기능이다.
- [ ] `app/agents/qa/tools.py`에 도구 둘:

```python
async def link_knowledge(step: int, thought: str, from_knowledge_id: str,
                         to_knowledge_id: str, relation: str, note: str) -> str
async def expand_knowledge(step: int, thought: str, knowledge_id: str, depth: int = 1) -> str
```

      둘 다 `_run`을 타지 않는다(게임을 건드리지 않는다 — 기존 지식 도구와 같은 이유).
      프레임이 나가기 **전에 로컬에서** 전부 검증한다. link은 단방향이라 Orchestration의 거부가
      내려오지 않기 때문이다: 예산 소진 → 남은 수와 함께 거부; `relation`이 `KNOWLEDGE_RELATIONS`에
      없음 → 거부; `note`가 빈 문자열 → 거부(반대편 `NOT NULL`은 프레임을 조용히 떨군다);
      끝점이 `knowledge_seen ∪ knowledge_glimpsed`에 없음 → 거부; `from == to` → 거부.
      `QaCancelled`는 재던지고, 그 밖의 예외는 보고하고 런은 계속.
      `expand_knowledge`는 `search_knowledge`의 3결과 처리를 그대로 본뜨고 `depth`를 로컬에서 2로 자른다.

### D. Agent — 접기

- [ ] `render_results`가 이웃 블록을 마커로 감싼다. 지식 결과는 오늘 마커가 없으므로
      `<<knowledge neighbours for search {serial}>> … <</knowledge neighbours>>`,
      `SceneMemory.render`가 관측 번호를 다는 것과 똑같이 검색별 일련번호를 싣는다.
- [ ] `app/agents/qa/context.py`: `fold_stale_knowledge(messages, keep=1)`.
      `fold_stale_scenes`와 같은 계약: 순수, 모델 입력 전용, 멱등, `ToolMessage.content`만,
      안 바뀐 메시지는 같은 객체로 반환. placeholder는 접힌 id를 이름으로 부르고
      `expand_knowledge`로 되찾을 수 있다고 말한다(마커 문법으로 짓지 않는 것은 `_placeholder`의 이유대로).
- [ ] **`app/prompts/qa_run/v9/`** — 시스템 프롬프트를 md 제목으로 구조화하고 "The knowledge base"
      절을 새로 넣는다. 본문의 나머지는 v8 그대로다. `vision_directive.md`는 v8에서 복사.

      **ARTEL-192와의 선.** "툴 설명이 사용 정책의 단일 출처, 시스템 프롬프트는 안 건드림"은 그대로
      유지한다 — 도구 **하나를 어떻게 부르는가**는 여전히 전부 도구 설명에 있다. 새 절이 말하는 것은
      `observe_scene` · `record_knowledge` · `link_knowledge`에 걸친 **작업 습관**이고, 그것은 어느 한
      도구 설명에도 집이 없다. 습관을 도구 셋에 쪼개 넣으면 셋이 서로 어긋난다.

      절의 구성 둘:
      - **화면 지도** — 화면 하나당 항목 하나(씬, 그리고 액션 가능한 것을 바꾸는 패널·오버레이·
        다이얼로그·탭 각각. 마을 위의 상점 패널은 마을의 각주가 아니라 자기 화면이다), `UI` 태그.
        전이마다 `LEADS_TO` 하나, `note`에 **무엇을 했는지**("마을 상단바의 상점 버튼", "Escape 또는
        패널 우상단 X"). 왕복은 서로 다른 두 경로다 — 나가는 길이야말로 나중 런이 막히는 지점이다.
        **실제로 걸어 본 것만 넣는다** — 런의 쓰기와 링크 예산은 한 줌이고, 안 가 본 화면을 지도에
        채우는 런은 지도를 지어내면서 시험을 멈춘 것이다. 지도는 런들에 걸쳐 조립된다.
      - **그 밖의 지식 구조화** — `REFINES`(구체적 → 일반), `DEPENDS_ON`(선행조건),
        `CONTRADICTS`(가장 값지고 가장 기록 안 되는 것 — 알아차리는 순간이 보통 둘 중 무엇을 믿을지
        고민하느라 바쁜 순간이라서), `REPLACES`. 그리고 "그 밖에는 링크하지 말 것".

      **⚠️ 로더는 `versions[-1]`을 기본으로 쓴다**(`app/prompts/loader.py:228`, 숫자 정렬).
      `qa_prompt_version`을 고정하지 않은 환경에서는 **v9 디렉터리가 생기는 순간 모든 런의 기본
      프롬프트가 바뀐다.** v9는 `link_knowledge`와 `LEADS_TO`를 말하므로 **도구가 없는 상태로
      v9가 먼저 나가면 없는 도구를 쓰라고 가르치는 프롬프트가 된다.** 그래서 v9는 C와 **같은 PR**에
      들어가거나 C 뒤에 온다. 별도 PR로 앞세우지 않는다.
- [ ] `runner.py`: `middleware_names_for`의 `"fold_scene_views"` 바로 뒤에
      `"fold_knowledge_neighbours"`, `build_middleware`의 `builders`에 짝을 맞추고
      새 `QaArchSpec.fold_stale_knowledge: bool = True`로 게이트한다 — `fold_stale_scenes`와 나란해서
      하드코딩이 아니라 실험 축이 된다. 기존 미들웨어를 확장하지 않고 별도로 두는 것은 둘이
      독립적으로 꺼져야 하고 핑거프린트가 미들웨어 순서를 해시하기 때문이다.

**접는 것은 이웃 블록뿐이고 히트의 요약·본문은 절대 건드리지 않는다.** 선이 거기 있는 이유:
`fold_stale_scenes`가 장면을 접는 것은 게임이 움직였고 `observe_scene`이 되찾아 주기 때문이다.
지식 본문은 **stale이 아니고**(문서가 바뀐 게 아니다) 다시 읽으려면 6개뿐인 검색 예산을 쓴다 —
접으면 에이전트에게 "귀한 자원을 써서 이 접기를 되돌리라"고 말하는 셈이라 장면 쪽보다 확연히
나쁜 거래다. 이웃 블록은 둘 다 반대다: **요청하지 않았는데 온 것**이고(자동 확장이 자원했다)
**`expand_knowledge`로 정확히 되찾을 수 있다**(자기 예산이 따로 있다). 그래서 이 기능이 들여온
증가분만 딱 접히고, `knowledge.py:74-77`이 적어 둔 기존 부채는 무관한 PR 안에서 조용히
갚아 버리지 않고 그대로 남는다.

**숫자.** 오늘 검색 한 번 = 5히트 × (헤더 ~60 + 요약 ~80 + 본문 ≤500) ≈ 3,200자 ≈ 900토큰, 그리고
아무것도 안 접힌다 — 검색 6번이면 ~5,400토큰이 영구히 남는다. 자동 1홉(fanout 2 / 총 8)은
8줄 × (~26자 + 120자 요약) ≈ 1,184자 ≈ **330토큰, 검색당 +37%**, 6검색 런에서 ~2,000토큰.
거부한 5히트 × fanout 3 = 15줄은 검색당 ~620토큰으로 **+70%** — 총 상한을 순진한 `히트×fanout`이
아니라 8로 둔 이유가 이것이다. `expand_knowledge`는 깊이 2 / fanout 3 / 20노드에 note까지 찍어
호출당 ~1,400토큰, 3번이면 4,200 — 진짜 위험은 여기이고 expand 예산을 3, 노드 예산을 20으로
둔 이유다.

`compaction.py`와의 관계: `SummarizationMiddleware`는 옛 메시지를 통째로 갈아치우므로 접힌 블록이
아예 요약되어 사라질 수 있다. 충돌이 아니다 — 접기는 모델 입력 전용이다. 다음 사람이 다시
알아내지 않도록 새 모듈 docstring에 한 문장 남긴다.

## Validation

### Kotlin — `src/test/kotlin/kr/artel/orchestration/knowledge/`

JUnit5 + Testcontainers `pgvector/pgvector:pg16`(`support/PostgresTestContainer.kt`),
`@ActiveProfiles("test") @SpringBootTest(webEnvironment = NONE)`. 각 케이스가 무엇을 지키는지
한국어 KDoc으로.

- `KnowledgeEdgeIntegrationTest` — 정상 경로; 자기 링크 거부; 다른 프로젝트 끝점 거부; 부분
  유니크로 중복 거부; `CONTRADICTS` 정규화(a→b 뒤 b→a가 `from < to` 한 행); 미지의 relation 거부;
  빈 note 거부; `REPLACES`는 소프트삭제된 대상 허용 / `REFINES`는 거부;
  **`mode=FROZEN`/`OFF`에서 link 거부**; **그림자를 링크하면 baseline 정규 id로 저장**.
- `KnowledgeEdgeUnlinkIntegrationTest` — 운영 런이 운영 edge를 지우면 그 행에 `deleted_at`이 찍힌다;
  스코프 런이 **자기** edge를 지우면 툼스톤이 아니라 그 행이 지워진다;
  스코프 런이 **baseline** edge를 지우면 **툼스톤 행이 생기고 baseline은 한 행도 안 바뀐다**;
  같은 baseline에 툼스톤을 두 번 찍으면 유니크가 막는다;
  `CONTRADICTS`를 에이전트가 본 방향(`to→from`)으로 불러도 정규화되어 찾아진다;
  없는 edge는 거부(예외 아님); `mode=FROZEN`에서 거부;
  `ck_knowledge_edge_tombstone` 위반(살아 있는 그림자 / baseline 그림자)이 DB에서 막힌다.
- `KnowledgeGraphTraversalIntegrationTest` — 깊이 1/2; `CONTRADICTS` 우선의 fanout 상한;
  노드 예산이 `truncated`를 세움; 사이클(`A REFINES B`, `B CONTRADICTS A`) 종료; 소프트삭제된
  이웃 제외; 소프트삭제된 edge 제외; 프로젝트 격리; 히트끼리의 edge 노출.
  **스코프 케이스**: 스코프 런이 baseline edge를 본다; 그림자가 있으면 이웃으로 **그림자 행**이
  실린다; 툼스톤이면 이웃이 **안 나온다**; 스코프가 만든 edge는 운영 런에 **안 보인다**;
  다른 스코프의 edge도 안 보인다; **스코프가 baseline과 같은 관계를 다시 주장하면 이웃이 한 줄만
  나오고 그 줄이 스코프 행이다**(중복 접기); **에이전트가 그림자 id로 링크하면 baseline 정규 id로
  저장되고, 운영 런에서도 그 edge가 제자리에 보인다**;
  **툼스톤이 있으면 그 스코프에서 이웃이 안 나오고, 같은 baseline edge가 운영 런에는 그대로 보인다**
  (격리의 핵심 케이스 — 이것이 깨지면 실험이 운영 그래프를 깎는다).
- `KnowledgeSimilarEntriesIntegrationTest` — 좌표축 벡터를 심는다.
  `KnowledgeVectorSearchIntegrationTest`와 같은 수법이고 이유도 같다(해시 유래 벡터는 순위가
  예측 불가라 상한을 시험할 수 없다). 자기 제외; 임계값 제외; exclude 집합; 벡터 없는 항목은
  그냥 없음; 스코프 가시성. **순서와 제외만 단언하고 임계 상수는 절대 단언하지 않는다.**
- `KnowledgeSearchRouterIntegrationTest`(확장) — `KNOWLEDGE_SEARCH_RESULT`가 `neighbors`를 싣는다;
  **기존 단언이 하나도 안 바뀌고 통과한다**(계약의 순수 추가성이 곧 시험이다);
  `knowledge_usage`에 `rank IS NULL`인 이웃 행이 생긴다.
- `KnowledgeExpandRouterIntegrationTest` — 정상 경로; 파싱 불가/미지 id에 `ERROR` 프레임;
  세션 없음 가드; 응답 **전에** 기록; `mode=OFF`는 오류가 아니라 빈 결과.

```bash
cd /home/yunseong/dev/artel/.worktrees/orche-knowledge-edge && ./mvnw clean test
```

### Python — `tests/`

- `test_qa_knowledge.py`(확장) — 이웃이 히트 아래 렌더된다; 120자에서 잘린다; 이웃이
  `knowledge_glimpsed`에 들어가고 `knowledge_seen`에는 **안 들어간다**; `forget_knowledge`가
  glimpsed 전용 id를 **"전문을 읽으라"는 메시지와 함께** 거부한다; `link_knowledge`가 glimpsed
  끝점을 받는다; link의 예산/relation/빈 note/자기링크/미열람 거부와 전송 실패 후 런 계속;
  expand의 예산·타임아웃·`KnowledgeSearchFailed`·빈 결과.
- `test_qa_agents_context.py`(확장) — `fold_stale_knowledge`의 순수성·멱등성·최신 1개 유지·
  **히트 본문 불변**·지식 아닌 `ToolMessage` 불변.
- `test_qa_arch.py`(확장) — 새 spec/resolved 필드, `links_need_searches`, `tool_call_limit`에
  새 허용량 반영, 핑거프린트 이동.
- `test_qa_service_deliver.py`(확장) — `KNOWLEDGE_EXPAND_RESULT` 디스패치가 채널에 닿는다.
- `test_qa_prompt_version.py` / `test_prompts_loader.py`(확장) — v9가 목록에 있고 로드되며
  `{vision_directive}`·`{language_directive}`가 치환된다; **v9가 기본으로 뽑힌다**(`versions[-1]`);
  v8은 명시 지정으로 여전히 로드된다(비교 축이 살아 있어야 한다).

```bash
cd /home/yunseong/dev/artel/artel-agent-server && python -m pytest
```

### End to end

지식이 있는 프로젝트로 실제 QA 런을 돌려 에이전트가 항목 둘을 링크하게 하고, 이후 검색이
이웃 줄을 내는지와 `knowledge_edge` · `knowledge_usage` 행이 생겼는지 확인한다.
스코프 런(`knowledge_scope_id != NULL`)으로 한 번 더 돌려 운영 지식창고에 edge가 안 생기는 것을 본다.

## Risks & Rollback

**롤아웃 순서: Orchestration(A→B)이 Agent(C→D)보다 **먼저** 머지된다.** 실패 모드가 비대칭이다.

- *Agent 먼저(거부)*: `KNOWLEDGE_LINK`가 `SUPPORTED_TYPES`에 없는 Orchestration에 닿으면
  `appendError("Unsupported Agent message type")`이고 응답이 없다. link은 단방향이라
  **에이전트는 영영 모른다** — 성공했다고 보고하고 예산 한 칸을 태우고 아무것도 안 쓰였다.
  성공처럼 보이는 조용한 무동작이 여기서 가능한 최악의 열화다.
- *Orchestration 먼저(채택)*: `neighbors`가 모든 히트에 실리지만 Agent의 pydantic이 `extra="allow"`라
  받아서 무시한다. 렌더되는 것도 깨지는 것도 없다.

D는 C보다 얼마든지 늦어도 된다 — 없으면 이웃 블록이 안 접힐 뿐이다.

**B가 모든 기존 검색의 응답을 Agent가 알기 전에 바꾼다.** `knowledge_edge`가 비어 있으면 비용은
빈 결과를 내는 인덱스 질의 하나라 `expandSearchHits` 기본값을 `true`로 둔다. 늘어난
`knowledge_usage` 쓰기가 문제가 되면 이 프로퍼티가 킬 스위치다.

**롤백**: A는 `DROP TABLE knowledge_edge` + `replaces_id` 복구 마이그레이션(단, 그 컬럼은 비어
있었으므로 데이터 손실은 없다). B~D는 `expandSearchHits=false`와 도구 제거로 되돌아간다.

### 확신 없는 것들 — 지어내지 않고 남긴다

1. **`similarMaxDistance = 0.40`은 추측이다.** 이 코퍼스의 거리 분포가 없다. 실제 프로젝트 하나의
   쌍별 거리를 뽑아 무릎을 찾은 뒤에 믿을 것. 상수는 테스트 단언에서 빼 둔다.
2. **`MAX_LINKS_PER_RUN = 3` / `MAX_EXPANDS_PER_RUN = 3`**은 기존 사다리(캡처 12 > 검색 6 > 기록 5 >
   삭제 2)에서 추론한 것이지 측정한 것이 아니다. `QaArchSpec` 필드로 두는 이유가 바로 이것이다.
3. **에이전트가 relation 넷을 잘 쓸지는 실제 런 전에는 모른다.** `REFINES`가 전부 삼키면
   `LINK_KNOWLEDGE_DESCRIPTION`을 벼리는 것이 답이지 값을 더하는 것이 아니다.
4. **`REPLACES`는 실제로는 안 채워질 공산이 크다.** delete-then-record 뒤에 링크를 붙여야 하는데
   `update_knowledge`(ARTEL-257)가 그 경로 자체를 말리고 있다. "수리 vs 폐기"는 지금보다 나아지지
   않는다 — 다만 나빠지지도 않고, 담을 그릇이 생긴다. 정직한 해법은 `record_knowledge`에 선택적
   `replaces` 인자를 붙이는 것이고 별도 이슈다.
5. **edge에는 "수정 그림자"가 없고 툼스톤만 있다.** `knowledge`는 그림자가 수정과 삭제 둘 다를
   지지만 edge는 삭제만이다 — 고칠 것이 `note`뿐이고 그것은 unlink 후 re-link로 되기 때문이다.
   대가는 스코프 런이 baseline edge의 `note`만 고치려 해도 링크 예산 하나와 unlink 예산 하나를
   쓴다는 것. 실제로 `note`만 고치는 일이 잦으면 `ck_knowledge_edge_tombstone`을 풀고 살아 있는
   그림자를 허용하는 것이 그때의 변경이고, CHECK 하나를 지우는 일이라 값이 싸다.
6. **정규 id 접기가 이 작업의 진짜 복잡도다.** 그림자·툼스톤·스코프 edge가 곱해지는 자리라
   traversal 테스트의 스코프 케이스 다섯 개가 형식적 항목이 아니다.
7. **화면 지도가 부피 가정을 바꾼다.** fanout·노드 예산은 의미 관계만 있다고 보고 잡은 수다.
   허브 화면 하나에 `LEADS_TO`가 예닐곱 개 달리는 것이 정상이고, fanout 2에서는 그중 둘만 나온다.
   더 나쁜 것은 **`LEADS_TO`가 우선순위 1이라 같은 노드의 `REFINES`를 밀어낼 수 있다**는 것이다 —
   화면 항목에 달린 규칙 예외가 경로 둘에 가려 안 보이는 상황. 우선순위 자체가 추측이므로
   실제 그래프가 생긴 뒤 재검토한다. 손이 필요하면 관계 종류별 쿼터가 다음 수단이지만,
   지금 넣는 것은 있지도 않은 문제에 대한 복잡도다.
8. **`record_knowledge` 설명이 화면 항목을 초대하지 않는다.** 지금 문구는 "시나리오가 말하지 않은
   RULE"을 강조하고 이번 런의 상태를 배제한다. 화면 항목은 "내일 새 세이브에서도 참인가" 시험을
   통과하므로 자격은 있지만, 문구가 부르지 않으면 에이전트는 안 쓴다. C에서 화면 항목을 명시적으로
   초대하는 문단을 더하되 **"지금 골드가 340"류를 배제하는 문장은 그대로 둔다** — 화면 항목과
   런 상태를 가르는 선이 정확히 거기다.
9. **v9가 기본 프롬프트를 조용히 갈아치운다**(위 D 참조). 배포 전에 `qa_prompt_version` 고정 여부를
   환경별로 확인한다. 이 레포에서 프롬프트 버전은 V25의 비교 축이기도 하므로, v8↔v9 점수를 견주려면
   양쪽을 명시적으로 고정해 돌려야 한다 — 기본값에 맡기면 전부 v9가 된다.

## Open Questions

- Jira 이슈 4개(A/B/C/D)를 지금 발급할지, A만 먼저 끊고 나머지는 A 리뷰 뒤에 낼지.
- `expandSearchHits`를 운영에서 처음부터 켤지, 한 스프린트는 `false`로 두고 `expand_knowledge`만
  노출해 실제 사용량을 본 뒤 켤지.
- 화면 지도가 `MAX_LINKS_PER_RUN = 3`으로 충분히 자라는지. 런당 링크 3개면 화면 지도가 의미 있는
  크기가 되기까지 수십 런이 걸린다. 링크 예산을 화면 지도용으로 따로 둘지(예: `LEADS_TO`만
  별도 허용량), 아니면 느린 축적을 받아들일지 — 후자가 기본이고, 실제 런에서 링크가 예산에
  막히는지부터 본다.
- 화면 항목을 `UI` 태그에 얹는 것으로 충분한지, 아니면 화면임을 나타내는 별도 축이 필요한지.
  V15 주석은 "한 축이 모자라면 enum 값을 늘리지 말고 직교 facet 컬럼을 더하라"고 못박아 뒀다 —
  화면과 그냥 UI 규칙을 갈라야 한다면 그것이 그 facet의 첫 소비처가 된다.
