-- ARTEL-655: 목록에 없는 selector 를 물어보고, 답이 오면 같아지는 화면을 접는다.
--
-- ## 왜 물어보나
--
-- ARTEL-654(V60) 가 `discriminator` 의 기본값을 **무시하는 쪽**으로 뒤집었다. 그것이 화면 폭발을
-- 멈췄지만(실측 `TurnBattleScene` 29행 → 2행) 구멍을 하나 남겼다. 목록은 `capability.control_selector`
-- 씨앗으로만 차고 **아무것도 목록을 늘리지 않는다.** 실측에서 3 이어야 할 씬이 2 이고, 그것을 고칠
-- 경로가 없다.
--
-- 틀리는 방향이 문제다. 목록이 얇으면 서로 다른 두 화면이 한 행으로 앉고, 뭉치는 것은 **조용하다.**
-- 반대쪽(잘게 갈림)은 시끄럽기라도 하다.
--
-- 무엇이 화면을 식별하는지를 기계가 알아내는 방향은 V60 머리말의 표대로 후보 셋을 다 재봤고 셋 다
-- 반례가 나왔다. 그래서 답은 **추정이 아니라 질문**이다.
--
-- ## 답은 화면 판정이 아니라 목록 항목이다
--
-- "이 화면은 3번과 같다" 로 답하면 카드를 서른 번째 뽑을 때 또 물어야 한다. "이 selector 가 화면을
-- 가른다/안 가른다" 로 답해야 한 번 묻고 끝난다. 저장되는 것은 `scene_screen_selector` 항목이고,
-- 그 항목은 V60 이 정한 대로 **정규식이 아니라 정확 문자열**이다.

--------------------------------------------------------------------------------
-- 1. qa_log 타입 — 새 프레임 넷
--------------------------------------------------------------------------------
-- 게이트가 둘이라는 것을 잊지 말 것(V57 주석). `QaLogService.TYPES` 의 `require` 와 이 CHECK 가
-- 같은 목록을 각자 들고 있고, 한쪽만 열면 통과한 값이 INSERT 에서 죽는다. 어긋나면
-- `QaLogTypeGateParityTest` 가 실패한다.
--
--   SCREEN_SELECTOR_PROPOSAL  목록에 없는 selector 를 물어본다 (ORCHE_TO_AGENT)
--   SCREEN_SELECTOR_VERDICT   그 제안에 대한 답 (AGENT_TO_ORCHE)
--   SCREEN_SELECTOR_RULE      QA agent 의 tool 이 목록을 고친다 (AGENT_TO_ORCHE)
--   SCREEN_SELECTOR_RESULT    위 둘의 답 — 받아들인 항목과 거절한 항목 (ORCHE_TO_AGENT)
ALTER TABLE qa_log DROP CONSTRAINT IF EXISTS qa_log_type_check;

ALTER TABLE qa_log ADD CONSTRAINT qa_log_type_check CHECK (
    type IN (
        'LOG',
        'ACTION',
        'ACTION_RESULT',
        'GAME_STATE',
        'STATUS',
        'ERROR',
        'CHAT',
        'SCREENSHOT',
        'PULSE',
        'TOOL',
        'TOOL_RESULT',
        'SCREEN_SELECTOR_PROPOSAL',
        'SCREEN_SELECTOR_VERDICT',
        'SCREEN_SELECTOR_RULE',
        'SCREEN_SELECTOR_RESULT'
    )
);

