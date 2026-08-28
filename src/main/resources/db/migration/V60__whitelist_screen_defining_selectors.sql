-- ARTEL-654: 화면 판정에 쓸 selector 를 씬마다 목록으로 두고, 목록 밖은 무시한다.
--
-- ## 기본값을 뒤집는다
--
-- 지금은 화면에 있는 조작 가능한 오브젝트가 전부 `discriminator` 에 들어간다. 무엇을 뺄지를
-- 기계가 규칙으로 정하는 방식인데, 그 기본값이 **넣는 쪽**이라 처음 보는 오브젝트는 규칙이 없어서
-- 그냥 들어간다. 그래서 런타임에 생기는 것이 하나 뜰 때마다 배열이 바뀌고 새 화면이 된다.
--
-- 실측(`artel_integration`, 2026-08-28): `screen` 30행 중 29행이 `TurnBattleScene` 의 것이었고
-- `ScreenObservationService.MAX_SCREENS_PER_SCENE` 32 코앞이었다. 게임이 오브젝트 이름에 카운터를
-- 넣으면(`agent(1)` · `agent(2)`) 이름마다 새 화면이라 끝이 없다.
--
-- 무엇을 뺄지 기계가 알아내는 방향으로 후보 셋을 재봤고 셋 다 반례가 나왔다.
--
-- | 후보 | 반례 |
-- |---|---|
-- | 한 관측 안의 인스턴스 수 | 이름에 카운터가 든 게임에서 안 잡히고, 이름이 같은 형제 컨트롤 둘(확인 버튼과 취소 버튼)을 잘못 뺀다 |
-- | 여러 관측에 걸친 등장 횟수 | 지나고 나서만 알 수 있고, 이름이 매번 바뀌면 표가 플레이 길이만큼 자란다 |
-- | 조작 없이 변한 것은 안 가른다 | 로딩 화면에서 게임 화면으로 넘어가는 것이 반례다. 아무것도 안 눌러도 바뀌고 그것은 명백히 다른 화면이다 |
--
-- 이름만 보고 깎는 것도 위험하다. 실측에서 실제로 화면을 가른 넷 중 둘이
-- `CombineSystem[7]/CombineZone[1]/Zone1[0]` 과 `Zone2[1]` 이다 — 끝자리가 숫자지만 서로 다른
-- 오브젝트다.
--
-- 이 SDK 는 Unity 게임 전반에 붙는 것이 목표라 게임 하나에 맞춘 임계값을 깎는 방향으로는 갈 수
-- 없다. 그래서 목록을 둔다 — 게임마다 답이 달라도 코드가 아니라 목록이 다르다. 목록이 유한하므로
-- 표도 플레이 길이만큼 자라지 않는다.
--
-- ## 목록이 빈 씬은 화면이 하나다. 오류가 아니다
--
-- 가를 근거가 하나도 없는데 가르는 것보다 맞다. 씨앗(3절)이 그 상태를 드물게 만들지만, 씨앗이
-- 하나도 없는 씬은 정상적으로 화면 하나로 산다.
--
-- ## 목록에 넣는 것은 소급되지 않는다
--
-- 항목을 나중에 더해도 이미 뭉쳐 있던 과거 화면은 안 갈린다. 그 selector 가 애초에 `discriminator`
-- 에 안 들어갔으니 기록이 없어서 복원할 수 없다. **다음 관측부터** 갈린다. 반대 방향(목록에서
-- 빼는 것)은 소급해서 접을 수 있고 그것은 ARTEL-655 다.
--
-- ⚠️ 번호: 이 브랜치가 열려 있는 동안 develop 이 V57 을 받았다. 그래서 처음 잡았던 V56 · V58 은
-- 둘 다 develop 최고 번호 위가 아니거나(V56) 그 자리를 다른 브랜치와 다투었고(V58 은 ARTEL-649
-- 가 들고 있었다 — 그 PR 은 닫혔지만 번호는 다시 쓰지 않는다), 지금의 V59 · V60 으로 함께 올렸다.
--
-- **둘의 순서가 붙어 있어서 함께 올렸다.** 4절의 소급 접기는 `uk_screen_discriminator` 가 이미
-- 걸려 있다는 전제로 쓰였다(그 절의 주석). V59 만 올리면 그 유니크가 이 파일 **뒤로** 가서 전제가
-- 뒤집힌다.
--
-- 비어 보이는 번호(V47 · V50 · V52 · V53 · V56 · V58)는 쓰지 않는다 — 더 높은 번호를 이미
-- 적용한 DB 에서 out-of-order 로 걸린다(`docs/flyway-migrations.md` 의 "Tangle").

