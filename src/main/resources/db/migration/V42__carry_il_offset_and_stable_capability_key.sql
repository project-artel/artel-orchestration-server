--------------------------------------------------------------------------------
-- V42. 근거 위치(offset)를 실어 한 메서드 안의 갈래를 가른다
--------------------------------------------------------------------------------
-- V40 이 세운 content_map 스키마 위에 얹는다. V40 본문을 직접 고치지 않는 이유는 번호가 한 번
-- 붙으면 그 파일의 checksum 이 계약이 되기 때문이다 — V40 을 이미 적용한 데이터베이스가
-- 하나라도 있으면 본문 수정은 validate 실패로 돌아온다.
--
-- 담는 것:
--   1. capability.capability_key — 재적재를 넘어 살아남는 참조 키와 그 유일성 범위
--   2. capability_evidence.branch_offset · capability_effect.il_offset — IL 위치
--   3. 되짚기 두 축이 조용히 비는 것을 막는 CHECK
--   4. v_content_map_capability 에 새 두 칸 노출

--------------------------------------------------------------------------------
-- 1. capability_key 의 유일성 범위 — content_map 단위
--------------------------------------------------------------------------------
-- 키의 유일성 범위가 content_map 단위라, 그 제약을 DB 가 걸려면 content_map_id 가 capability 와
-- 같은 테이블에 있어야 한다. scene 을 통해 이미 알 수 있는 값이지만 복합 FK 로 씬의 것과 묶어
-- 두 벌이 갈라지지 않게 한다.

-- capability 가 복합 FK 로 참조할 대상. 기능이 든 content_map_id 가 그 기능이 붙은 씬의
-- content_map_id 와 어긋날 수 없게 하려는 것이고, 그것이 없으면 안정 키의 유일성 범위가
-- 조용히 다른 맵으로 새어 나간다.
ALTER TABLE scene
    DROP CONSTRAINT IF EXISTS uk_scene_id_map;
ALTER TABLE scene
    ADD CONSTRAINT uk_scene_id_map UNIQUE (id, content_map_id);

-- NOT NULL 을 한 번에 걸지 않는다. 이미 행이 있는 데이터베이스에서는 기본값 없는 NOT NULL 이
-- 거절되므로, 넣고 · 씬에서 채우고 · 조인 뒤 조인다.
ALTER TABLE capability
    ADD COLUMN IF NOT EXISTS content_map_id BIGINT;
UPDATE capability c
   SET content_map_id = s.content_map_id
  FROM scene s
 WHERE s.id = c.scene_id
   AND c.content_map_id IS DISTINCT FROM s.content_map_id;
ALTER TABLE capability
    ALTER COLUMN content_map_id SET NOT NULL;

-- 재적재를 넘어 살아남는 내용 기반 키. (entry_id, branch_offset, 정규화한 condition_tree)
-- 에서 만든다 — 산식은 적재기(ARTEL-442)가 확정한다.
--
-- id 는 BIGSERIAL 이라 적재기가 evidence 기능을 지웠다 넣으면 새로 발급된다. 갈래를 펼치면
-- 번호가 통째로 밀리기까지 한다. scene_edge.capability_id 와 screen_transition.capability_id
-- 는 런타임에 알아낸 지식을 든 참조라, 번호가 밀리면 그 지식이 엉뚱한 기능에 붙는다.
--
-- nullable 인 이유: observed · inferred · human 출신은 entry_id 도 branch_offset 도 없어
-- 산식의 입력이 없다. 없는 것이 정상이고, 더미값을 넣으면 그 순간 키가 키가 아니게 된다.
ALTER TABLE capability
    ADD COLUMN IF NOT EXISTS capability_key VARCHAR(64);

-- 안정 키의 유일성 범위. 씬 단위가 아니라 content_map 단위다 — 같은 타입이 두 씬에 놓이면
-- 갈래가 같아도 서로 다른 기능이고, 그 둘이 같은 키를 들면 참조가 어느 쪽인지 못 가린다.
ALTER TABLE capability
    DROP CONSTRAINT IF EXISTS uk_capability_map_key;
