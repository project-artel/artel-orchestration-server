--------------------------------------------------------------------------------
-- V80. v_content_map_capability 가 `무엇이 불렀나` 를 함께 낸다
--------------------------------------------------------------------------------
-- TC 생성기가 이 뷰만 보게 하려는 것이다. 지금은 창구가 셋이다:
--
--   findStepCapabilityRows   이 뷰. `status <> 'not-a-step'`
--   findObservationRows      뷰를 안 거치고 capability 를 직접 조인한다
--   findScreenElements       scene_object_ref 를 직접 조인한다
--
-- 둘째가 뷰를 우회한 이유는 하나뿐이다 — 관측 문구를 고르려면 `call_path` 뿌리가 필요한데
-- 뷰가 그것을 안 냈다. `Start` 면 "화면을 열면", `Update` 면 "머무르는 동안" 이다.
-- 그 한 칸 때문에 창구가 갈렸고, 갈린 창구마다 다른 잣대가 붙었다.
--
-- V72 가 정한 방식을 그대로 따른다 — 뷰는 넓히고 필터는 질의로 내린다. 뷰를 하나 더 만들면
-- 컬럼이 늘 때마다 고칠 자리가 둘이 된다.
--
-- `call_path` 원본도 함께 낸다. 뿌리는 첫 마디뿐이라, 그 위를 봐야 할 때 다시 뷰를 우회하게 된다.
--
-- 정의는 V72 의 것을 그대로 옮기고 마지막에 두 칸을 더했다. 필터도 그대로다.
DROP VIEW IF EXISTS v_content_map_capability;

-- 효과는 여기 접지 않는다. 행이 여러 개라 조인하면 곱해진다.
CREATE VIEW v_content_map_capability AS
SELECT
    cm.id AS content_map_id,
    -- `scene` 의 것이다. 한 지도 안에서 `scene` 마다 다를 수 있고, observation 이 만든 `scene`
    -- 에서는 NULL 이다.
    s.capture,
    s.id AS scene_id,
    s.name AS scene_name,
    s.summary AS scene_summary,
    c.id AS capability_id,
    -- 재적재를 넘어 살아남는 참조 키. c.id 는 표시·조인용이고 이쪽이 기억해 둘 값이다.
    c.capability_key,
    c.origin,
    c.verification,
    -- 그 verification 을 만든 문장. agent 가 무엇을 보고 그렇게 말했는지가 이 id 끝에 있다.
    c.verification_observation_id,
    -- 이 행이 왜 이 scene에 있나. V70에서 더해진 존재 축이다.
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
    -- entry_id 만 내면 `evidence` 주소가 메서드까지다. `branch` 를 짚으려면 둘이 함께 나가야 한다.
    ce.branch_offset,
    ce.record_kind,
    ce.trigger_kind,
    ce.analysis_confidence,
    ce.condition_tree,
    ce.gaps,
    -- 무엇이 이 코드를 불렀나. `call_path` 첫 마디의 메서드 이름이고, `Start`·`Update` 처럼
    -- **Unity 가 정한 이름**이라 개발자가 무엇을 어떻게 짓든 흔들리지 않는다.
    substring(ce.call_path->>0 from '::([A-Za-z0-9_]+)') AS trigger_root,
    ce.call_path
FROM capability c
JOIN scene s ON s.id = c.scene_id
JOIN content_map cm ON cm.id = s.content_map_id
LEFT JOIN capability_evidence ce ON ce.capability_id = c.id
WHERE c.merged_into IS NULL;
