-- ARTEL-649: collection family 를 `discriminator` 에서 뺀다. 그리고 옛 규칙으로 쌓인 화면 행을 접는다.
--
-- V56 이 `uk_screen_discriminator` 를 걸면서 "두 관측을 같은 화면으로 볼지는 `discriminator` 가
-- 정한다"고 못박았다. 그 규칙이 이제 바뀐다. 규칙만 바꾸고 행을 두면 한 씬 안에 **옛 규칙으로
-- 만든 행과 새 규칙으로 만들 행이 섞이고**, `observed_count` 가 그 둘에 쪼개져 "몇 번 지나갔나"가
-- 양쪽 다 틀린 값이 된다. 그래서 여기서 접는다.
--
-- 실측(`artel_integration`, 2026-08-28): `screen` 30행 중 29행이 `TurnBattleScene` 의 것이었고
-- `ScreenObservationService.MAX_SCREENS_PER_SCENE` 32 에 닿기 직전이었다. 형제 인덱스를 지운
-- family 로 묶으면 12 family 가 나오고, 그중 화면 하나 안에 인스턴스가 둘 이상인 넷
-- (`Card(Clone)` · `MeleeRock(Clone)` · `RangedCat(Clone)` · `BossFlower(Clone)`) 을 빼면 29행이
-- **3행**으로 접힌다. 셋은 각각 combine panel 열림 · 평시 전투 · combine 확정 가능이다.
--
-- ## 왜 지우지 않고 합치는가
--
-- 지우면 `observed_count` 와 `screen_transition` 이 함께 사라진다. 그 둘은 QA 런이 실제로 밟아서
-- 벌어 온 유일한 지식이고, 정적 분석으로 다시 만들 수 없다. 접히는 29행은 **같은 화면을 29번
-- 다르게 적은 것**이지 29개의 다른 관측이 아니므로, 합이 곧 맞는 값이다.
--
-- ## 순서가 중요하다
--
-- `uk_screen_discriminator` 가 이미 걸려 있어, 새 `discriminator` 를 먼저 쓰면 합치기 전에 제약
-- 위반이 난다. **합치기를 먼저 하고 새 값을 쓴다.**
--
-- ⚠️ 번호: develop 과 모든 워크트리·원격 브랜치를 통틀어 V57 이 가장 높다. 비어 보이는 번호
-- (V47 · V50 · V52 · V53) 는 쓰지 않는다 — 더 높은 번호를 이미 적용한 DB 에서 out-of-order 로
-- 걸린다(`docs/flyway-migrations.md` 의 "Tangle").

--------------------------------------------------------------------------------
-- 1. scene_collection_family — collection 판정을 기억할 자리
--------------------------------------------------------------------------------
-- **씬 옆에 둔다.** collection 인지 아닌지는 씬의 구조적 사실이고, 그 씬의 어느 화면에서 보든 같은
-- 답이어야 한다.
--
-- **프로세스 메모리에 두지 않는다.** `ScreenObservationService.folds` 는 재시작하면 사라지고 서버가
-- 두 대면 각자 자기 것을 본다. 그러면 같은 화면이 런마다·재시작마다 다른 `discriminator` 로 앉아,
-- V56 이 막으려던 바로 그 분열이 규칙 쪽에서 다시 열린다. 판정 근거는
-- `ScreenObservationService.rememberedCollectionFamilies` 에 있다.
CREATE TABLE IF NOT EXISTS scene_collection_family (
    id BIGSERIAL PRIMARY KEY,
    scene_id BIGINT NOT NULL REFERENCES scene (id) ON DELETE CASCADE,
    -- 형제 인덱스를 지운 경로. capability.control_selector 와 같은 표기라 길이도 같이 잡는다.
    family VARCHAR(512) NOT NULL,
    first_observed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_scene_collection_family UNIQUE (scene_id, family)
);