--------------------------------------------------------------------------------
-- 2. screen_selector_proposal — 한 번 물어본 것은 다시 묻지 않는다
--------------------------------------------------------------------------------
-- 이 표가 없으면 카드를 뽑을 때마다 제안이 하나씩 나간다. 실측 `TurnBattleScene` 의 한 `pulse` 에
-- selector 가 62 개 있었고 그중 목록 밖이 59 개다 — `pulse` 는 초당 여러 번 온다.
--
-- **프로세스 메모리에 두지 않는다.** 재시작하면 사라지고 서버가 두 대면 각자 자기 것을 본다.
-- 그러면 "한 번만 묻는다" 가 재시작마다 거짓이 되고, 답할 agent 는 같은 질문을 계속 받는다.
-- `ScreenObservationService.folds` 를 캐시로 쓰지 않기로 한 것과 같은 판단이다.
--
-- 답이 와도 행은 남는다. 상태만 `answered` 로 바뀐다 — 지우면 "물어본 적 있다" 가 사라져 다시
-- 묻게 되고, 그것이 이 표가 막으려던 바로 그것이다.
CREATE TABLE IF NOT EXISTS screen_selector_proposal (
    id BIGSERIAL PRIMARY KEY,
    scene_id BIGINT NOT NULL REFERENCES scene (id) ON DELETE CASCADE,

    -- 무엇 때문에 물어보나.
    --   unknown-selector — 목록에도 제외에도 없는 selector 를 `pulse` 에서 봤다
    --   scene-screen-cap — 이 씬의 화면이 MAX_SCREENS_PER_SCENE 에 닿았다. 목록이 너무 잘다는
    --                      뜻이므로 무엇을 뺄지 묻는다
    reason VARCHAR(32) NOT NULL,

    -- 물어본 대상. `unknown-selector` 는 selector 원문 하나이고, `scene-screen-cap` 은 대상이
    -- 씬 자체라 빈 문자열이다. NULL 로 두지 않는 이유는 아래 유니크가 NULL 을 서로 다른 값으로
    -- 보아 같은 씬에 상한 제안이 무한히 쌓이기 때문이다.
    selector VARCHAR(512) NOT NULL,

    -- outstanding — 나갔고 아직 답이 없다
    -- answered    — 답이 왔다. 그 답이 이 selector 를 다루지 않았어도 다시 묻지 않는다
    status VARCHAR(16) NOT NULL DEFAULT 'outstanding',

    -- 나간 프레임의 messageId. 답의 correlationId 가 이 값이고, 그것으로 어느 씬의 답인지 푼다.
    message_id VARCHAR(255),

    -- 어느 런이 물었나. 답이 늦게 와 런이 끝난 뒤에 도착해도 항목은 저장된다 — 목록은 런이
    -- 아니라 씬에 매달린 지식이다.
    asked_qa_run_id BIGINT,

    asked_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    answered_at TIMESTAMP WITH TIME ZONE,

    CONSTRAINT ck_screen_selector_proposal_reason
        CHECK (reason IN ('unknown-selector', 'scene-screen-cap')),
    CONSTRAINT ck_screen_selector_proposal_status
        CHECK (status IN ('outstanding', 'answered')),
    -- `unknown-selector` 는 대상이 있어야 하고 `scene-screen-cap` 은 없어야 한다. 이 검사가 없으면
    -- 빈 selector 를 가진 unknown 행이 씬 전체를 한 번 물어본 것으로 세어져 나머지가 영영 안 나간다.
    CONSTRAINT ck_screen_selector_proposal_target CHECK (
        (reason = 'unknown-selector' AND selector <> '')
        OR (reason = 'scene-screen-cap' AND selector = '')
    ),

    -- **한 번만 묻는다** 가 이 한 줄이다. 코드가 "이미 물었나" 를 판정하면 그 검사는 경합에 진다 —
    -- 같은 `pulse` 를 두 서버가 보면 둘 다 아직 없다고 읽는다.
    CONSTRAINT uk_screen_selector_proposal UNIQUE (scene_id, selector)
);

CREATE INDEX IF NOT EXISTS idx_screen_selector_proposal_message
    ON screen_selector_proposal (message_id);

COMMENT ON TABLE screen_selector_proposal IS
    '목록에 없는 selector 를 물어본 기록. 한 번 물어본 (scene, selector) 는 다시 묻지 않는다.';
COMMENT ON COLUMN screen_selector_proposal.reason IS
    'unknown-selector(목록 밖 selector) | scene-screen-cap(화면 상한에 닿아 목록을 좁혀야 한다).';
COMMENT ON COLUMN screen_selector_proposal.selector IS
    '물어본 selector 원문. scene-screen-cap 은 대상이 씬 자체라 빈 문자열이다.';

