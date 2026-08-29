--------------------------------------------------------------------------------
-- V63. 지도를 빌드당 하나로 모으고, 근거 문서 없이도 서게 한다
--------------------------------------------------------------------------------
-- V40 이 세운 유일 키는 `(game_build_id, capture)` 다. capture 는 스캔이 돈 자리에서 정해지므로
-- (멈춘 editor · editor-play · 빌드 실행) 같은 빌드를 두 자리에서 스캔하면 지도가 갈라진다.
--
-- 갈라 둔 값을 아무도 쌍으로 안 쓴다. 조회는 capture 하나를 골라 읽고, 인자가 없으면 id 가 가장
-- 큰 것을 고른다. TC 생성도 한쪽만 본다. 쌍을 지키려고 나눈 구조인데 소비자는 언제나 한쪽만 본다.
--
-- 그리고 지도의 뿌리가 근거 문서 하나뿐이다. `evidence_digest` 와 `schema_version` 이 NOT NULL 이라
-- 문서 없이는 `content_map` 행이 안 생기고, `scene.content_map_id` 가 NOT NULL 이라 씬도 못 생긴다.
-- 근거 스캔을 아직 못 돌린 빌드에서는 QA 런이 무엇을 알아내도 적을 자리가 없다.
--
-- 담는 것:
--   1. scene.capture · scene.origin — capture 가 씬으로 내려간다
--   2. 겹친 지도를 합친다 — 유일 키를 바꾸기 전에
--   3. 유일 키를 game_build_id 하나로
--   4. content_map 의 NOT NULL 셋 해제
--   5. content_map.rooted_by — 이 행이 근거에서 태어났나 관측에서 태어났나
--   6. 뷰 둘을 다시 낸다 — capture 가 cm 에서 s 로 옮겨간다
--
-- 잃는 것을 적어 둔다: capture 를 키에서 빼면 editor 값과 player 값을 한 빌드에서 동시에 들 수
-- 없다. 그 쌍은 지금도 아무도 안 읽으므로 실사용 손실이 없고, 대신 규칙이 "같은 씬을 다시 읽으면
-- 마지막 walk 가 이긴다" 로 바뀐다.

--------------------------------------------------------------------------------
-- 1. capture 가 씬으로 내려간다
--------------------------------------------------------------------------------
-- 갱신 단위가 원래 씬이라 이 자리가 맞다. SDK 는 씬을 재방문할 때마다 그 씬만 덮어쓰므로,
-- "이 값을 어느 상태에서 읽었나" 는 지도 전체가 아니라 씬마다 다를 수 있는 사실이다.
--
-- nullable 인 것이 요점이다. 관측이 만든 씬은 근거 walk 를 지나지 않아 capture 를 말할 수 없고,
-- 그때 아무 값이나 채우면 "editor 에서 읽었다" 는 거짓이 표에 앉는다.
ALTER TABLE scene
    ADD COLUMN IF NOT EXISTS capture VARCHAR(16);
ALTER TABLE scene
    DROP CONSTRAINT IF EXISTS ck_scene_capture;
ALTER TABLE scene
    ADD CONSTRAINT ck_scene_capture
        CHECK (capture IS NULL OR capture IN ('editor', 'editor-play', 'player'));

-- 어휘를 capability.origin 에서 그대로 가져온다(evidence · observed). 같은 뜻을 두 표에서 다른
-- 말로 적으면 "이 씬은 observed 인데 그 위의 기능은 observation 이다" 같은 문장이 생긴다.
-- inferred · human 을 받지 않는 것은 씬이 추론이나 사람 입력으로 태어나는 경로가 없어서다.
ALTER TABLE scene
    ADD COLUMN IF NOT EXISTS origin VARCHAR(16) NOT NULL DEFAULT 'evidence';
ALTER TABLE scene
    DROP CONSTRAINT IF EXISTS ck_scene_origin;
ALTER TABLE scene
    ADD CONSTRAINT ck_scene_origin
        CHECK (origin IN ('evidence', 'observed'));

-- 기존 씬은 전부 근거 walk 가 만든 것이다. 자기 지도의 옛 capture 를 그대로 물려받는다.
--
-- **2 절보다 앞이어야 한다.** 이관이 끝나면 진 지도가 사라져 그 씬이 어느 capture 에서 왔는지
-- 물어볼 곳이 없어진다.
UPDATE scene s
   SET capture = cm.capture
  FROM content_map cm
 WHERE cm.id = s.content_map_id
   AND s.capture IS NULL;