--------------------------------------------------------------------------------
-- 1. scene_screen_selector — 화면 판정에 쓸 selector 목록
--------------------------------------------------------------------------------
-- **씬 옆에 둔다.** 어떤 selector 가 화면을 식별하는가는 씬의 구조적 사실이고, 그 씬의 어느
-- 화면에서 보든 같은 답이어야 한다. 화면마다 따로 들면 같은 selector 가 화면에 따라 다르게
-- 판정되어 규칙이 화면마다 갈린다.
--
-- **프로세스 메모리에 두지 않는다.** `ScreenObservationService.folds` 는 재시작하면 사라지고 서버가
-- 두 대면 각자 자기 것을 본다. 그러면 같은 화면이 런마다·재시작마다 다른 `discriminator` 로 앉아,
-- V59 가 막으려던 바로 그 분열이 규칙 쪽에서 다시 열린다.
--
-- ## 정규표현식을 저장하지 않는다
--
-- 이 표는 `discriminator` 를 만드는 Kotlin(`ScreenSelectorWhitelist`)과 소급 처리를 하는 SQL
-- (2절의 `screen_defining_selector`, 그리고 ARTEL-655) 양쪽에서 평가된다. `java.util.regex` 와
-- POSIX ARE 는 다르고, 한쪽에서만 맞는 항목이 하나 생기면 같은 화면이 두 `discriminator` 로
-- 갈린다 — `uk_screen_discriminator`(V59) 가 막으려던 분열이 목록 쪽에서 다시 열린다.
--
-- 두 번째 이유는 항목을 LLM 이 쓴다는 것이다. 잘못된 정확 문자열은 아무것에도 안 맞고 끝나지만,
-- 잘못된 정규식은 **전부** 맞고 그것이 조용하다.
--
-- 정규식이 필요하면 저장이 아니라 작성 시점에 둔다 — 제안된 정규식을 지금까지 본 selector 에
-- 펼치고, 저장되는 것은 펼쳐진 정확 항목이다.
CREATE TABLE IF NOT EXISTS scene_screen_selector (
    id BIGSERIAL PRIMARY KEY,
    scene_id BIGINT NOT NULL REFERENCES scene (id) ON DELETE CASCADE,

    -- 이 항목이 가리키는 대상이 셋 중 무엇인가.
    --   selector — selector 원문 하나. 정확히 같은 문자열만 맞는다
    --   path     — 형제 index 를 지운 경로 하나. `Card(Clone)[37]` 과 `Card(Clone)[38]` 이 한 항목에 맞는다
    --   subtree  — 그 경로와 그 아래 전부. **마디 경계**로만 맞는다(값이 같거나 `pattern/` 로 시작)
    -- `contains` 로 하지 않는 이유가 실측에 있다 — `CombineSystem/CombineZone/Zone1` 이
    -- `SomeZone1Extra` 에 걸린다.
    match_kind VARCHAR(16) NOT NULL,

    -- 맞대 볼 정확 문자열. 정규식이 아니다(위 주석).
    -- 길이는 `capability.control_selector` 와 같이 잡는다 — 씨앗이 그 칸에서 온다.
    pattern VARCHAR(512) NOT NULL,

    -- 이 항목을 누가 썼나. 정적 분석 < agent < 사람 순으로 이긴다.
    source VARCHAR(16) NOT NULL,

    -- 이 대상이 화면을 식별하는가. `false` 는 **명시적 제외**다.
    -- 목록에 없는 것은 애초에 안 들어가므로, `false` 행이 필요한 자리는 넓은 항목에 구멍을 낼
    -- 때뿐이다 — `subtree` 로 패널을 통째로 넣고 그 아래 하나만 빼는 경우. 이 칸이 없으면
    -- "사람이 agent 를 이긴다" 가 표현할 것이 없어진다.
    screen_defining BOOLEAN NOT NULL DEFAULT TRUE,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_scene_screen_selector_match CHECK (match_kind IN ('selector', 'path', 'subtree')),
    CONSTRAINT ck_scene_screen_selector_source CHECK (source IN ('static-analysis', 'agent', 'human')),

    -- 출처가 키에 든다. 사람이 agent 의 판단을 **덮는 것이 아니라 이기는 것**이라, 두 행이 함께
    -- 남아야 사람 항목을 지웠을 때 agent 의 판단이 되살아난다.
    CONSTRAINT uk_scene_screen_selector UNIQUE (scene_id, match_kind, pattern, source)
);

