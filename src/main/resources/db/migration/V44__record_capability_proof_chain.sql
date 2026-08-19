--------------------------------------------------------------------------------
-- V44. 결론에 이르는 사슬을 단계별로 남긴다
--------------------------------------------------------------------------------
-- V40 이 세운 content_map 스키마 위에 얹는다. V40 을 직접 고치지 않는 이유는 번호가 한 번
-- 붙으면 그 파일의 checksum 이 계약이 되기 때문이다 — 이미 V40 을 적용한 데이터베이스가
-- 하나라도 있으면 본문 수정은 validate 실패로 돌아온다.
--
-- 담는 것:
--   1. capability_evidence.analysis_confidence 어휘를 specs_v2 Resolution 과 통일
--   2. capability_effect.resolution — 효과마다의 확실성
--   3. capability_proof — 결론에 이르는 사슬. 한 단계 = 한 행
--   4. v_spec_gap 의 given-incomplete 분기를 새 어휘로
--   5. v_capability_proof — 사슬의 별도 조회 창구

--------------------------------------------------------------------------------
-- 1. analysis_confidence — specs_v2 Resolution 어휘로 통일
--------------------------------------------------------------------------------
-- V40 은 verified | derived | partial 을 썼고 agent-server 의 specs_v2 Resolution 은
-- exact | derived | ambiguous | unresolved 를 쓴다. analysis_confidence 를 "사슬
-- (capability_proof.resolution) 의 최솟값"으로 정의하려면 두 값이 같은 순서 집합이어야 한다.
-- 어휘가 다르면 계산한 값과 저장된 값을 비교할 방법 자체가 없다.
--
-- 옮김: verified → exact, derived → derived, partial → ambiguous.
-- unresolved 는 새 칸으로, partial 에 뭉쳐 있던 "아예 못 풀었다"를 가른다.
ALTER TABLE capability_evidence
    DROP CONSTRAINT IF EXISTS capability_evidence_analysis_confidence_check;

UPDATE capability_evidence SET analysis_confidence = 'exact' WHERE analysis_confidence = 'verified';
UPDATE capability_evidence SET analysis_confidence = 'ambiguous' WHERE analysis_confidence = 'partial';

--   exact       그 자리에서 확정했다
--   derived     유도했다
--   ambiguous   후보가 여럿이라 하나로 못 좁혔다
--   unresolved  못 풀었다
ALTER TABLE capability_evidence
    ADD CONSTRAINT capability_evidence_analysis_confidence_check
        CHECK (analysis_confidence IN ('exact', 'derived', 'ambiguous', 'unresolved'));

--------------------------------------------------------------------------------
-- 2. capability_effect.resolution — 이 효과를 얼마나 확실하게 짚었나
--------------------------------------------------------------------------------
-- capability_evidence.analysis_confidence 는 기능 전체의 등급이라 어느 효과가 흐린지 말하지
-- 못한다. 효과마다 두면 "이 then 은 단정 근거로 쓸 수 있고 저것은 아니다"가 갈린다.
--
-- null 인 이유: origin='observed' 인 효과는 유도가 아니라 관측이라 사슬이 없다.
ALTER TABLE capability_effect
    ADD COLUMN IF NOT EXISTS resolution VARCHAR(16);
ALTER TABLE capability_effect
    DROP CONSTRAINT IF EXISTS ck_capability_effect_resolution;
ALTER TABLE capability_effect
    ADD CONSTRAINT ck_capability_effect_resolution
        CHECK (resolution IN ('exact', 'derived', 'ambiguous', 'unresolved'));