--------------------------------------------------------------------------------
-- 2. 겹친 지도를 합친다
--------------------------------------------------------------------------------
-- 남길 행은 id 가 큰 쪽이다. 지금 조회의 기본값과 같은 기준이라 사람이 보던 지도가 바뀌지 않는다.
--
-- 자식이 둘이고 둘 다 CASCADE 다 — `scene`(V40)과 `content_map_document`(V41). 씬만 옮기면 진
-- 지도의 문서 포인터가 함께 사라지고, 스토리지의 1.4MB 객체를 가리키는 행이 없어져 **재적재가
-- 불가능해진다.** 그래서 둘 다 옮긴다.
--
-- 이긴 지도에 같은 키가 이미 있으면 옮기지 않는다. 그 행은 진 지도와 함께 CASCADE 로 사라진다 —
-- 씬은 이름이 같으면 이긴 쪽이 이기고(story 의 "마지막 walk 가 이긴다"), 문서는 내용 해시가 같으면
-- 같은 문서라 사본을 남길 이유가 없다.
--
-- **`capability.content_map_id` 가 씬을 따라가야 한다.** V42 가 `fk_capability_scene_map` 으로
-- `(scene_id, content_map_id)` 를 씬의 것과 묶어 뒀고, 그 제약은 DEFERRABLE 이 아니라 씬을 옮기는
-- 문장 자체가 거절된다(실데이터 사본에서 실제로 거절당했다). 그래서 제약을 떼고 옮긴 뒤 다시
-- 건다 — 다시 걸 때 전수 검사가 돌아 두 벌이 갈라지지 않았음을 DB 가 확인해 준다.
--
-- `uk_capability_map_key (content_map_id, capability_key)` 는 이 이동으로 깨지지 않는다.
-- `capability_key` 의 첫 칸이 씬 이름이고(`CapabilityKey.of`), 옮기는 씬은 이긴 지도에 같은 이름이
-- **없을 때만** 옮기기 때문이다.
ALTER TABLE capability
    DROP CONSTRAINT IF EXISTS fk_capability_scene_map;
--
-- **진 지도가 둘 이상이면 그들끼리도 겹친다.** 한 UPDATE 안의 NOT EXISTS 는 문장 시작 시점의
-- 스냅샷을 보므로, 이긴 지도에 없는 이름을 진 지도 둘이 함께 들고 있으면 둘 다 통과해
-- `uk_scene_map_name` 에서 죽는다. ROW_NUMBER 로 이름당 하나만 고르고, 그 하나는 id 가 큰
-- 지도의 것 — 승자를 고른 기준과 같다.
WITH winners AS (
    SELECT game_build_id, MAX(id) AS winner_id
      FROM content_map
     GROUP BY game_build_id
), movable AS (
    SELECT s.id,
           w.winner_id,
           ROW_NUMBER() OVER (
               PARTITION BY w.winner_id, s.name
               ORDER BY s.content_map_id DESC, s.id DESC
           ) AS pick
      FROM scene s
      JOIN content_map loser ON loser.id = s.content_map_id
      JOIN winners w ON w.game_build_id = loser.game_build_id
     WHERE loser.id <> w.winner_id
       AND NOT EXISTS (
               SELECT 1
                 FROM scene kept
                WHERE kept.content_map_id = w.winner_id
                  AND kept.name = s.name
           )
)
UPDATE scene s
   SET content_map_id = movable.winner_id
  FROM movable
 WHERE s.id = movable.id
   AND movable.pick = 1;

WITH winners AS (
    SELECT game_build_id, MAX(id) AS winner_id
      FROM content_map
     GROUP BY game_build_id
), movable AS (
    SELECT d.id,
           w.winner_id,
           ROW_NUMBER() OVER (
               PARTITION BY w.winner_id, d.content_hash
               ORDER BY d.content_map_id DESC, d.id DESC
           ) AS pick
      FROM content_map_document d
      JOIN content_map loser ON loser.id = d.content_map_id
      JOIN winners w ON w.game_build_id = loser.game_build_id
     WHERE loser.id <> w.winner_id
       AND NOT EXISTS (
               SELECT 1
                 FROM content_map_document kept
                WHERE kept.content_map_id = w.winner_id
                  AND kept.content_hash = d.content_hash
           )
)
UPDATE content_map_document d
   SET content_map_id = movable.winner_id
  FROM movable
 WHERE d.id = movable.id
   AND movable.pick = 1;

-- 씬을 따라간다. V42 가 이 컬럼을 처음 채울 때와 같은 문장이다.
UPDATE capability c
   SET content_map_id = s.content_map_id
  FROM scene s
 WHERE s.id = c.scene_id
   AND c.content_map_id IS DISTINCT FROM s.content_map_id;

DELETE FROM content_map loser
 USING (
           SELECT game_build_id, MAX(id) AS winner_id
             FROM content_map
            GROUP BY game_build_id
       ) winners
 WHERE winners.game_build_id = loser.game_build_id
   AND loser.id <> winners.winner_id;

-- 다시 건다. 여기서 전수 검사가 돌아, 이관이 두 벌을 갈라 놓지 않았음을 DB 가 확인한다.
ALTER TABLE capability
    ADD CONSTRAINT fk_capability_scene_map
        FOREIGN KEY (scene_id, content_map_id) REFERENCES scene (id, content_map_id)
        ON DELETE CASCADE;

