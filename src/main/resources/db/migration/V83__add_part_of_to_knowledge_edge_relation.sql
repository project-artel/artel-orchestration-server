-- knowledge_edge.relation 에 PART_OF 를 더한다. (2026-09-02, ARTEL-748)
--
-- 문서 추출은 지금까지 knowledge 항목만 남기고 edge 를 하나도 만들지 않았다. 기획서 하나에서 나온
-- 수십 개 항목이 그래프에서 서로 무관한 점으로 흩어져, 사람이 그래프를 봐도 "이것들이 같은
-- 문서에서 나왔다"를 읽을 수 없었다. 문서 자신을 node 로 두고 항목마다 그 node 를 향한 PART_OF
-- edge 를 걸어 그 사실을 그래프에 싣는다.
--
-- **V29 의 시험을 다시 통과시킨다.** 그 파일은 관계 값을 "읽는 쪽이 그것 때문에 다르게 행동하는가"
-- 로만 늘린다고 정했다. PART_OF 는 통과한다 — 그래프 화면이 문서 node 와 그 아래 항목을 다른
-- 모양으로 그리고(ARTEL-751), 그래프 탐색 표기가 이 이름을 따로 읽는다(ARTEL-749). 다섯 값 중
-- 어느 것으로도 대신할 수 없다: REFINES 는 "더 좁은 경우"라는 의미 관계이고, 이것은 의미가 아니라
-- **어느 문서에서 왔는가**라는 출처다.
--
-- ⚠️ **V29 의 KnowledgeRelation KDoc 이 PART_OF 를 이미 한 번 거부했다.** 그 문맥은 QA agent 가
-- KNOWLEDGE_LINK 로 손수 주장하는 관계 어휘였고, 거부 사유는 "REFINES 와 거의 겹친다"였다. 그
-- 판단은 그대로 살아 있다 — 이 값은 지금도 그 어휘에 들어가지 않고, `KnowledgeRelation` enum 에도
-- 없어 `fromWire` 파싱을 통과하지 못한다. 여기서 CHECK 를 넓히는 것은 **적재 파이프라인만 쓰는
-- 구조적 관계**를 위해서이고, 그 값을 만드는 코드는 `KnowledgeService.store` 하나뿐이다.
--
-- 방향은 **항목 → 문서**다. 반대로 두면 문서 node 하나가 수십 개의 나가는 edge 를 갖게 되어
-- `idx_knowledge_edge_from` 의 fanout 이 문서 크기만큼 커지고, "이 항목은 어디서 왔나"라는 더 잦은
-- 질문이 역방향 조회가 된다.
--
-- ⚠️ V29 는 이미 적용된 마이그레이션이라 수정 금지(체크섬). 인라인 컬럼 CHECK 는 Postgres 가
-- <table>_<column>_check 규칙으로 자동 명명하므로(knowledge_edge_relation_check), V15/V57 과 같이
-- 제약만 교체하는 새 버전으로 넓힌다. 기존 다섯 값은 그대로 두므로 이미 저장된 행은 전부 통과한다.

ALTER TABLE knowledge_edge DROP CONSTRAINT IF EXISTS knowledge_edge_relation_check;

ALTER TABLE knowledge_edge ADD CONSTRAINT knowledge_edge_relation_check CHECK (
    relation IN (
        'LEADS_TO',
        'REFINES',
        'CONTRADICTS',
        'DEPENDS_ON',
        'REPLACES',
        'PART_OF'
    )
);

COMMENT ON COLUMN knowledge_edge.relation IS
    'knowledge 항목 사이의 관계. PART_OF 만은 런이 주장한 것이 아니라 문서 적재가 만드는 구조적 관계이고, 방향은 항목에서 문서 node 로 향한다.';
