--------------------------------------------------------------------------------
-- V71. agent 가 본 것을 capability 에 적는 자리
--------------------------------------------------------------------------------
-- capability 472 행 중 `verification = 'confirmed'` 이 2 행이다. 지도는 정적 분석이 믿는 것만
-- 적고 그것이 참인지는 거의 모른다.
--
-- 기계로 채우는 길은 실측으로 막혔다. `interaction = 'none'` 이 418 행(89 퍼센트)이고 누를 수
-- 있는 것은 51 행뿐이라, action 전후의 `pulse` 를 비교하는 ARTEL-450 방식은 나머지 421 행에
-- 대해 아무 말도 할 수 없다. 그 421 행은 "적을 처치하면 보상을 받는다" 같은 것이라 누르는 것이
-- 아니라 일어나는 일이고, 일어났는지는 `screen` 을 본 쪽 — QA agent — 이 안다.
--
-- 그래서 agent 가 쓴다. 이 마이그레이션이 그 쓰기가 앉을 자리다.
--
-- 담는 것:
--   1. capability_observation 에 agent 의 문장이 앉을 여섯 칸
--   2. `pulse` diff 행의 NOT NULL 보장을 CHECK 로 다시 건다
--   3. 같은 문장은 한 행 — agent 문장의 멱등 키
--   4. capability.verification_observation_id — verification 을 되짚는 포인터
--   5. agent 가 적은 capability 행의 멱등 키
--   6. v_content_map_capability 를 다시 낸다

--------------------------------------------------------------------------------
-- 1. capability_observation — agent 의 문장이 앉을 자리
--------------------------------------------------------------------------------
-- 새 표를 만들지 않고 이 표를 넓힌다. `capability_inference.based_on` 이 V40 부터
-- `[capability_observation.id, ...]` 로 정의돼 있고, agent 가 `inferred` capability 를 적을 때
-- 딛고 선 observation 을 밝히는 자리가 그것이다. agent 의 문장이 다른 표에 앉으면 그 목록이
-- 가리킬 행이 영영 없다 — ARTEL-450 이 백로그로 내려가 이 표에 쓰는 코드가 지금 하나도 없다.
--
-- 대신 두 종류가 한 칸에 섞이지 않게 `source` 로 가른다. `pulse` diff 행은 "값이 달라졌다" 는
-- 측정이고 agent 행은 "됐다 / 안 됐다" 는 verdict 라, 구분할 칸이 없으면 둘이 같은 사실로 읽힌다.
ALTER TABLE capability_observation
    -- 이 행을 누가 만들었나. 기존 행은 없지만(쓰는 코드가 없다) 기본값은 pulse-diff 다 —
    -- ARTEL-450 이 되살아나면 그쪽이 아무것도 안 고쳐도 맞는 값이 들어간다.
    ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'pulse-diff',
    -- agent 의 verdict. works 는 `verification = 'confirmed'`, fails 는 'contradicted' 로 간다.
    -- `fired` 로 대신하지 않는다 — `fired` 는 "`pulse` 값이 달라졌나" 라는 측정이고, 418 행은
    -- 애초에 누르는 대상이 아니라 그 질문 자체가 성립하지 않는다.
    ADD COLUMN IF NOT EXISTS verdict VARCHAR(8),
    -- 무엇을 보고 그렇게 말했나. **verdict 만 받으면 나중에 그것이 맞았는지 확인할 길이 없다.**
    ADD COLUMN IF NOT EXISTS rationale TEXT,
    -- 캡처가 있으면 그것도 근거가 된다. `qa_log` 의 SCREENSHOT 행 message_id(=captureId) 다.
    -- FK 를 걸지 않는 이유: 캡처는 표가 아니라 `qa_log` 한 행이고, 그 표의 PK 는 id 라
    -- message_id 를 참조할 수 없다. 대신 서비스가 같은 try 안에 그 SCREENSHOT 행이 있는지 본다.
    ADD COLUMN IF NOT EXISTS capture_id VARCHAR(64),
    -- 런 안의 어느 try 가 적었나. qa_run_id 만으로는 시나리오 여럿을 도는 런에서 갈리지 않는다.
    -- SET NULL 이다 — try 가 사라져도 observation 은 그 런의 기록으로 남는다.
    ADD COLUMN IF NOT EXISTS qa_try_id BIGINT REFERENCES qa_try (id) ON DELETE SET NULL,
    -- 이 행을 만든 frame. 라우터가 UUID 를 강제하므로 실제로는 UUID 문자열이다.
    -- 멱등 키가 아니다(3 절 참조) — 되짚기용이다.
    ADD COLUMN IF NOT EXISTS agent_message_id VARCHAR(255);

ALTER TABLE capability_observation
    DROP CONSTRAINT IF EXISTS ck_capability_observation_source;
ALTER TABLE capability_observation
    ADD CONSTRAINT ck_capability_observation_source
        CHECK (source IN ('pulse-diff', 'agent'));

ALTER TABLE capability_observation
    DROP CONSTRAINT IF EXISTS ck_capability_observation_verdict;
ALTER TABLE capability_observation
    ADD CONSTRAINT ck_capability_observation_verdict
        CHECK (verdict IS NULL OR verdict IN ('works', 'fails'));

--------------------------------------------------------------------------------
-- 2. `pulse` diff 행의 보장을 CHECK 로 다시 건다
--------------------------------------------------------------------------------
-- agent 행에는 `action_method` 도 `fired` 도 없다. 418 행은 누르는 대상이 아니라 보낼 메서드가
-- 없고, 값이 달라졌는지는 agent 가 재는 것이 아니다. 그래서 두 칸의 NOT NULL 을 푼다.
--
-- 푸는 것으로 끝내면 ARTEL-450 이 되살아났을 때 그쪽 행도 두 칸을 비울 수 있게 된다. 그것이 이
-- 표의 원래 계약이었으므로, source 별 CHECK 로 같은 보장을 다시 건다.
ALTER TABLE capability_observation ALTER COLUMN action_method DROP NOT NULL;
ALTER TABLE capability_observation ALTER COLUMN fired DROP NOT NULL;