--------------------------------------------------------------------------------
-- 3. 유일 키가 game_build_id 하나다
--------------------------------------------------------------------------------
ALTER TABLE content_map
    DROP CONSTRAINT IF EXISTS uk_content_map_build_capture;
ALTER TABLE content_map
    DROP CONSTRAINT IF EXISTS uk_content_map_build;
ALTER TABLE content_map
    ADD CONSTRAINT uk_content_map_build UNIQUE (game_build_id);

--------------------------------------------------------------------------------
-- 4. 근거 문서 없이도 행이 선다
--------------------------------------------------------------------------------
-- 이 셋은 전부 **근거 문서가 말해 주는 것**이다. QA 런이 먼저 도는 빌드에서는 아무도 말해 준 적이
-- 없고, 그때 더미값을 넣으면 "schema 0 · 지문 없음" 이 진짜 헤더와 같은 칸에 앉는다.
ALTER TABLE content_map ALTER COLUMN schema_version DROP NOT NULL;
ALTER TABLE content_map ALTER COLUMN evidence_digest DROP NOT NULL;
ALTER TABLE content_map ALTER COLUMN capture DROP NOT NULL;

--------------------------------------------------------------------------------
-- 5. 이 지도가 무엇에서 태어났나
--------------------------------------------------------------------------------
-- NOT NULL 셋이 풀리면 "값이 없다" 만으로는 근거가 아직 안 온 지도인지 근거가 있었는데 헤더가 빈
-- 지도인지 가릴 수 없다. 그 판정을 컬럼 부재에서 추론하지 않고 여기 적는다.
--
-- 어휘가 scene.origin(evidence · observed)과 다른 것은 뜻이 다르기 때문이다. 씬의 origin 은
-- capability 와 같은 축 — 이 한 줄을 어디서 알아냈나. 지도의 rooted_by 는 이 행을 세운 경로가
-- 근거 등록이었나 관측이었나를 말한다.
--
-- 기존 행은 전부 근거 등록이 세운 것이라 DEFAULT 가 그대로 맞다.
ALTER TABLE content_map
    ADD COLUMN IF NOT EXISTS rooted_by VARCHAR(16) NOT NULL DEFAULT 'evidence';
ALTER TABLE content_map
    DROP CONSTRAINT IF EXISTS ck_content_map_rooted_by;
ALTER TABLE content_map
    ADD CONSTRAINT ck_content_map_rooted_by
        CHECK (rooted_by IN ('evidence', 'observation'));

--------------------------------------------------------------------------------
-- 6. 뷰 둘을 다시 낸다
--------------------------------------------------------------------------------
-- `CREATE OR REPLACE VIEW` 로는 못 고친다. Postgres 는 뒤에 열을 붙이는 것만 허용하는데
-- `capture` 의 출처가 `cm` 에서 `s` 로 바뀐다. V45 가 status 를 옮길 때 한 것과 같은 모양이다.
--
-- 소비자가 보는 열 이름은 `capture` 그대로다. 값이 나오는 자리만 씬으로 내려갔고, TC 생성기는
-- 이미 씬 단위로 읽으므로 읽는 쪽이 달라지지 않는다.
--
-- `v_spec_gap` 은 `capture` 를 내지 않는데도 함께 다시 낸다. 둘이 한 쌍으로 읽히는 표라, 가장 새
-- 정의만 읽어도 쌍 전체가 서게 하려는 것이다(V46 이 같은 이유로 정의를 통째로 옮겼다).
DROP VIEW IF EXISTS v_spec_gap;
DROP VIEW IF EXISTS v_content_map_capability;

-- 효과는 여기 접지 않는다. 행이 여러 개라 조인하면 곱해진다.
CREATE VIEW v_content_map_capability AS
SELECT
    cm.id AS content_map_id,
    -- 씬의 것이다. 한 지도 안에서 씬마다 다를 수 있고, 관측이 만든 씬에서는 NULL 이다.
    s.capture,
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
    -- entry_id 만 내면 근거 주소가 메서드까지다. branch 를 짚으려면 둘이 함께 나가야 한다.
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

-- V46 의 정의를 그대로 다시 낸다. 위 뷰와 짝이라 함께 떨어졌을 뿐, 이 뷰가 답하는 질문은 바뀌지
-- 않았다.
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
-- not-a-step 을 거르지 않는 것도 의도다. 무엇이 조작을 갖지 못했는지가 이 표가 답해야 할
-- 질문이다. 대신 status 를 함께 내서, TC 생성기가 실제로 받는 행(v_content_map_capability 가
-- 내주는 것)만 세고 싶은 쪽이 걸러 쓸 수 있게 한다.
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
        -- 스폰 행만 여기서 빠진다(V46). 만들어지는 쪽은 조작이 없어야 맞는 행이다.
        WHEN c.interaction = 'none' AND c.spawned_by_field IS NULL THEN 'when-missing'
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