COMMENT ON TABLE scene_screen_selector IS
    '이 씬에서 화면을 식별하는 selector 목록. discriminator 는 이 목록에 맞는 것만 담는다.';
COMMENT ON COLUMN scene_screen_selector.match_kind IS
    'selector(원문 하나) | path(형제 index 를 지운 경로) | subtree(그 경로와 그 아래 전부, 마디 경계).';
COMMENT ON COLUMN scene_screen_selector.pattern IS
    '맞대 볼 정확 문자열. 정규식이 아니다 — Kotlin 과 SQL 이 같은 결과를 내야 한다.';
COMMENT ON COLUMN scene_screen_selector.source IS
    'static-analysis | agent | human. 이 순서로 뒤가 앞을 이긴다.';
COMMENT ON COLUMN scene_screen_selector.screen_defining IS
    'false 는 명시적 제외. 넓은 항목에 구멍을 낼 때 쓴다.';

--------------------------------------------------------------------------------
-- 2. screen_defining_selector — 목록 평가를 SQL 에 한 벌만 둔다
--------------------------------------------------------------------------------
-- 이 함수가 `ScreenSelectorWhitelist.defines` 와 **같은 답을 내야 한다.** 두 벌이 갈리면 소급 처리가
-- 접은 화면과 런타임이 앉히는 화면이 다른 규칙을 따르게 되어, 합쳐 놓은 행 옆에 옛 모양의 행이
-- 다시 쌓인다. SQL 쪽 정의를 여기 하나로 모으는 것이 그 위험을 줄이는 자리다 — 4절의 소급 접기도,
-- ARTEL-655 의 소급 접기도 이 함수를 쓴다.
--
-- ## 우선순위
--
-- 맞는 항목이 여럿이면 **출처가 먼저**, 그 다음 좁은 것이 이긴다.
--   1. 출처 — human > agent > static-analysis
--   2. 대상의 좁기 — selector > path > subtree
--   3. `subtree` 끼리는 긴 pattern 이 좁다 (`A/B` 가 `A` 를 이긴다)
--   4. 그래도 같으면 나중에 쓴 행. 여기까지 오는 경우는 없다 — `uk_scene_screen_selector` 가
--      (kind, pattern, source) 를 하나로 묶고, 길이가 같은 서로 다른 `subtree` pattern 둘이 한
--      경로에 동시에 맞을 수는 없다. 그래도 순서를 못박는 것은 Kotlin 과 답이 갈릴 자리를 남기지
--      않기 위해서다
--
-- 맞는 항목이 하나도 없으면 `FALSE` — **목록 밖은 무시한다**가 이 한 줄이다.
--
-- `LIKE` 를 쓰지 않는다. `%` 와 `_` 가 메타문자라 `Map_scene` 같은 이름이 아무 글자에나 맞는다.
-- `starts_with` 는 순수 문자열 비교라 Kotlin 의 `String.startsWith` 와 정확히 같다.
--
-- 형제 index 를 지우는 정규식은 `ScreenFold.SIBLING_INDEX` 와 같은 것이어야 한다. 이것은 코드에
-- 박힌 고정 정규식이라 표에 저장되는 항목과 다르다 — 저장하지 않기로 한 것은 행 쪽이다.
CREATE OR REPLACE FUNCTION screen_defining_selector(p_scene_id BIGINT, p_selector TEXT)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
AS $$
    SELECT COALESCE(
        (
            SELECT r.screen_defining
            FROM scene_screen_selector r
            WHERE r.scene_id = p_scene_id
              AND CASE r.match_kind
                      WHEN 'selector' THEN r.pattern = p_selector
                      WHEN 'path' THEN r.pattern = regexp_replace(p_selector, '\[[0-9]+\]', '', 'g')
                      WHEN 'subtree' THEN
                          r.pattern = regexp_replace(p_selector, '\[[0-9]+\]', '', 'g')
                          OR starts_with(regexp_replace(p_selector, '\[[0-9]+\]', '', 'g'), r.pattern || '/')
                      ELSE FALSE
                  END
            ORDER BY
                CASE r.source
                    WHEN 'human' THEN 3
                    WHEN 'agent' THEN 2
                    WHEN 'static-analysis' THEN 1
                    ELSE 0
                END DESC,
                CASE r.match_kind
                    WHEN 'selector' THEN 3
                    WHEN 'path' THEN 2
                    WHEN 'subtree' THEN 1
                    ELSE 0
                END DESC,
                length(r.pattern) DESC,
                r.id DESC
            LIMIT 1
        ),
        FALSE
    );