--------------------------------------------------------------------------------
-- 3. capability_proof — 결론에 이르는 사슬. 한 단계 = 한 행
--------------------------------------------------------------------------------
-- analysis_confidence 등급 하나로는 "이 결론이 틀렸다"까지만 말할 수 있다. 호출을 잘못 따라간
-- 것인지, 필드 쓰기를 잘못 읽은 것인지, 조건을 잘못 붙인 것인지 가릴 수 없어 적재기 규칙을
-- 고치러 갈 자리를 못 짚는다.
--
-- 등급 하나로 눌리면 신뢰도의 원인도 사라진다. 사슬의 한 단계만 흐린 탓에 전체가 내려간 것과,
-- 처음부터 끝까지 흐릿한 것이 같은 derived 로 보인다.
--
-- JSONB 한 칸에 넣지 않는 이유: 사슬이라 행이 여럿이고, "어느 단계에서 흐려졌나"를 묻는 질의가
-- 이 표의 존재 이유다. JSONB 에 넣으면 그 질의가 매번 문서를 펴야 한다.
--
-- rule 은 적재기가 적용한 규칙의 이름이다. 같은 규칙이 계속 흐린 결론을 내면 그 이름이 뭉쳐
-- 나오고, 그것이 고칠 규칙이다.
CREATE TABLE IF NOT EXISTS capability_proof (
    id BIGSERIAL PRIMARY KEY,
    capability_id BIGINT NOT NULL REFERENCES capability (id) ON DELETE CASCADE,

    -- 어느 효과를 유도한 사슬인가. null 이면 기능 자체(given 을 세운 과정)에 붙은 사슬이다.
    effect_id BIGINT REFERENCES capability_effect (id) ON DELETE CASCADE,

    -- 사슬 안의 순서. 0 부터.
    seq INT NOT NULL,

    -- 한 단계: source 를 relation 으로 따라가 target 에 닿았다.
    source VARCHAR(1024) NOT NULL,
    relation VARCHAR(64) NOT NULL,
    target VARCHAR(1024),

    -- 이 단계의 확실성. 어휘는 specs_v2 Resolution 과 같다.
    resolution VARCHAR(16) NOT NULL
        CHECK (resolution IN ('exact', 'derived', 'ambiguous', 'unresolved')),

    -- 적용한 규칙 이름.
    rule VARCHAR(64) NOT NULL,

    CONSTRAINT ck_capability_proof_seq_nonneg CHECK (seq >= 0)
);
-- 순서가 사슬마다 하나뿐임을 강제한다. effect_id 가 null 인 사슬과 아닌 사슬을 따로 거는 이유는
-- UNIQUE 가 NULL 을 서로 다르게 보아 한 제약으로는 null 쪽이 안 걸리기 때문이다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_capability_proof_effect_seq
    ON capability_proof (capability_id, effect_id, seq) WHERE effect_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_capability_proof_capability_seq
    ON capability_proof (capability_id, seq) WHERE effect_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_capability_proof_rule
    ON capability_proof (rule, resolution);

--------------------------------------------------------------------------------
-- 4. v_spec_gap — given-incomplete 분기를 새 어휘로
--------------------------------------------------------------------------------
-- V40 의 정의를 그대로 다시 낸다. 바뀐 것은 analysis_confidence 를 보는 한 줄뿐이고, 컬럼
-- 목록과 순서가 같아야 CREATE OR REPLACE 가 통하므로 본문 전체를 싣는다.
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
CREATE OR REPLACE VIEW v_spec_gap AS
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

--------------------------------------------------------------------------------
-- 5. v_capability_proof — 사슬의 별도 조회 창구
--------------------------------------------------------------------------------
-- v_content_map_capability 에 조인하지 않는 이유: 그 뷰는 효과조차 접지 않는다. 기능 하나에
-- 효과가 여럿이라 행이 곱해지기 때문이고, 사슬은 효과마다 여러 단계라 더 곱해진다. TC 생성기가
-- 받는 행 수가 사슬 길이에 따라 흔들리면 그 뷰의 계약이 무너진다.
--
-- 대신 여기로 따로 읽는다. 묻는 질문이 다르다 — 저쪽은 "무엇을 테스트할 수 있나"이고 이쪽은
-- "이 결론은 어떻게 나왔나"다.
CREATE OR REPLACE VIEW v_capability_proof AS
SELECT
    s.content_map_id,
    c.scene_id,
    c.id AS capability_id,
    c.capability_key,
    p.effect_id,
    p.seq,
    p.source,
    p.relation,
    p.target,
    p.resolution,
    p.rule,
    -- 이 사슬 전체의 확실성. 가장 흐린 단계가 전체를 정한다.
    MIN(
        CASE p.resolution
            WHEN 'exact' THEN 1
            WHEN 'derived' THEN 2
            WHEN 'ambiguous' THEN 3
            ELSE 4
        END
    ) OVER (PARTITION BY p.capability_id, p.effect_id) AS chain_rank
FROM capability_proof p
JOIN capability c ON c.id = p.capability_id
JOIN scene s ON s.id = c.scene_id;