COMMENT ON TABLE scene_collection_family IS
    '이 씬에서 인스턴스가 둘 이상 관측된 경로 family. discriminator 가 이 family 를 통째로 뺀다.';
COMMENT ON COLUMN scene_collection_family.family IS
    'selector 에서 모든 마디의 형제 인덱스를 지운 경로. ScreenFold.collectionFamilyOf 와 같은 규칙이다.';

--------------------------------------------------------------------------------
-- 2. 이미 쌓인 discriminator 에서 collection family 를 뽑아 표를 채운다
--------------------------------------------------------------------------------
-- 새 규칙이 다음 런의 **첫 pulse 부터** 서 있게 하려는 것이다. 표를 비운 채로 두면 서버가 그 씬을
-- 처음부터 다시 배우고, 배우기 전에 앉은 화면이 다시 옛 모양으로 남는다.
--
-- 여기서 세는 것은 `discriminator` 에 실린 인스턴스뿐이다. 런타임은 `fold` 가 든 객체를 전부 세므로
-- 더 넓게 본다. 지나간 `pulse` 는 남아 있지 않고 `discriminator` 가 이 DB 에 남은 유일한 증거라,
-- 이 마이그레이션이 볼 수 있는 것이 그것뿐이다. 좁게 봐서 놓친 family 는 다음 런이 잡는다.
--
-- **켜진 것만 세지 않는다.** 같은 실측에서 `MeleeRock(Clone)` 은 동시 활성이 최대 1이고
-- `BossFlower(Clone)` 은 한 번도 켜진 적이 없다 — 스폰되자마자 풀에 들어가 꺼진 채 남기 때문이다.
-- 그래도 `discriminator` 에는 `active:false` 로 들어가 화면을 가른다. 활성만 세는 규칙으로 재보면
-- 29행이 3이 아니라 12 로만 접힌다.
INSERT INTO scene_collection_family (scene_id, family)
SELECT scene_id, family
FROM (
    SELECT s.scene_id,
           s.id AS screen_id,
           regexp_replace(e.el ->> 'selector', '\[[0-9]+\]', '', 'g') AS family,
           count(*) AS instances
    FROM screen s, LATERAL jsonb_array_elements(s.discriminator) AS e(el)
    GROUP BY 1, 2, 3
) per_screen
WHERE instances > 1
GROUP BY scene_id, family
ON CONFLICT (scene_id, family) DO NOTHING;

--------------------------------------------------------------------------------
-- 3. 화면마다 새 discriminator 를 계산하고, 같아지는 행을 한 묶음으로 본다
--------------------------------------------------------------------------------
-- 묶음마다 id 가 가장 작은 행을 남긴다. 그 행이 가장 먼저 본 행이므로 `first_seen_qa_run_id` 도
-- 그대로 맞는다.
--
-- family 를 전부 뺀 결과가 `[]` 가 되는 화면이 있을 수 있다. 그 씬에서 조작 가능한 것이 전부
-- collection 이었다는 뜻이고, 그때 그 씬의 화면은 하나다 — 가를 근거가 없는데 가르는 것보다 맞다.
DROP TABLE IF EXISTS v58_screen_rewrite;
CREATE TEMP TABLE v58_screen_rewrite AS
SELECT s.id AS screen_id,
       s.scene_id,
       COALESCE(
           (
               SELECT jsonb_agg(e.el ORDER BY e.el ->> 'selector')
               FROM jsonb_array_elements(s.discriminator) AS e(el)
               WHERE NOT EXISTS (
                   SELECT 1
                   FROM scene_collection_family f
                   WHERE f.scene_id = s.scene_id
                     AND f.family = regexp_replace(e.el ->> 'selector', '\[[0-9]+\]', '', 'g')
               )
           ),
           '[]'::jsonb
       ) AS discriminator
FROM screen s;

DROP TABLE IF EXISTS v58_screen_merge;
CREATE TEMP TABLE v58_screen_merge AS
SELECT r.screen_id,
       min(r.screen_id) OVER (PARTITION BY r.scene_id, r.discriminator) AS keeper_id
