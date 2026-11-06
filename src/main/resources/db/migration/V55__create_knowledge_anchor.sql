-- 지식 한 항목을 씬·화면에 묶는다. (2026-08-27, ARTEL-591)
--
-- QA agent 는 그동안 화면 지도를 지식창고 안에 지었다 — 화면 하나당 항목 하나, 경로는 LEADS_TO
-- edge(V29). 그 데이터의 집이 이제 content_map 이라(V40) 지식창고는 지도를 내놓는다.
-- 남는 것은 **다른 어디에도 컬럼이 없는 지식**이고, 그중에 "이 화면에서만 참인 사실"이 있다.
-- 그 사실이 자기 화면을 가리킬 자리가 이 표다.
--
-- 앵커가 없는 지식은 게임 전체의 사실이다. 그것이 기본값이고, 기본값은 싸야 한다 — 그래서
-- knowledge 행 자체는 이 마이그레이션으로 한 바이트도 늘지 않는다.
--
-- 번호: develop 은 V54 까지 있고 다른 브랜치·워크트리가 V36~V54 를 이미 잡았다. 비어 보이는
-- 33 과 47 은 쓰지 않는다 — 더 높은 번호를 이미 적용한 DB 에서 out-of-order 로 걸린다
-- (docs/flyway-migrations.md 의 "Tangle"). 그래서 V55 다.
--
-- ⚠️ V13/V15/V18/V19/V27/V28/V29/V40 은 이미 적용된 마이그레이션이라 수정 금지(체크섬).

