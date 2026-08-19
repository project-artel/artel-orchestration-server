--------------------------------------------------------------------------------
-- V43. 끝날 때까지 반복해야 도달하는 조작을 표시한다
--------------------------------------------------------------------------------
-- V40 이 세운 스키마와 V42 가 얹은 것 위에 다시 얹는다. V40 본문을 직접 고치지 않는 이유는
-- 번호가 한 번 붙으면 그 파일의 checksum 이 계약이 되기 때문이다.
--
-- 담는 것:
--   1. capability.repeat_until_done — 한 번 누르는 것과 끝날 때까지 누르는 것을 가른다
--   2. 반복이 조작 없이는 성립하지 않는다는 CHECK
--   3. v_content_map_capability 에 새 칸 노출

--------------------------------------------------------------------------------
-- 1. repeat_until_done — 반복해야 도달하는 조작
--------------------------------------------------------------------------------
-- 한 번 누르는 것과 끝날 때까지 누르는 것을 가른다. 대사 넘기기·웨이브 클리어처럼 같은 조작을
-- 반복해야 다음 자리에 닿는 구간이 이 계열이다.
--
-- interaction CHECK 를 넓히지 않고 형제 컬럼으로 둔 이유: enum 확장은 이 값을 읽는 모든
-- 소비자를 깨지만, 모르는 컬럼은 아무도 깨지 않는다. 기본값이 false 라 모르는 소비자는 기존과
-- 같이 읽는다 — 덜 정확할 뿐이다.
--
-- 반복을 못 적으면 그 전제가 사전조건으로 밀려난다. "대사를 모두 넘긴 상태"는 사람에게는
-- 지시가 되지만 실행 에이전트가 확인할 수 없는 전제다. 반복은 전제가 아니라 스텝이어야 한다.
--
-- 종료 조건은 여기 적지 않는다. 반복이 끝내 만드는 효과가 곧 종료 조건이고 그것은 이미
-- capability_effect 에 있다.
ALTER TABLE capability
    ADD COLUMN IF NOT EXISTS repeat_until_done BOOLEAN NOT NULL DEFAULT FALSE;

-- 반복은 조작이 있어야 성립한다. interaction='none' 은 타이머·로딩·코루틴이라 누를 것이 없고,
-- 그것을 "끝날 때까지 반복한다"고 적으면 TC 가 지시할 수 없는 것을 지시하게 된다.
ALTER TABLE capability
    DROP CONSTRAINT IF EXISTS ck_capability_repeat_needs_interaction;
ALTER TABLE capability
    ADD CONSTRAINT ck_capability_repeat_needs_interaction
        CHECK (NOT repeat_until_done OR interaction <> 'none');

--------------------------------------------------------------------------------
-- 2. v_content_map_capability — 새 칸을 창구에 낸다
--------------------------------------------------------------------------------
-- CREATE OR REPLACE 가 아니라 DROP 후 CREATE 인 이유: 새 칸이 목록 끝이 아니라 가운데 들어간다.
-- CREATE OR REPLACE VIEW 는 끝에 덧붙이는 것만 허용한다.
DROP VIEW IF EXISTS v_content_map_capability;
-- 효과는 여기 접지 않는다. 행이 여러 개라 조인하면 곱해진다.
CREATE VIEW v_content_map_capability AS
SELECT
    cm.id AS content_map_id,
    cm.capture,
    s.id AS scene_id,
    s.name AS scene_name,
    s.summary AS scene_summary,
    c.id AS capability_id,
    -- 재적재를 넘어 살아남는 참조 키. c.id 는 표시·조인용이고 이쪽이 기억해 둘 값이다.
    c.capability_key,
    c.origin,
    c.verification,
    c.status,
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
    -- entry_id 만 내면 근거 주소가 메서드까지다. 갈래를 짚으려면 둘이 함께 나가야 한다.
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