$$;

COMMENT ON FUNCTION screen_defining_selector(BIGINT, TEXT) IS
    '이 selector 가 이 씬의 화면을 식별하는가. ScreenSelectorWhitelist.defines 와 같은 답을 내야 한다.';

--------------------------------------------------------------------------------
-- 3. 씨앗 — capability.control_selector
--------------------------------------------------------------------------------
-- 목록이 비면 씬 전체가 화면 하나라서 초반 런의 지도가 쓸모없다. `capability.control_selector` 는
-- 정적 분석이 코드에서 뽑은 것이라 런타임에 스폰된 이름이 아니고, **정의상 조작할 수 있는
-- 것들**이다. 그것을 초기 목록으로 쓴다. 실측 빌드에서 capability 472 개 중 `control_selector` 가
-- 있는 것은 24 개로 얇지만 0 보다 낫다.
--
-- ## 왜 `path` 가 아니라 `selector` 인가
--
-- `control_selector` 는 `PulseObject.selector` 와 같은 표기다(그 KDoc). 실측에서도
-- `CombineSystem[7]/CombineButton[0]` 이 양쪽에 글자 그대로 있었고, ARTEL-453 의 코드도 이미 정확
-- 일치로 맞대 보고 있다. `path` 로 넓히면 이름이 같은 형제 컨트롤(확인 버튼과 취소 버튼)이 한
-- 항목에 맞아, 한쪽만 켜진 화면과 둘 다 켜진 화면을 가르는 근거가 흐려진다.
--
-- 대가: 빌드가 바뀌어 계층 index 가 흔들리면 씨앗이 아무것에도 안 맞고 그 씬은 화면 하나가 된다.
-- 그것은 눈에 보이고 `path` 항목을 더하면 복구된다. 반대쪽 실패(잘못 뭉친 화면)는 조용하다.
--
-- 런타임도 같은 씨앗을 심는다(`ScreenObservationService.seededWhitelist`). 이 절은 **이미 쌓인**
-- capability 를 위한 것이고, 다음 빌드의 새 capability 는 그쪽이 맡는다.
INSERT INTO scene_screen_selector (scene_id, match_kind, pattern, source, screen_defining)
SELECT DISTINCT c.scene_id, 'selector', c.control_selector, 'static-analysis', TRUE
FROM capability c
WHERE c.control_selector IS NOT NULL
  AND c.control_selector <> ''
ON CONFLICT (scene_id, match_kind, pattern, source) DO NOTHING;

