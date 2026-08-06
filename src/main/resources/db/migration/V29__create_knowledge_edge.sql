-- knowledge 항목 사이의 관계를 타입과 이유와 함께 저장한다. (2026-08-06, ARTEL-274)
--
-- 지금 knowledge는 평평한 자루다. 항목마다 tag 하나를 달고, 도달 경로는 항목당 3개의 LLM 생성
-- 질문에 대한 벡터 유사도뿐이다(V18). 두 항목이 같은 메커니즘을 말한다는 것도, 하나가 다른
-- 하나의 예외라는 것도, 서로 모순이라는 것도 어디에도 없다. 일반 규칙을 검색한 QA 런은 한 홉
-- 옆에 더 구체적인 규칙이 있다는 것을 영영 모르고, 운영자는 지식창고가 내부적으로 모순이 됐다는
-- 것을 볼 방법이 없다.
--
-- **벡터 이웃은 저장하지 않는다.** "이것과 비슷한 것"은 조회 시점에 pgvector로 계산한다. 임베딩
-- 모델이 바뀌면 통째로 옳지 않게 되는 파생값이라 테이블에 넣으면 갱신 책임이 생기고, K-NN을
-- 물화하면 부피가 N×K로 폭증한다. 여기 담기는 것은 **런이 이유를 적어 주장한 관계**뿐이다.
--
-- ⚠️ V13/V15/V18/V19/V27/V28은 이미 적용된 마이그레이션이라 수정 금지(체크섬).