ALTER TABLE capability_observation
    DROP CONSTRAINT IF EXISTS ck_capability_observation_shape;
ALTER TABLE capability_observation
    ADD CONSTRAINT ck_capability_observation_shape
        CHECK (
            CASE source
                -- V40 의 계약 그대로. verdict 칸은 비어야 한다 — `pulse` diff 는 판단하지 않는다.
                WHEN 'pulse-diff' THEN
                    action_method IS NOT NULL
                    AND fired IS NOT NULL
                    AND verdict IS NULL
                    AND rationale IS NULL
                -- verdict 와 rationale 이 함께 온다. 빈 문자열은 rationale 이 아니다.
                WHEN 'agent' THEN
                    verdict IS NOT NULL
                    AND rationale IS NOT NULL
                    AND btrim(rationale) <> ''
                ELSE FALSE
            END
        );

--------------------------------------------------------------------------------
-- 3. 같은 문장은 한 행
--------------------------------------------------------------------------------
-- "이 capability 가 되는 것을 봤다" 는 한 런 안에서 한 번 참이다. tool 이 재시도해서 같은
-- frame 이 두 번 와도 행이 둘이 되면 안 되고, 그 멱등을 코드가 아니라 DB 가 강제한다.
--
-- 키에 `agent_message_id` 를 쓰지 않는 이유: 재시도하는 tool 은 새 messageId 를 발급한다.
-- 그러면 같은 문장이 다른 키를 들고 와 멱등이 걸리지 않는다.
--
-- `verdict` 가 키에 있는 것이 요점이다. 한 런에서 works 를 봤다가 나중에 fails 를 보면 그것은
-- **다른 문장**이라 행이 둘이 되어야 한다. 그 어긋남 자체가 값진 정보이고, 하나로 덮으면 사라진다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_capability_observation_agent_statement
    ON capability_observation (qa_run_id, capability_id, verdict)
    WHERE source = 'agent';

--------------------------------------------------------------------------------
-- 4. verification 을 되짚는 포인터
--------------------------------------------------------------------------------
-- `verification = 'confirmed'` 한 행을 본 사람이 어느 문장이 그것을 만들었는지 되짚을 수 있어야
-- 한다. TC 생성기가 confirmed 를 사실로 읽으므로, 잘못 올린 한 행은 근거 없는 테스트 케이스가
-- 되고 그 테스트가 실패하면 사람은 게임을 의심한다 — 지도를 의심하지 않는다.
--
-- 문장을 컬럼으로 복사하지 않고 포인터 하나만 둔다. observation 행이 rationale · qa_run_id ·
-- 시각을 이미 들고 있어, 복사하면 두 벌이 갈라진다.
--
-- SET NULL 인 대가를 적어 둔다: `capability_observation` 은 `qa_run` 에 CASCADE 라 런을 지우면
-- verification 은 남고 rationale 만 사라진다. 지금 `qa_run` 을 지우는 경로가 없어 열어 둔다 —
-- V40 이 같은 긴장을 같은 이유로 열어 두었다.
ALTER TABLE capability
    ADD COLUMN IF NOT EXISTS verification_observation_id BIGINT
        REFERENCES capability_observation (id) ON DELETE SET NULL;

--------------------------------------------------------------------------------
-- 5. agent 가 적은 capability 행의 멱등 키
--------------------------------------------------------------------------------
-- `capability_key` 는 `(entry_id, branch_offset, 정규화한 condition_tree)` 에서 만드는데
-- observed · inferred 출신은 셋 다 없어 키가 NULL 이다. 그래서 `uk_capability_map_key` 가
-- 걸리지 않고, 같은 발견을 두 번 보내면 행이 둘이 된다.
--
-- 내용으로 키를 만든다. `summary` 는 TEXT 라 통째로 인덱스에 넣으면 8KB 한계에 걸릴 수 있어
-- md5 로 접는다. 대소문자와 앞뒤 공백만 정규화한다 — 그 이상은 서로 다른 문장을 같다고 말하는
-- 일이라 이 자리가 아니다.
--
-- `origin` 을 키에 넣지 않는다. agent 가 먼저 `inferred` 로 적고 나중에 실제로 보면, 그것은 같은
-- capability 에 대한 두 사실이지 두 capability 가 아니다. 그때 origin 은 `inferred` 로 남고
-- verification 이 confirmed 가 된다 — 축이 둘인 설계가 정확히 그 경우를 위한 것이다.
--
-- **`evidence` 출신 행과는 겹치지 않는다.** 부분 인덱스라 `origin = 'evidence'` 행은 아예 보지
-- 않는다. agent 가 `evidence` 에 이미 있는 capability 를 다시 적는 경우를 합치는 것은 ARTEL-646 이다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_capability_agent_statement
    ON capability (
        scene_id,
        interaction,
        coalesce(control_path, ''),
        md5(lower(btrim(summary)))
    )
    WHERE origin IN ('observed', 'inferred') AND merged_into IS NULL;

--------------------------------------------------------------------------------
-- 6. v_content_map_capability 를 다시 낸다
--------------------------------------------------------------------------------
-- 창구를 하나로 못 박은 것이 이 뷰의 존재 이유다. verification 만 내고 그 rationale 을 다른
-- 창구에 두면 읽는 쪽이 두 곳을 봐야 한다.
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
WHERE c.merged_into IS NULL
  AND c.status <> 'not-a-step';