--------------------------------------------------------------------------------
-- 4. 이미 쌓인 화면을 새 규칙으로 다시 접는다
--------------------------------------------------------------------------------
-- 규칙만 바꾸고 행을 두면 한 씬 안에 **옛 규칙으로 만든 행과 새 규칙으로 만들 행이 섞이고**,
-- `observed_count` 가 그 둘에 쪼개져 "몇 번 지나갔나"가 양쪽 다 틀린 값이 된다.
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
-- 목록을 전부 적용한 결과가 `[]` 가 되는 화면이 있을 수 있다. 그 씬에 씨앗이 하나도 없었다는
-- 뜻이고, 그때 그 씬의 화면은 하나다 — 오류가 아니다(1절).
DROP TABLE IF EXISTS v60_screen_rewrite;
CREATE TEMP TABLE v60_screen_rewrite AS
SELECT s.id AS screen_id,
       s.scene_id,
       COALESCE(
           (
               SELECT jsonb_agg(e.el ORDER BY e.el ->> 'selector')
               FROM jsonb_array_elements(s.discriminator) AS e(el)
               WHERE screen_defining_selector(s.scene_id, e.el ->> 'selector')
           ),
           '[]'::jsonb
       ) AS discriminator
FROM screen s;

DROP TABLE IF EXISTS v60_screen_merge;
CREATE TEMP TABLE v60_screen_merge AS
SELECT r.screen_id,
       min(r.screen_id) OVER (PARTITION BY r.scene_id, r.discriminator) AS keeper_id
FROM v60_screen_rewrite r;

CREATE INDEX ON v60_screen_merge (screen_id);

--------------------------------------------------------------------------------
-- 5. screen_capability — 참조를 남길 행으로 옮기고 관측 수를 합친다
--------------------------------------------------------------------------------
-- 지우는 것은 8절의 `DELETE FROM screen` 이 CASCADE 로 한다.
INSERT INTO screen_capability (screen_id, capability_id, observed_count, fired_count)
SELECT m.keeper_id, sc.capability_id, sum(sc.observed_count), sum(sc.fired_count)
FROM screen_capability sc
JOIN v60_screen_merge m ON m.screen_id = sc.screen_id
WHERE m.screen_id <> m.keeper_id
GROUP BY 1, 2
ON CONFLICT (screen_id, capability_id) DO UPDATE SET
    observed_count = screen_capability.observed_count + EXCLUDED.observed_count,
    fired_count = screen_capability.fired_count + EXCLUDED.fired_count;

--------------------------------------------------------------------------------
-- 6. screen_transition — 끝점을 옮기고, 자기 자신으로 접힌 전이는 지운다
--------------------------------------------------------------------------------
-- 두 화면이 한 화면이 되면 그 둘 사이의 전이는 **전이가 아니다.** 런타임도 그것을 남기지 않는다
-- (`ScreenObservationService.record` 의 `fromScreenId != screenId`). 실측의 29행은 사실상 같은
-- 화면들을 오간 기록이라, 여기서 지워지는 것이 대부분이고 그게 맞다.
--
-- 접힌 전이는 늘 씬 안이다. 묶음이 씬 단위(`PARTITION BY r.scene_id`)라 씬을 넘는 전이는 두 끝이
-- 서로 다른 씬에 있어 절대 같은 keeper 로 접히지 않는다. 그래서 `scene_edge` 가 매달린 전이가
-- 이 절에서 사라지는 일은 없다.
DROP TABLE IF EXISTS v60_transition_merge;
CREATE TEMP TABLE v60_transition_merge AS
SELECT t.id,
       t.capability_id,
       mf.keeper_id AS from_keeper,
       mt.keeper_id AS to_keeper,
       min(t.id) OVER (
           PARTITION BY mf.keeper_id, mt.keeper_id, coalesce(t.capability_id, -1)
       ) AS keeper_id
FROM screen_transition t
JOIN v60_screen_merge mf ON mf.screen_id = t.from_screen_id
JOIN v60_screen_merge mt ON mt.screen_id = t.to_screen_id
WHERE mf.keeper_id <> mt.keeper_id;

-- 합쳐질 전이의 관측 수를 대표 전이에 얹는다.
UPDATE screen_transition t
SET observed_count = t.observed_count + folded.extra
FROM (
    SELECT tm.keeper_id, sum(st.observed_count) AS extra
    FROM v60_transition_merge tm
    JOIN screen_transition st ON st.id = tm.id
    WHERE tm.id <> tm.keeper_id
    GROUP BY 1
) folded
WHERE t.id = folded.keeper_id;