--------------------------------------------------------------------------------
-- 1. knowledge_edge
--------------------------------------------------------------------------------
-- **하드 FK를 걸지 않는다.** V28이 shadows_id(knowledge → knowledge)를 논리참조로 두면서 그 이유를
-- 이미 적어 뒀다 — knowledge는 프로젝트·문서·런·다른 knowledge까지 전부 논리참조로 들고 있고,
-- 여기에만 하드 FK를 걸면 baseline 정리가 스코프 행에 막힌다. edge는 shadows_id와 정확히 같은
-- 모양의 참조이므로 그 관례를 따른다. (knowledge가 소프트삭제라 ON DELETE CASCADE는 어차피
-- 거의 발화하지 않는다 — 끊어진 끝점을 거르는 것은 traversal의 deleted_at 필터다.)
--
-- **scope_id는 knowledge와 같은 뜻이다**(V28). NULL이 운영 공용(baseline)이고, 값이 있으면 그
-- 스코프의 런에만 보인다. 실험 arm이 주장한 관계가 운영 그래프에 섞이면 되돌릴 방법이 없다.
--
-- **끝점은 정규 id(canonical id)로 저장한다.** 스코프 런이 baseline B1을 고치면 그림자 S1이 생기고
-- (V28) 그 스코프의 검색은 S1의 id를 낸다. 에이전트가 쥔 id가 그림자 id일 수 있다는 뜻이다.
-- 그대로 저장하면 baseline 그래프와 스코프 그래프가 id 공간에서 갈라져, 실험이 끝난 뒤 그 edge가
-- 무엇을 가리켰는지 아무도 못 읽는다. 서비스가 COALESCE(shadows_id, id)로 접어 저장한다.
CREATE TABLE IF NOT EXISTS knowledge_edge (
    id                   BIGSERIAL PRIMARY KEY,
    -- 읽기가 항상 프로젝트로 먼저 자르므로 비정규화해 둔다. 쓰기 버그로 생긴 프로젝트 교차
    -- edge가 traversal 질의에서 knowledge를 두 번 조인하지 않고도 걸러진다.
    project_id           BIGINT NOT NULL,
    scope_id             BIGINT,
    from_knowledge_id    BIGINT NOT NULL,
    to_knowledge_id      BIGINT NOT NULL,
    relation             VARCHAR(20) NOT NULL
                         CHECK (relation IN ('LEADS_TO', 'REFINES', 'CONTRADICTS', 'DEPENDS_ON', 'REPLACES')),
    -- 이 edge가 왜 있는지, 주장한 런의 말로. LEADS_TO만은 "왜"가 아니라 **무엇을 했는지**를 진다
    -- ("마을 상단바의 상점 버튼") — 경로를 나중 런이 쓸 수 있게 만드는 것이 그 문장이기 때문이다.
    --
    -- NOT NULL인 이유는 감사할 수 없는 edge는 아무도 확신을 갖고 지울 수 없기 때문이다. 도구가
    -- 빈 문자열을 먼저 거부하지만, 그 검사가 빠져도 여기서 막힌다.
    note                 TEXT NOT NULL,
    -- 이 스코프 행이 가리는 baseline edge. knowledge.shadows_id와 같은 장치다.
    --
    -- 다만 edge에는 **수정 그림자가 없고 툼스톤만 있다.** edge에서 고칠 수 있는 것은 note뿐이고
    -- 그것은 unlink 후 re-link로 되므로(끝점과 relation은 정체성이라 바꾸면 다른 edge다),
    -- knowledge가 update_knowledge를 따로 둔 이유(본문이 길어 재입력이 비싸다)가 여기엔 없다.
    -- 그래서 이 컬럼이 있는 행은 항상 툼스톤이고, 아래 CHECK가 그 불변식을 못박는다.
    shadows_edge_id      BIGINT,
    created_by_qa_try_id BIGINT,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- knowledge와 같은 소프트삭제. unlink가 이 컬럼을 쓴다.
    deleted_at           TIMESTAMP WITH TIME ZONE,
    -- V19가 knowledge에 남긴 것과 같은 감사용 컬럼. 되살려도 지우지 않는다 —
    -- "직전에 누가 지웠었나"가 곧 감사 기록이다.
    deleted_by_qa_try_id BIGINT,

    CONSTRAINT ck_knowledge_edge_no_self CHECK (from_knowledge_id <> to_knowledge_id),
    -- CONTRADICTS는 대칭이고 **한 행**으로 저장한다. 두 행으로 두면 쓰기가 두 배가 되고 unlink가
    -- 반쯤 실패할 수 있는 2행 연산이 되며, 무엇보다 아래 유니크 인덱스가 (A,B)와 (B,A)를 같은
    -- 주장으로 못 본다. 서비스가 from = min, to = max로 정규화하고 이 CHECK가 서비스 버그를 막는다.
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

COMMENT ON TABLE knowledge_edge IS
    'knowledge 항목 사이의 관계. 런이 이유(note)를 적어 명시한 것만 담고, 벡터 유사도 이웃은 저장하지 않는다.';
COMMENT ON COLUMN knowledge_edge.scope_id IS
    '이 edge가 속한 지식 스코프. NULL이면 운영 공용(baseline)이고, 값이 있으면 그 스코프의 런에만 보인다.';
COMMENT ON COLUMN knowledge_edge.shadows_edge_id IS
    '이 스코프 행이 가리는 baseline knowledge_edge.id. edge에는 수정 그림자가 없어 이 값이 있으면 항상 툼스톤이다.';
COMMENT ON COLUMN knowledge_edge.note IS
    '이 관계를 주장한 이유. LEADS_TO는 예외적으로 "무엇을 해서 그 경로를 지났는가"를 담는다.';

--------------------------------------------------------------------------------
-- 2. 인덱스
--------------------------------------------------------------------------------
-- **scope_id가 키에 들어가는 것이 핵심이다.** 스코프는 baseline과 같은 관계를 (예컨대 다른 note로)
-- 자기 스코프에 나란히 둘 수 있어야 한다 — 그것이 스코프가 baseline을 덮는 방법 중 하나다.
-- baseline의 유일성과 스코프의 유일성은 각각 지켜지고 서로를 막지 않는다.
--
-- 부분(deleted_at IS NULL)인 이유는 unlink한 관계를 다시 주장할 수 있어야 하기 때문이다.
--
-- 목적은 성능이 아니라 **재시도나 동시 쓰기가 같은 주장을 두 번 파일하는 것을 막는 것**이다.
-- 서비스도 먼저 검사하지만 인스턴스 두 대가 같은 프레임을 동시에 처리하면 그 검사는 경합에 진다.
-- 위반은 저장 트랜잭션을 되돌리고 KnowledgeLinkResult.Rejected가 된다 — WS 수신 체인의 예외가 아니다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_edge_live
    ON knowledge_edge (scope_id, from_knowledge_id, to_knowledge_id, relation)
 WHERE deleted_at IS NULL;

-- 양방향 둘 다 만든다. traversal은 사실상 무향이다 — 일반 규칙에 걸렸을 때 그것을 REFINES 하는
-- 구체적 항목이 나와야지 반대 방향만 나와서는 쓸모가 없다. scope_id를 두 번째에 끼우는 것은
-- 읽기 술어가 늘 `끝점 = ? AND (scope_id IS NULL OR scope_id = ?)` 모양이기 때문이다(V28 관례).
CREATE INDEX IF NOT EXISTS idx_knowledge_edge_from
    ON knowledge_edge (from_knowledge_id, scope_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_knowledge_edge_to
    ON knowledge_edge (to_knowledge_id, scope_id) WHERE deleted_at IS NULL;

-- "이 런이 그래프에 무엇을 했나"의 조회 축. knowledge_event의 idx_knowledge_event_try와 같은 자리다.
CREATE INDEX IF NOT EXISTS idx_knowledge_edge_try
    ON knowledge_edge (created_by_qa_try_id);

-- 한 스코프가 같은 baseline edge에 툼스톤을 둘 만들면 읽기의 NOT EXISTS가 두 번 참이 될 뿐이라
-- 결과는 지금 안 틀린다. 그러나 그것은 우연이다. V28의 uq_knowledge_scope_shadow와 같은 자리이고
-- 같은 이유로 UNIQUE다 — 인스턴스 두 대가 같은 프레임을 동시에 처리하면 서비스의 사전 검사가
-- 경합에 지고, 이 유일성이 깨진 채로 굳으면 나중에 "툼스톤 하나당 baseline 하나"를 가정하는
-- 질의(예: 실험 arm별 edge 차이)가 조용히 틀린다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_edge_scope_tombstone
    ON knowledge_edge (scope_id, shadows_edge_id) WHERE shadows_edge_id IS NOT NULL;

--------------------------------------------------------------------------------
-- 3. knowledge.replaces_id 제거
--------------------------------------------------------------------------------
-- V27이 "이 항목이 대체한 항목"의 자리로 만들어 뒀지만 **한 번도 쓰인 적 없다.** 엔티티에 매핑도
-- 되지 않았고(KnowledgeEntity KDoc이 그렇게 적어 뒀다) 쓰는 코드가 없다 — 드롭해도 잃는 데이터가
-- 없다.
--
-- 같은 사실의 집이 둘이면 조용히 어긋난다. KnowledgeSearchProperties에 model 필드를 두지 않은
-- 것과 같은 이유이고, 이번에는 REPLACES가 그 자리를 가져간다 — 컬럼과 달리 쓰기 경로가 있다.
--
-- 후속 작업이 아니라 **이 마이그레이션에서** 지운다. 쪼개면 두 집이 동시에 살아 있는 상태가
-- 생기고, 그 사이에 replaces_id를 채우는 코드가 붙으면 되돌리기가 어려워진다.
--
-- knowledge_entry_facts view는 이 컬럼을 select하지 않으므로 V28이 고정해 둔 컬럼 순서는
-- 건드려지지 않는다.
ALTER TABLE knowledge DROP COLUMN IF EXISTS replaces_id;