ALTER TABLE capability
    ADD CONSTRAINT uk_capability_map_key UNIQUE (content_map_id, capability_key);

-- content_map_id 가 씬의 것과 어긋날 수 없게 한다.
ALTER TABLE capability
    DROP CONSTRAINT IF EXISTS fk_capability_scene_map;
ALTER TABLE capability
    ADD CONSTRAINT fk_capability_scene_map
        FOREIGN KEY (scene_id, content_map_id) REFERENCES scene (id, content_map_id)
        ON DELETE CASCADE;

--------------------------------------------------------------------------------
-- 2. IL 위치 — 한 메서드 안의 갈래를 가르는 유일한 값
--------------------------------------------------------------------------------
-- 이 기능이 갈라져 나온 IL 위치. 같은 메서드 안의 서로 다른 지점을 가르는 유일한 값이다.
-- 이것이 없으면 근거 주소가 메서드까지만 남아, 한 Update() 안의 갈래 넷이 기능 하나로 눌린다.
--
-- 컬럼명에 offset 을 쓰지 않는다 — PostgreSQL 예약어라 매번 따옴표를 요구한다.
ALTER TABLE capability_evidence
    ADD COLUMN IF NOT EXISTS branch_offset INT;

-- 이 효과가 나온 IL 위치. 근거 원본이 effects[] 마다 이미 주는 값이다.
-- 같은 메서드가 위치에 따라 다른 효과를 낸다는 것을 표현할 수 있는 유일한 자리다.
ALTER TABLE capability_effect
    ADD COLUMN IF NOT EXISTS il_offset INT;

-- 조인이 메서드가 아니라 갈래 단위다. branch_offset 을 붙여 같은 entry_id 안에서 갈래를 좁힌다.
DROP INDEX IF EXISTS idx_capability_evidence_entry;
CREATE INDEX IF NOT EXISTS idx_capability_evidence_entry
    ON capability_evidence (entry_id, branch_offset);

--------------------------------------------------------------------------------
-- 3. 되짚기의 두 축이 조용히 비는 것을 막는다
--------------------------------------------------------------------------------
-- 값이 없는 것 자체는 허용하되, 없다면 왜 없는지가 gaps 에 남아야 한다. 실측에서 method_id 는
-- 18건 중 2건, call_path 는 1건만 실려 있었고 그 사실이 아무 데도 안 남아 "적재기가 못 채운 것"과
-- "근거에 원래 없는 것"이 구분되지 않았다.
--
-- 이미 있는 행이 새 규칙을 못 지킬 수 있으므로, 걸기 전에 사유를 채워 준다. 사유 없이 빈 행을
-- 남기면 제약이 붙는 순간 마이그레이션이 통째로 멈춘다.
UPDATE capability_evidence
   SET gaps = gaps || '["method-id-missing"]'::jsonb
 WHERE method_id IS NULL
   AND NOT (gaps @> '["method-id-missing"]'::jsonb);
UPDATE capability_evidence
   SET gaps = gaps || '["call-path-missing"]'::jsonb
 WHERE jsonb_array_length(call_path) = 0
   AND NOT (gaps @> '["call-path-missing"]'::jsonb);

ALTER TABLE capability_evidence
    DROP CONSTRAINT IF EXISTS ck_capability_evidence_method_id_or_gap;
ALTER TABLE capability_evidence
    ADD CONSTRAINT ck_capability_evidence_method_id_or_gap
        CHECK (method_id IS NOT NULL OR gaps @> '["method-id-missing"]'::jsonb);

ALTER TABLE capability_evidence
    DROP CONSTRAINT IF EXISTS ck_capability_evidence_call_path_or_gap;
ALTER TABLE capability_evidence
    ADD CONSTRAINT ck_capability_evidence_call_path_or_gap
        CHECK (jsonb_array_length(call_path) > 0 OR gaps @> '["call-path-missing"]'::jsonb);

--------------------------------------------------------------------------------
-- 4. v_content_map_capability — 새 두 칸을 창구에 낸다
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