--------------------------------------------------------------------------------
-- 3. fold_scene_screens — 목록을 적용하고 같아지는 화면을 접는다
--------------------------------------------------------------------------------
-- **V60 의 4~8 절을 씬 하나로 좁혀 함수로 옮긴 것이다.** 같은 일을 하는 정의를 두 벌 두면 갈리고,
-- 갈리면 소급 처리가 접은 화면과 런타임이 앉히는 화면이 다른 규칙을 따르게 되어 합쳐 놓은 행 옆에
-- 옛 모양의 행이 다시 쌓인다. V60 은 이미 적용된 마이그레이션이라 고칠 수 없으므로, **앞으로
-- 접기가 필요한 자리는 전부 이 함수를 부른다.**
--
-- ## 도착 순서에 의존하지 않는다
--
-- 이 함수의 모양이 그 보장이다. 도착한 순서대로 두 행씩 합치는 것이 아니라
--
--   1. 목록을 적용해 모든 화면의 `discriminator` 를 다시 계산하고
--   2. 같아지는 것끼리 묶고
--   3. 묶음마다 하나로 접는다
--
-- 는 집합 연산이다. 입력은 (그 씬의 화면 전부, 지금의 목록) 둘뿐이고 호출 이력이 아니므로, 답이
-- 어떤 순서로 와도 마지막 호출이 같은 목록을 보면 같은 상태로 끝난다. 대표를 고르는 것도
-- `min(id)` 라 순서와 무관하다.
--
-- ## 접은 것은 다시 갈리지 않는다
--
-- 되돌릴 수 없다. 목록에서 빠진 selector 의 값은 `discriminator` 에서 지워지고, 그것이 이 함수가
-- 남기는 유일한 기록이라 나중에 그 selector 를 목록에 넣어도 복원할 재료가 없다. **다음 관측부터**
-- 갈린다. 여기를 고쳐 되돌리려 하기 전에 V60 1절의 "목록에 넣는 것은 소급되지 않는다" 를 읽을 것 —
-- 그 비대칭이 버그가 아니라 이 설계의 값이다.
--
-- 돌려주는 것은 사라진 화면 수다. 0 이면 아무것도 안 바뀌었다는 뜻이고, 부르는 쪽은 그것으로
-- `fold` 상태를 버릴지 정한다.
CREATE OR REPLACE FUNCTION fold_scene_screens(p_scene_id BIGINT)
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_folded INTEGER;
BEGIN
    -- 임시 표를 만들기 전에 `DROP IF EXISTS` 를 두지 않는다. 이 함수는 끝날 때 자기가 만든 것을
    -- 전부 지우고, 중간에 실패하면 트랜잭션이 통째로 되돌아 `CREATE TEMP TABLE` 도 사라진다.
    -- 방어로 넣었더니 부를 때마다 NOTICE 가 다섯 줄씩 로그를 채웠다.
    --
    -- 1) 목록을 적용한 `discriminator`. `screen_defining_selector`(V60 2절) 를 그대로 쓴다 —
    --    평가 규칙이 SQL 에 한 벌만 있어야 Kotlin 쪽과 갈릴 자리가 하나로 준다.
    CREATE TEMP TABLE fold_screen_rewrite AS
    SELECT s.id AS screen_id,
           COALESCE(
               (
                   SELECT jsonb_agg(e.el ORDER BY e.el ->> 'selector')
                   FROM jsonb_array_elements(s.discriminator) AS e(el)
                   WHERE screen_defining_selector(s.scene_id, e.el ->> 'selector')
               ),
               '[]'::jsonb
           ) AS discriminator
    FROM screen s
    WHERE s.scene_id = p_scene_id;

    -- 2) 같아지는 것끼리 묶고 묶음마다 가장 작은 id 를 남긴다. 그 행이 가장 먼저 본 행이므로
    --    `first_seen_qa_run_id` 도 그대로 맞다.
    CREATE TEMP TABLE fold_screen_merge AS
    SELECT r.screen_id,
           r.discriminator,
           min(r.screen_id) OVER (PARTITION BY r.discriminator) AS keeper_id
    FROM fold_screen_rewrite r;

    CREATE INDEX ON fold_screen_merge (screen_id);

    SELECT count(*) INTO v_folded
    FROM fold_screen_merge
    WHERE screen_id <> keeper_id;

    IF v_folded = 0 THEN
        -- 접힐 것이 없어도 `discriminator` 는 다시 써야 한다. 목록에서 빠진 selector 가 하나뿐인
        -- 화면에도 남아 있으면, 다음 관측이 만드는 값과 달라 같은 화면이 행 둘로 앉는다.
        UPDATE screen s
        SET discriminator = r.discriminator
        FROM fold_screen_rewrite r
        WHERE s.id = r.screen_id
          AND s.discriminator <> r.discriminator;

        DROP TABLE fold_screen_merge;
        DROP TABLE fold_screen_rewrite;
        RETURN 0;
    END IF;

    -- 3) screen_capability — 참조를 남길 행으로 옮기고 관측 수를 합친다.
    --    지우는 것은 6) 의 `DELETE FROM screen` 이 CASCADE 로 한다.
    INSERT INTO screen_capability (screen_id, capability_id, observed_count, fired_count)
    SELECT m.keeper_id, sc.capability_id, sum(sc.observed_count), sum(sc.fired_count)
    FROM screen_capability sc
    JOIN fold_screen_merge m ON m.screen_id = sc.screen_id
    WHERE m.screen_id <> m.keeper_id
    GROUP BY 1, 2
    ON CONFLICT (screen_id, capability_id) DO UPDATE SET
        observed_count = screen_capability.observed_count + EXCLUDED.observed_count,
        fired_count = screen_capability.fired_count + EXCLUDED.fired_count;

    -- 4) screen_transition — 끝점을 옮기고, 자기 자신으로 접힌 전이는 지운다.
    --
    --    두 화면이 한 화면이 되면 그 둘 사이의 전이는 **전이가 아니다.** 런타임도 그것을 남기지
    --    않는다(`ScreenObservationService.record` 의 `fromScreenId != screenId`).
    --
    --    V60 과 달리 한 씬만 접으므로 씬을 넘는 전이는 한쪽 끝만 이 묶음에 든다. 그래서 양쪽을
    --    `LEFT JOIN` 하고 `COALESCE` 로 메운다 — 안쪽만 조인하면 그 전이들이 후보에서 빠져 끝점이
    --    지워진 화면을 계속 가리킨다.
    CREATE TEMP TABLE fold_transition AS
    SELECT t.id,
           t.capability_id,
           COALESCE(mf.keeper_id, t.from_screen_id) AS from_keeper,
           COALESCE(mt.keeper_id, t.to_screen_id) AS to_keeper
    FROM screen_transition t
    LEFT JOIN fold_screen_merge mf ON mf.screen_id = t.from_screen_id
    LEFT JOIN fold_screen_merge mt ON mt.screen_id = t.to_screen_id
    WHERE mf.screen_id IS NOT NULL OR mt.screen_id IS NOT NULL;

    CREATE TEMP TABLE fold_transition_keep AS
    SELECT f.id,
           f.from_keeper,
           f.to_keeper,
           min(f.id) OVER (
               PARTITION BY f.from_keeper, f.to_keeper, coalesce(f.capability_id, -1)
           ) AS keeper_id
    FROM fold_transition f
    WHERE f.from_keeper <> f.to_keeper;

    UPDATE screen_transition t
    SET observed_count = t.observed_count + folded.extra
    FROM (
        SELECT k.keeper_id, sum(st.observed_count) AS extra
        FROM fold_transition_keep k
        JOIN screen_transition st ON st.id = k.id
        WHERE k.id <> k.keeper_id
        GROUP BY 1
    ) folded
    WHERE t.id = folded.keeper_id;

    -- `scene_edge` 가 가리키던 전이가 대표가 아니면 대표로 옮긴다. FK 가 `ON DELETE SET NULL` 이라
    -- 옮기지 않고 지우면 "어느 관측이 이 간선을 처음 검증했나" 가 소리 없이 null 이 된다.
    --
    -- 자기 자신으로 접힌 전이에는 `scene_edge` 가 매달릴 수 없다. 묶음이 씬 하나 안이라 두 끝이
    -- 같은 keeper 로 접혔다는 것은 그 전이가 씬 안이었다는 뜻이고, `scene_edge` 는 씬을 넘는
    -- 전이에만 붙는다.
    UPDATE scene_edge e
    SET first_observed_transition_id = k.keeper_id
    FROM fold_transition_keep k
    WHERE e.first_observed_transition_id = k.id
      AND k.id <> k.keeper_id;

    DELETE FROM screen_transition t
    USING fold_transition f
    WHERE t.id = f.id
      AND t.id NOT IN (SELECT keeper_id FROM fold_transition_keep);

    -- 남은 대표의 끝점을 남길 화면으로 옮긴다. 지우기를 먼저 한 뒤라야
    -- `uk_screen_transition_auto` 가 중간 상태에서 걸리지 않는다.
    UPDATE screen_transition t
    SET from_screen_id = k.from_keeper,
        to_screen_id = k.to_keeper
    FROM fold_transition_keep k
    WHERE t.id = k.keeper_id
      AND (t.from_screen_id <> k.from_keeper OR t.to_screen_id <> k.to_keeper);

    -- 5) 화면을 논리참조로 드는 표들.
    UPDATE capability_observation o
    SET screen_id = m.keeper_id
    FROM fold_screen_merge m
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
        JOIN fold_screen_merge m ON m.screen_id = a.screen_id
    )
    DELETE FROM knowledge_anchor a
    USING remapped r
    WHERE a.id = r.id AND r.rank_in_group > 1;

    UPDATE knowledge_anchor a
    SET screen_id = m.keeper_id
    FROM fold_screen_merge m
    WHERE a.screen_id = m.screen_id
      AND m.screen_id <> m.keeper_id;

    -- 6) 남길 행의 열을 채우고, 나머지를 지우고, 새 discriminator 를 쓴다.
    --
    --    `array_agg(... ORDER BY s.id) FILTER (...)` 의 첫 원소가 곧 "남길 행이 비어 있을 때만
    --    다른 행의 값으로 채운다" 이다. `image_captured_at` 은 `image_object_key` 를 준 행에서 함께
    --    가져온다 — 둘은 한 캡처의 두 칸이라 따로 고르면 이미지와 시각이 어긋난다.
    CREATE TEMP TABLE fold_screen_group AS
    SELECT m.keeper_id,
           sum(s.observed_count) AS observed_count,
           (array_agg(s.name ORDER BY s.id) FILTER (WHERE s.name IS NOT NULL))[1] AS name,
           (array_agg(s.image_object_key ORDER BY s.id)
                FILTER (WHERE s.image_object_key IS NOT NULL))[1] AS image_object_key,
           (array_agg(s.image_captured_at ORDER BY s.id)
                FILTER (WHERE s.image_object_key IS NOT NULL))[1] AS image_captured_at
    FROM screen s
    JOIN fold_screen_merge m ON m.screen_id = s.id
    GROUP BY m.keeper_id;

    -- 접히는 행을 먼저 지운다. `uk_screen_discriminator`(V59) 가 걸려 있어 새 값을 먼저 쓰면
    -- 합치기 전에 제약 위반이 난다.
    DELETE FROM screen s
    USING fold_screen_merge m
    WHERE s.id = m.screen_id
      AND m.screen_id <> m.keeper_id;

    UPDATE screen s
    SET observed_count = g.observed_count,
        name = g.name,
        image_object_key = g.image_object_key,
        image_captured_at = g.image_captured_at,
        discriminator = r.discriminator
    FROM fold_screen_group g
    JOIN fold_screen_rewrite r ON r.screen_id = g.keeper_id
    WHERE s.id = g.keeper_id;

    DROP TABLE fold_screen_group;
    DROP TABLE fold_transition_keep;
    DROP TABLE fold_transition;
    DROP TABLE fold_screen_merge;
    DROP TABLE fold_screen_rewrite;
    RETURN v_folded;
END;
$$;

COMMENT ON FUNCTION fold_scene_screens(BIGINT) IS
    '목록을 적용해 이 씬의 화면을 다시 계산하고 같아지는 것끼리 접는다. 사라진 화면 수를 돌려준다.';