FROM v58_screen_rewrite r;

CREATE INDEX ON v58_screen_merge (screen_id);

--------------------------------------------------------------------------------
-- 4. screen_capability — 참조를 남길 행으로 옮기고 관측 수를 합친다
--------------------------------------------------------------------------------
-- 지우는 것은 5절의 `DELETE FROM screen` 이 CASCADE 로 한다.
INSERT INTO screen_capability (screen_id, capability_id, observed_count, fired_count)
SELECT m.keeper_id, sc.capability_id, sum(sc.observed_count), sum(sc.fired_count)
FROM screen_capability sc
JOIN v58_screen_merge m ON m.screen_id = sc.screen_id
WHERE m.screen_id <> m.keeper_id
GROUP BY 1, 2
ON CONFLICT (screen_id, capability_id) DO UPDATE SET
    observed_count = screen_capability.observed_count + EXCLUDED.observed_count,
    fired_count = screen_capability.fired_count + EXCLUDED.fired_count;

--------------------------------------------------------------------------------
-- 5. screen_transition — 끝점을 옮기고, 자기 자신으로 접힌 전이는 지운다
--------------------------------------------------------------------------------
-- 두 화면이 한 화면이 되면 그 둘 사이의 전이는 **전이가 아니다.** 런타임도 그것을 남기지 않는다
-- (`ScreenObservationService.record` 의 `fromScreenId != screenId`). 실측의 29행은 사실상 하나의
-- 화면을 오간 기록이라, 여기서 지워지는 것이 대부분이고 그게 맞다.
--
-- 접힌 전이는 늘 씬 안이다. 묶음이 씬 단위(`PARTITION BY r.scene_id`)라 씬을 넘는 전이는 두 끝이
-- 서로 다른 씬에 있어 절대 같은 keeper 로 접히지 않는다. 그래서 `scene_edge` 가 매달린 전이가
-- 이 절에서 사라지는 일은 없다.
DROP TABLE IF EXISTS v58_transition_merge;
CREATE TEMP TABLE v58_transition_merge AS
SELECT t.id,
       t.capability_id,
       mf.keeper_id AS from_keeper,
       mt.keeper_id AS to_keeper,
       min(t.id) OVER (
           PARTITION BY mf.keeper_id, mt.keeper_id, coalesce(t.capability_id, -1)
       ) AS keeper_id
FROM screen_transition t
JOIN v58_screen_merge mf ON mf.screen_id = t.from_screen_id
JOIN v58_screen_merge mt ON mt.screen_id = t.to_screen_id
WHERE mf.keeper_id <> mt.keeper_id;

-- 합쳐질 전이의 관측 수를 대표 전이에 얹는다.
UPDATE screen_transition t
SET observed_count = t.observed_count + folded.extra
FROM (
    SELECT tm.keeper_id, sum(st.observed_count) AS extra
    FROM v58_transition_merge tm
    JOIN screen_transition st ON st.id = tm.id
    WHERE tm.id <> tm.keeper_id
    GROUP BY 1
) folded
WHERE t.id = folded.keeper_id;

-- `scene_edge` 가 가리키던 전이가 대표가 아니면 대표로 옮긴다. FK 가 `ON DELETE SET NULL` 이라
-- 옮기지 않고 지우면 "어느 관측이 이 간선을 처음 검증했나" 가 소리 없이 null 이 된다.
UPDATE scene_edge e
SET first_observed_transition_id = tm.keeper_id
FROM v58_transition_merge tm
WHERE e.first_observed_transition_id = tm.id
  AND tm.id <> tm.keeper_id;

-- 대표가 아닌 전이와 자기 자신으로 접힌 전이를 지운다.
DELETE FROM screen_transition t
WHERE t.id NOT IN (SELECT keeper_id FROM v58_transition_merge);