-- `scene_edge` 가 가리키던 전이가 대표가 아니면 대표로 옮긴다. FK 가 `ON DELETE SET NULL` 이라
-- 옮기지 않고 지우면 "어느 관측이 이 간선을 처음 검증했나" 가 소리 없이 null 이 된다.
UPDATE scene_edge e
SET first_observed_transition_id = tm.keeper_id
FROM v60_transition_merge tm
WHERE e.first_observed_transition_id = tm.id
  AND tm.id <> tm.keeper_id;

-- 대표가 아닌 전이와 자기 자신으로 접힌 전이를 지운다.
DELETE FROM screen_transition t
WHERE t.id NOT IN (SELECT keeper_id FROM v60_transition_merge);

-- 남은 대표의 끝점을 남길 화면으로 옮긴다. 지우기를 먼저 한 뒤라야
-- `uk_screen_transition_auto` 가 중간 상태에서 걸리지 않는다.
UPDATE screen_transition t
SET from_screen_id = tm.from_keeper,
    to_screen_id = tm.to_keeper
FROM v60_transition_merge tm
WHERE t.id = tm.keeper_id
  AND (t.from_screen_id <> tm.from_keeper OR t.to_screen_id <> tm.to_keeper);

--------------------------------------------------------------------------------
-- 7. 화면을 논리참조로 드는 표들
--------------------------------------------------------------------------------
UPDATE capability_observation o
SET screen_id = m.keeper_id
FROM v60_screen_merge m
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
    JOIN v60_screen_merge m ON m.screen_id = a.screen_id
)
DELETE FROM knowledge_anchor a
USING remapped r
WHERE a.id = r.id AND r.rank_in_group > 1;

UPDATE knowledge_anchor a
SET screen_id = m.keeper_id
FROM v60_screen_merge m
WHERE a.screen_id = m.screen_id
  AND m.screen_id <> m.keeper_id;

--------------------------------------------------------------------------------
-- 8. 남길 행의 열을 채우고, 나머지를 지우고, 새 discriminator 를 쓴다
--------------------------------------------------------------------------------
-- 묶음마다 id 가 가장 작은 행을 남긴다. 그 행이 가장 먼저 본 행이므로 `first_seen_qa_run_id` 도
-- 그대로 맞는다.
--
-- `array_agg(... ORDER BY s.id) FILTER (...)` 의 첫 원소가 곧 "남길 행이 비어 있을 때만 다른 행의
-- 값으로 채운다" 이다 — 남길 행이 id 가 가장 작으므로 그 값이 비어 있지 않으면 그것이 첫 원소다.
-- `image_captured_at` 은 `image_object_key` 를 준 행에서 함께 가져온다. 둘은 한 캡처의 두 칸이라
-- 따로 고르면 이미지와 시각이 어긋난다.
DROP TABLE IF EXISTS v60_screen_group;
CREATE TEMP TABLE v60_screen_group AS
SELECT m.keeper_id,
       sum(s.observed_count) AS observed_count,
       (array_agg(s.name ORDER BY s.id) FILTER (WHERE s.name IS NOT NULL))[1] AS name,
       (array_agg(s.image_object_key ORDER BY s.id) FILTER (WHERE s.image_object_key IS NOT NULL))[1] AS image_object_key,
       (array_agg(s.image_captured_at ORDER BY s.id) FILTER (WHERE s.image_object_key IS NOT NULL))[1] AS image_captured_at
FROM screen s
JOIN v60_screen_merge m ON m.screen_id = s.id
GROUP BY m.keeper_id;

DELETE FROM screen s
USING v60_screen_merge m
WHERE s.id = m.screen_id
  AND m.screen_id <> m.keeper_id;

UPDATE screen s
SET observed_count = g.observed_count,
    name = g.name,
    image_object_key = g.image_object_key,
    image_captured_at = g.image_captured_at,
    discriminator = r.discriminator
FROM v60_screen_group g
JOIN v60_screen_rewrite r ON r.screen_id = g.keeper_id
WHERE s.id = g.keeper_id;

DROP TABLE v60_screen_group;
DROP TABLE v60_transition_merge;
DROP TABLE v60_screen_merge;
DROP TABLE v60_screen_rewrite;
