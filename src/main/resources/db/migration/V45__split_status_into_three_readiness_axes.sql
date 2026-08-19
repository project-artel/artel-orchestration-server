--------------------------------------------------------------------------------
-- V45. status 를 실행·관측·적용 세 축으로 가른다
--------------------------------------------------------------------------------
-- V40 이 세운 스키마와 V42 · V43 · V44 가 얹은 것 위에 다시 얹는다. V40 본문을 직접 고치지
-- 않는 이유는 번호가 한 번 붙으면 그 파일의 checksum 이 계약이 되기 때문이다.
--
-- 담는 것:
--   1. capability 에 세 축 컬럼
--   2. 기존 status 값을 축으로 옮긴다
--   3. status 를 생성 컬럼으로 바꾼다 — 축과 어긋난 행이 존재할 수 없게
--   4. status 를 읽는 뷰 둘을 다시 낸다

--------------------------------------------------------------------------------
-- 1. 세 축
--------------------------------------------------------------------------------
-- 축이 셋이다. 하나로 눌러 두면 "실행은 되는데 관측이 안 됨"과 "이 빌드엔 적용 안 됨"이
-- 같은 needs-probe 한 통에 들어가 소비자가 가릴 수 없다. 앞은 조작 스텝으로는 쓸 수 있고
-- 판정 근거로만 못 쓴다. 뒤는 아예 쓸 수 없다.
--
-- 축을 나누면 각자 자기 축만 정한다. watchable 판정(ARTEL-452)은 관측 축만 건드리고, gap
-- 판정(ARTEL-461)은 실행 축만 건드린다 — 지금은 둘이 한 컬럼에서 서로를 덮어쓴다.
--
-- 축이 내려간 이유는 여기 적지 않는다. capability_evidence.gaps 가 사유를 든다.
-- 축은 상태이고 gap 은 사유다.
ALTER TABLE capability
    ADD COLUMN IF NOT EXISTS actionability VARCHAR(32) NOT NULL DEFAULT 'runnable';
ALTER TABLE capability
    DROP CONSTRAINT IF EXISTS ck_capability_actionability;
ALTER TABLE capability
    ADD CONSTRAINT ck_capability_actionability
        CHECK (actionability IN ('runnable', 'needs-probe', 'unreachable-precondition', 'not-a-step'));

ALTER TABLE capability
    ADD COLUMN IF NOT EXISTS observability VARCHAR(16) NOT NULL DEFAULT 'unknown';
ALTER TABLE capability
    DROP CONSTRAINT IF EXISTS ck_capability_observability;
ALTER TABLE capability
    ADD CONSTRAINT ck_capability_observability
        CHECK (observability IN ('observable', 'unobservable', 'unknown'));

ALTER TABLE capability
    ADD COLUMN IF NOT EXISTS applicability VARCHAR(16) NOT NULL DEFAULT 'applies';
ALTER TABLE capability
    DROP CONSTRAINT IF EXISTS ck_capability_applicability;
ALTER TABLE capability
    ADD CONSTRAINT ck_capability_applicability
        CHECK (applicability IN ('applies', 'not-applicable', 'unknown'));

--------------------------------------------------------------------------------
-- 2. 기존 status 값을 축으로 옮긴다
--------------------------------------------------------------------------------
-- status 를 버리기 전에 옮긴다. 순서가 반대면 값이 사라지고, 그 뒤 생성 컬럼이 기본값에서
-- 다시 계산한 값은 원래 값과 다르다.
--
-- observability 를 status='runnable' 인 행에서만 'observable' 로 세우는 이유: 아래 유도 규칙이
-- observability <> 'observable' 을 needs-probe 로 접는다. 기본값 'unknown' 그대로 두면
-- runnable 이던 행이 전부 needs-probe 로 뒤집힌다.
UPDATE capability
   SET actionability = status,
       observability = CASE WHEN status = 'runnable' THEN 'observable' ELSE 'unknown' END,
       applicability = 'applies';

--------------------------------------------------------------------------------
-- 3. status 를 생성 컬럼으로
--------------------------------------------------------------------------------
-- status 를 읽는 뷰를 먼저 떼야 컬럼을 버릴 수 있다. 4 절에서 다시 낸다.
DROP VIEW IF EXISTS v_spec_gap;
DROP VIEW IF EXISTS v_content_map_capability;

-- 생성 컬럼은 기존 컬럼을 고쳐 만들 수 없다. 버리고 다시 만드는 것이 유일한 길이다.
ALTER TABLE capability
    DROP COLUMN status;