-- 남은 대표의 끝점을 남길 화면으로 옮긴다. 지우기를 먼저 한 뒤라야
-- `uk_screen_transition_auto` 가 중간 상태에서 걸리지 않는다.
UPDATE screen_transition t
SET from_screen_id = tm.from_keeper,
    to_screen_id = tm.to_keeper
FROM v58_transition_merge tm
WHERE t.id = tm.keeper_id
  AND (t.from_screen_id <> tm.from_keeper OR t.to_screen_id <> tm.to_keeper);

--------------------------------------------------------------------------------
-- 6. 화면을 논리참조로 드는 표들
--------------------------------------------------------------------------------
UPDATE capability_observation o
SET screen_id = m.keeper_id
FROM v58_screen_merge m
WHERE o.screen_id = m.screen_id
  AND m.screen_id <> m.keeper_id;

-- `knowledge_anchor` 는 FK 가 없고(V55) `uq_knowledge_anchor_screen` 이 걸려 있다. 옮기면 같은
-- 지식이 같은 화면을 두 번 가리키게 되는 쌍이 생기므로, 그 쌍은 가장 오래된 것만 남긴다.
WITH remapped AS (
    SELECT a.id,
           row_number() OVER (
               PARTITION BY a.knowledge_id, a.scene_name, m.keeper_id ORDER BY a.id
           ) AS rank_in_group
    FROM knowledge_anchor a
    JOIN v58_screen_merge m ON m.screen_id = a.screen_id
)
DELETE FROM knowledge_anchor a
USING remapped r
WHERE a.id = r.id AND r.rank_in_group > 1;

UPDATE knowledge_anchor a
SET screen_id = m.keeper_id
FROM v58_screen_merge m
WHERE a.screen_id = m.screen_id
  AND m.screen_id <> m.keeper_id;

--------------------------------------------------------------------------------
-- 7. 남길 행의 열을 채우고, 나머지를 지우고, 새 discriminator 를 쓴다
--------------------------------------------------------------------------------
-- `array_agg(... ORDER BY s.id) FILTER (...)` 의 첫 원소가 곧 "남길 행이 비어 있을 때만 다른 행의
-- 값으로 채운다" 이다 — 남길 행이 id 가 가장 작으므로 그 값이 비어 있지 않으면 그것이 첫 원소다.
-- `image_captured_at` 은 `image_object_key` 를 준 행에서 함께 가져온다. 둘은 한 캡처의 두 칸이라
-- 따로 고르면 이미지와 시각이 어긋난다.
DROP TABLE IF EXISTS v58_screen_group;
CREATE TEMP TABLE v58_screen_group AS
SELECT m.keeper_id,
       sum(s.observed_count) AS observed_count,
       (array_agg(s.name ORDER BY s.id) FILTER (WHERE s.name IS NOT NULL))[1] AS name,
       (array_agg(s.image_object_key ORDER BY s.id) FILTER (WHERE s.image_object_key IS NOT NULL))[1] AS image_object_key,
       (array_agg(s.image_captured_at ORDER BY s.id) FILTER (WHERE s.image_object_key IS NOT NULL))[1] AS image_captured_at
FROM screen s
JOIN v58_screen_merge m ON m.screen_id = s.id
GROUP BY m.keeper_id;

DELETE FROM screen s
USING v58_screen_merge m
WHERE s.id = m.screen_id
  AND m.screen_id <> m.keeper_id;

UPDATE screen s
SET observed_count = g.observed_count,
    name = g.name,
    image_object_key = g.image_object_key,
    image_captured_at = g.image_captured_at,
    discriminator = r.discriminator
FROM v58_screen_group g
JOIN v58_screen_rewrite r ON r.screen_id = g.keeper_id
WHERE s.id = g.keeper_id;

DROP TABLE v58_screen_group;
DROP TABLE v58_transition_merge;
DROP TABLE v58_screen_merge;
DROP TABLE v58_screen_rewrite;
