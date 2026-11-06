--------------------------------------------------------------------------------
-- V72. v_content_map_capability 에서 status 필터를 뺀다
--------------------------------------------------------------------------------
-- QA agent 가 런 시작에 받는 씬별 목록(`/internal/scene-context`)은 이 뷰를 읽는다. 뷰가
-- `status <> 'not-a-step'` 을 들고 있어 실측 472 행 중 54 행만 나간다. 잘리는 418 행이 전부
-- `interaction = 'none'` — "적을 처치하면 보상을 받는다" 처럼 누르는 것이 아니라 일어나는 일이다.
--
-- 그 418 행이 문제의 핵심이다. action 전후의 `pulse` 를 비교하는 기계 검증은 누를 수 없는 행에
-- 대해 아무 말도 할 수 없어 ARTEL-450 이 백로그로 내려갔고, 대신 화면을 본 agent 가 적기로 했다
-- (V71). 그런데 agent 는 이 418 행을 애초에 받지 못한다. 적을 대상을 모르니 키를 지목할 수 없다.
--
-- ## 왜 뷰를 넓히고 소비자를 가르나
--
-- 이 뷰를 읽는 곳이 둘이다. agent 는 전부를 원하고, TC 생성기(`ContentMapViewService.stepsByScene`)
-- 는 지금 받는 것을 그대로 받아야 한다 — 누를 수 없는 것으로는 실행 가능한 테스트 케이스를
-- 만들 수 없다.
--
-- 뷰를 하나 더 만드는 대신 이 뷰를 넓히고 필터를 질의로 내린다. 뷰가 컬럼 30 개를 들고 있어
-- 둘로 나누면 다음에 컬럼이 하나 늘 때 고칠 자리가 둘이 되고, 실제로 그 충돌이 이 뷰에서
-- 방금 일어났다 — ARTEL-644 와 ARTEL-460 이 각자 이 뷰를 통째로 다시 냈다.
--
-- 대신 두 소비자가 서로 다른 것을 원한다는 사실이 `ContentMapRepository` 에 이름으로 남는다.
--   - `findStepCapabilityRows` — `AND status <> 'not-a-step'`. TC 생성기가 읽는 것
--   - `findAllCapabilityRows`  — 필터 없음. agent 가 읽는 것
--
-- `merged_into IS NULL` 은 뷰에 남긴다. 접힌 행은 다른 행으로 대체된 중복이라 아무 소비자도
-- 원하지 않는다 — 소비자마다 답이 갈리는 필터가 아니다.
--
-- `status` 판정 규칙은 건드리지 않는다. `runnable` · `needs-probe` · `not-a-step` 은 V45 가 가른
-- 실행 가능성 축이고 그 구분 자체는 맞다. 잘못된 것은 그 축으로 agent 의 시야를 자른 것이다.
--
-- ## 무엇을 베낀 정의인가
--
-- V71(ARTEL-644)의 정의를 그대로 옮기고 마지막 `AND c.status <> 'not-a-step'` 한 줄만 뺐다.
-- V71 은 develop 에 이미 있고 그 시점에 V70(ARTEL-460)의 `scene_presence` 를 함께 들고 있으므로,
-- 이 파일이 두 브랜치의 컬럼을 모두 갖는다. 따로 합칠 것이 남아 있지 않다.
--
-- `v_spec_gap` 은 이 뷰에 의존하지 않으므로 건드리지 않는다.
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
    ce.gaps
FROM capability c
JOIN scene s ON s.id = c.scene_id
JOIN content_map cm ON cm.id = s.content_map_id
LEFT JOIN capability_evidence ce ON ce.capability_id = c.id
WHERE c.merged_into IS NULL;