--------------------------------------------------------------------------------
-- 1. knowledge_anchor
--------------------------------------------------------------------------------
-- **knowledge 에 컬럼을 붙이지 않고 표를 따로 둔다.** 지식 하나가 여러 화면에 정당하게 걸린다
-- ("전투 중 ESC 는 아무것도 하지 않는다"는 전투 화면 셋에 걸린다). 컬럼이면 첫 화면만 남고
-- 나머지는 소리 없이 사라지는데, 사라진 쪽을 나중에 되찾을 방법이 없다.
--
-- **하드 FK 를 걸지 않는다.** knowledge 는 project(V13)·document(V13)·run(V19)·다른
-- knowledge(V28 shadows_id, V29 edge)까지 전부 논리참조로 든다. 여기만 예외로
-- content_map.screen 에 하드 FK 를 걸면 게임 빌드를 지우는 일이 지식을 지우는 일로 번진다 —
-- 빌드는 갈아 끼우는 것이고 지식은 프로젝트에 남는 것이라, 그 둘의 수명은 애초에 다르다.
-- knowledge_id 쪽도 같은 이유로 논리참조다.
--
-- 끊어진 끝점(지워진 화면·지워진 지식)을 거르는 것은 읽기 질의의 몫이다. knowledge 가
-- 소프트삭제라 어차피 CASCADE 는 거의 발화하지 않는다는 V29 의 사정도 그대로다.
CREATE TABLE IF NOT EXISTS knowledge_anchor (
    id           BIGSERIAL PRIMARY KEY,
    knowledge_id BIGINT NOT NULL,

    -- **NOT NULL 인 쪽.** 화면은 씬 안에 살고, 씬 이름은 게임 상태 프레임이 매번 실어 주므로
    -- 앵커를 달 수 있는 시점이면 언제나 안다. 반대(화면은 아는데 씬은 모른다)는 일어나지 않는다.
    --
    -- **content map 과 대조하지 않는다.** content map 이 아직 없는 프로젝트도 씬 이름은 있다.
    -- 검증을 걸면 그 프로젝트에서 오는 앵커가 전부 거절되고, 그 결과는 "화면 지식을 못 적는
    -- 프로젝트"라 기능이 없는 것과 같아진다. 이름은 게임이 부르는 대로 그대로 담는다.
    scene_name   VARCHAR(255) NOT NULL,

    -- **NULL 허용인 쪽.** 화면(content_map.screen)은 pulse 관측으로 판정되는 것이라(V40) 판정이
    -- 안 되는 순간이 정상적으로 있다. 그때 앵커는 "이 씬의 어딘가"까지만 말하고 멈춘다 —
    -- 화면을 지어내는 것보다 모른다고 하는 편이 낫다.
    --
    -- content_map 이 채워지기 전까지 이 값은 사실상 늘 NULL 이다. 의도된 상태이고, 그래서 아래
    -- 두 유일 인덱스 중 실제로 일하는 쪽은 NULL 쪽이다.
    screen_id    BIGINT,

    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE knowledge_anchor IS
    '지식 한 항목이 어느 씬·화면의 것인지. 행이 없으면 게임 전체의 사실이다.';
COMMENT ON COLUMN knowledge_anchor.knowledge_id IS
    '대상 knowledge.id. 논리참조라 FK 가 없다 — 끊어진 끝점을 거르는 것은 읽기 질의의 몫이다.';
COMMENT ON COLUMN knowledge_anchor.scene_name IS
    '게임이 부르는 씬 이름. content map 과 대조하지 않는다 — content map 이 없는 프로젝트도 씬 이름은 있다.';
COMMENT ON COLUMN knowledge_anchor.screen_id IS
    '판정된 화면(content_map.screen.id). NULL 이면 화면까지는 모르고 씬까지만 아는 앵커다.';

--------------------------------------------------------------------------------
-- 2. 인덱스
--------------------------------------------------------------------------------
-- 검색이 낸 히트마다 앵커를 붙이는 조회. 히트 id 묶음으로 IN 조회가 들어온다.
CREATE INDEX IF NOT EXISTS idx_knowledge_anchor_knowledge
    ON knowledge_anchor (knowledge_id);

-- "이 씬의 지식만" 으로 검색을 좁히는 축. 씬 이름으로 knowledge_id 를 먼저 모으는 EXISTS 가
-- 이 인덱스를 탄다.
CREATE INDEX IF NOT EXISTS idx_knowledge_anchor_scene
    ON knowledge_anchor (scene_name);

-- 같은 지식을 같은 화면에 두 번 걸지 않는다. 중복 앵커는 조용히 틀린다 — 검색 응답에 같은
-- 화면이 두 번 실리고, 화면별 지식을 세는 질의가 같은 사실을 두 번 센다.
--
-- 서비스도 먼저 검사하지만 인스턴스 두 대가 같은 프레임을 동시에 처리하면 그 검사는 경합에
-- 진다. V28 의 uq_knowledge_scope_shadow, V29 의 uq_knowledge_edge_live 와 같은 자리다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_anchor_screen
    ON knowledge_anchor (knowledge_id, scene_name, screen_id) WHERE screen_id IS NOT NULL;

-- 위 인덱스는 screen_id 가 NULL 인 행에 걸리지 않는다 — Postgres 는 UNIQUE 에서 NULL 을 서로
-- 다른 값으로 본다. V40 의 uk_screen_transition_auto 가 같은 이유로 짝을 이룬 인덱스를 둔다.
--
-- 여기서는 그 구멍이 더 크다. content map 이 채워지기 전까지 **모든** 앵커가 NULL 쪽이라,
-- 이 인덱스가 없으면 같은 프레임을 재시도하는 것만으로 씬 앵커가 무한히 쌓인다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_anchor_scene_only
    ON knowledge_anchor (knowledge_id, scene_name) WHERE screen_id IS NULL;

--------------------------------------------------------------------------------
-- 3. 스코프와의 관계 — 여기 담기지 않은 것
--------------------------------------------------------------------------------
-- 이 표에는 scope_id 가 없다. 앵커는 knowledge 행에 매달린 사실이고, 그 행이 이미 스코프를
-- 진다(V28) — 앵커에 스코프를 또 두면 같은 사실의 집이 둘이 되어 조용히 어긋난다. 읽기는
-- knowledge 를 조인해 KnowledgeScopeSql.VISIBLE 를 그대로 지나므로, 가려진 지식의 앵커는
-- 애초에 조회되지 않는다.
--
-- ⚠️ 남는 구멍 하나: 스코프 런이 baseline 을 고치면 그림자는 **새 행**이라(V28) baseline 의
-- 앵커를 물려받지 않는다. 그 스코프에서 그 지식은 앵커가 없는 것으로 보인다. 지금은 실험
-- 엔티티가 없어 발화하지 않고, 앵커를 나중에 붙이는 API 도 아직 없다(ARTEL-591 non-goal).
-- 실험이 실제로 돌기 시작하면 여기가 첫 번째 확인 대상이다 — 그때 정규 id(V29 의 edge 가
-- COALESCE(shadows_id, id) 로 접는 방식)로 갈지, 그림자를 만들 때 앵커를 복사할지 정한다.
