--------------------------------------------------------------------------------
-- V66. 이 capability 행이 왜 이 scene 에 있나
--------------------------------------------------------------------------------
-- `DontDestroyOnLoad` 는 scene load 를 넘어 살아남으라는 뜻이다. 만들어진 뒤로 그 오브젝트는
-- 모든 scene 에 실제로 존재한다. 어디에 있나는 추론할 것이 아니라 이미 답이 나와 있다.
--
-- 그래서 적재기가 그런 오브젝트의 capability 를 real scene 전부에 앉힌다(ARTEL-460). 실측 문서에서
-- capability 64 개가 scene 7 개로 펼쳐져 441 행이 되고(448 중 7 행은 Map_scene 의 real 배치와 같은
-- 키를 내 접힌다), 지도 전체가 469 에서 846 으로 는다.
--
-- 그 부피는 공짜가 아니다. `TurnBattleScene` 을 읽는 agent 의 목록에 거기서 절대 안 먹는 tutorial
-- capability 57 개가 딸려 온다. 그 행들이 근거가 실제로 그 scene 을 지목한 행과 똑같이 보이면 이
-- 변경은 지도를 나쁘게 만든 것이다. 이 마이그레이션이 그 둘을 가르는 칸을 만든다.
--
-- 담는 것:
--   1. capability.scene_presence — 이 행이 왜 이 scene 에 있나
--   2. v_content_map_capability 를 다시 낸다

--------------------------------------------------------------------------------
-- 1. capability.scene_presence
--------------------------------------------------------------------------------
-- `verification` 으로 대신하지 않는다. 저쪽은 "실행해 봤나"이고 이쪽은 "근거가 이 scene 을
-- 말했나"라, 한 칸에 담으면 아직 아무도 안 눌러 본 evidence 행과 살아남아 여기 있을 뿐인 행이
-- 같은 'unverified' 한 값에 들어가 영영 갈라지지 않는다. `analysis_confidence` 로도 대신하지
-- 않는다 — 그 행이 그 scene 에 있다는 것 자체는 유도가 아니라 사실이고, 흐린 것은 여기서 그
-- 기능이 의미가 있나다.
--
-- 세 값이 답하는 것:
--
--   placed                   문서가 이 오브젝트를 이 scene 에 놓았다. 기존 행 전부가 이 값이다
--   persistent-evidenced     scene 을 넘어 살아남았고, 근거가 이 scene 을 지목했다.
--                            그 지목의 사슬은 capability_proof 에 있다
--   persistent-unconfirmed   scene 을 넘어 살아남아 여기 있다. 여기서 되는지는 아무도 모른다
--
-- 기본값이 'placed' 인 것은 기존 행의 사실과 맞다. observed · inferred · human 출신도 'placed'
-- 다 — 그 행들은 agent 나 사람이 그 scene 을 보고 적은 것이라, 그 scene 에 있다는 말이 곧 근거다.
--
-- 'persistent-unconfirmed' 행을 지우는 것은 QA agent 다(ARTEL-644). 이 칸은 그 쓰기가 무엇을 보고
-- 무엇을 지울지 고를 수 있게 하는 재료이기도 하다.
ALTER TABLE capability
    ADD COLUMN IF NOT EXISTS scene_presence VARCHAR(24) NOT NULL DEFAULT 'placed';

ALTER TABLE capability
    DROP CONSTRAINT IF EXISTS ck_capability_scene_presence;
ALTER TABLE capability
    ADD CONSTRAINT ck_capability_scene_presence
        CHECK (scene_presence IN ('placed', 'persistent-evidenced', 'persistent-unconfirmed'));

-- 한 scene 의 목록에서 확인 안 된 행만 골라 내는 것이 이 칸의 주된 쓰임이라(agent 가 지울 대상,
-- 화면이 흐리게 그릴 대상), scene 과 묶어 건다. 'placed' 가 절대다수이므로 부분 인덱스로 둔다 —
-- 전체 인덱스는 크기만 크고 고르는 일을 돕지 못한다.
CREATE INDEX IF NOT EXISTS idx_capability_scene_presence
    ON capability (scene_id, scene_presence)
    WHERE scene_presence <> 'placed';

--------------------------------------------------------------------------------
-- 2. v_content_map_capability 를 다시 낸다
--------------------------------------------------------------------------------
-- 창구를 하나로 못 박은 것이 이 뷰의 존재 이유다. TC 생성기와 agent 의 scene 맥락이 둘 다 이
-- 뷰만 읽으므로, 새 칸이 여기 없으면 저장은 갈라 두고 읽는 쪽은 못 가르는 상태가 된다.
--
-- `CREATE OR REPLACE VIEW` 로 끝에 붙일 수도 있지만, 새 칸이 `verification` 바로 옆에 있어야
-- 읽는 사람이 둘을 한 쌍으로 본다. V43 · V45 · V63 이 같은 이유로 통째로 다시 냈다.
--
-- `v_spec_gap` 은 이 뷰에 의존하지 않으므로 건드리지 않는다.
DROP VIEW IF EXISTS v_content_map_capability;

-- 효과는 여기 접지 않는다. 행이 여러 개라 조인하면 곱해진다.
CREATE VIEW v_content_map_capability AS
SELECT
    cm.id AS content_map_id,
    -- scene 의 것이다. 한 지도 안에서 scene 마다 다를 수 있고, observation 이 만든 scene 에서는 NULL 이다.
    s.capture,
    s.id AS scene_id,
    s.name AS scene_name,
    s.summary AS scene_summary,
    c.id AS capability_id,
    -- 재적재를 넘어 살아남는 참조 키. c.id 는 표시·조인용이고 이쪽이 기억해 둘 값이다.
    c.capability_key,
    c.origin,
    c.verification,
    -- 이 행이 왜 이 scene 에 있나. verification 과 다른 축이다 — 저쪽은 실행 확인이고 이쪽은
    -- 근거가 이 scene 을 말했나다.
    c.scene_presence,
    c.status,
    -- status 를 낳은 세 축. status 만 보면 "관측이 안 됨"과 "적용이 안 됨"이 같은 칸에 보인다.
    c.actionability,
    c.observability,
    c.applicability,
    c.summary,
    c.given_text,
    c.control_selector,
    c.control_path,
    c.control_label,
    c.interaction,
    c.input_key,
    c.input_phase,
    -- 한 번인지 끝날 때까지인지. 모르는 소비자는 이 칸을 안 읽고 기존과 같이 동작한다.
    c.repeat_until_done,
    c.hint_action_method,
    c.hint_action_params,
    ce.entry_id,
    -- entry_id 만 내면 evidence 주소가 메서드까지다. branch 를 짚으려면 둘이 함께 나가야 한다.
    ce.branch_offset,
    ce.record_kind,
    ce.trigger_kind,
    ce.analysis_confidence,
    ce.condition_tree,
    ce.gaps
FROM capability c
JOIN scene s ON s.id = c.scene_id
JOIN content_map cm ON cm.id = s.content_map_id
LEFT JOIN capability_evidence ce ON ce.capability_id = c.id
WHERE c.merged_into IS NULL
  AND c.status <> 'not-a-step';