-- 세 축에서 유도된 값이다. 생성 컬럼으로 둬서 적재기가 따로 쓰지 못하게 한다 — 쓸 수 있으면
-- 언젠가 축과 어긋나고, 어긋난 뒤에는 어느 쪽이 참인지 아무도 모른다.
--
-- 값 집합은 그대로다. 이 컬럼을 읽던 소비자는 바뀐 것을 못 느낀다.
--
-- 유도 순서가 뜻을 정한다:
--   조작이 아니면 그것이 먼저다. 관측이 되든 말든 명세의 스텝이 못 된다
--   적용 불가는 실행 불가와 같은 칸으로 내린다. 기존 어휘에 "이 빌드엔 없다"가 없어서다
--   실행은 되는데 관측이 안 되면 needs-probe. 그 칸이 원래 뜻하던 바다
ALTER TABLE capability
    ADD COLUMN status VARCHAR(32) GENERATED ALWAYS AS (
        CASE
            WHEN actionability = 'not-a-step' THEN 'not-a-step'
            WHEN applicability = 'not-applicable' THEN 'unreachable-precondition'
            WHEN actionability = 'unreachable-precondition' THEN 'unreachable-precondition'
            WHEN actionability = 'needs-probe' THEN 'needs-probe'
            WHEN observability <> 'observable' THEN 'needs-probe'
            ELSE 'runnable'
        END
    ) STORED;

--------------------------------------------------------------------------------
-- 4. status 를 읽던 뷰 둘을 다시 낸다
--------------------------------------------------------------------------------
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

-- V44 의 정의를 그대로 다시 낸다. 3 절에서 status 를 떼느라 함께 떨어졌을 뿐, 이 뷰가 답하는
-- 질문은 바뀌지 않았다. 축은 상태이고 gap 은 사유라, 겹치는 분기를 억지로 맞추지 않는다.
--
-- 테이블이 아니라 뷰인 이유: 전부 다른 컬럼에서 계산되므로 적재 코드를 두면 낡는다.
--
-- 이것은 QA 결함이 아니라 개발 우선순위 신호다. then-missing 이 많으면 수집기(SDK)를 고칠
-- 차례이고, given-subject-unknown 이 많으면 조건 분석기의 주어 추적이 약한 것이다.
-- agent 가 메울 수 있는 것이 아니다 — 근거에 없는 것을 메우면 그럴듯한 거짓말이 된다.
--
-- 사유가 여럿 성립하면 먼저 걸리는 것 하나만 나온다. 순서가 이런 이유:
--
--   when-missing 이 맨 앞     조작이 없으면 given 과 then 을 아무리 알아도 명세가 아니다
--   given-* 가 then-* 보다 앞  then 이 비었다는 것은 status='needs-probe' 로도 알 수 있지만,
--                             given 이 왜 불완전한지는 여기 말고 나오는 데가 없다
--
-- 그 순서의 대가: 조건이 ambiguous 이면서 관측 가능 효과도 없는 행은 given-incomplete 로만 잡혀
-- then-missing 집계가 그만큼 적게 나온다. 두 사유를 다 세야 하면 이 뷰가 아니라 사유별
-- EXISTS 를 각각 세는 질의를 따로 써야 한다.
--
-- not-a-step 을 거르지 않는 것도 의도다. 그 행들이 when-missing 의 본체이고, 무엇이 조작을
-- 갖지 못했는지가 이 표가 답해야 할 질문이다. 대신 status 를 함께 내서, TC 생성기가 실제로
-- 받는 행(v_content_map_capability 가 내주는 것)만 세고 싶은 쪽이 걸러 쓸 수 있게 한다.
CREATE VIEW v_spec_gap AS
SELECT
    s.content_map_id,
    c.scene_id,
    c.id AS capability_id,
    c.status,
    CASE
        -- 적재기 결함이다. 게임의 근거가 부족한 것이 아니라 우리가 근거를 잃은 것이라,
        -- then-missing 으로 뭉뚱그리면 SDK 를 고치러 가서 헛짚는다.
        WHEN c.origin = 'evidence' AND ce.capability_id IS NULL THEN 'evidence-missing'
        WHEN c.interaction = 'none' THEN 'when-missing'
        WHEN ce.gaps @> '["subject-null"]'::jsonb THEN 'given-subject-unknown'
        -- ambiguous 는 "후보가 여럿", unresolved 는 "못 풀었다". 둘 다 조건을 단정할 수 없다는
        -- 뜻이라 같은 사유로 낸다. 어느 단계에서 그렇게 됐는지는 capability_proof 가 답한다.
        WHEN ce.analysis_confidence IN ('ambiguous', 'unresolved')
          OR ce.gaps @> '["callee-condition-not-composed"]'::jsonb THEN 'given-incomplete'
        WHEN ce.gaps @> '["unread-condition"]'::jsonb THEN 'given-unread'
        WHEN NOT EXISTS (
            SELECT 1 FROM capability_effect e
            WHERE e.capability_id = c.id
              AND e.category IN ('observable', 'availability')
        ) THEN 'then-missing'
        WHEN EXISTS (
            SELECT 1 FROM capability_effect e
            WHERE e.capability_id = c.id
              AND e.category = 'observable'
              AND e.detail IS NULL
        ) THEN 'then-detail-unknown'
        ELSE NULL
    END AS reason
FROM capability c
JOIN scene s ON s.id = c.scene_id
LEFT JOIN capability_evidence ce ON ce.capability_id = c.id
WHERE c.merged_into IS NULL;
