--------------------------------------------------------------------------------
-- V100. macro 의 버전 이력을 남기고, revert 가 어느 event 를 되돌렸는지 적는다
--------------------------------------------------------------------------------
-- QA run 화면이 그 run 이 바꾼 knowledge 와 macro 를 diff 로 보여 주고 사람이 revert 할 수 있게
-- 한다(`.plan/general/2026-10-13-show-and-revert-run-knowledge-and-macro-changes.md`).
--
-- 담는 것 넷:
--   1. macro.version       — 현재 정의의 버전. knowledge.version(V27)과 같은 규칙이다
--   2. macro_event         — macro 의 버전 이력. knowledge_event(V27)와 같은 모양이다
--   3. revert 표시 두 칸   — 두 event 표에 `reverts_event_id` 와 `reverted_by_app_user_id`
--   4. 이력 backfill       — 기존 macro 와 이력 없는 버전 1 knowledge 에 CREATE event 하나씩
--
-- ## revert 는 이력을 고치지 않는다
--
-- revert 는 event 를 지우거나 고치지 않고 새 event 를 덧붙인다. 그 event 의 `reverts_event_id` 가
-- 되돌린 event 를 가리킨다. 그래서 "이 run 의 변경이 되돌려졌나" 는 그 run 의 마지막 event 를
-- 가리키는 event 가 있는지로 읽힌다.

--------------------------------------------------------------------------------
-- 1. macro.version
--------------------------------------------------------------------------------
-- 정의(`source` · `definition_json` · `parameter_names`)가 실제로 바뀔 때만 오른다. 같은 정의를
-- 다시 등록하면 오르지 않는다 — 오르면 내용이 같은 event 가 쌓이고, 나중 run 의 같은 등록이 앞
-- run 의 변경을 "덮었다" 고 읽혀 revert 가 막힌다.
--
-- 기존 행은 DEFAULT 1 이고, 아래 4 에서 그 버전 1 의 CREATE event 를 만든다.
ALTER TABLE macro
    ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 1;

COMMENT ON COLUMN macro.version IS
    '현재 정의의 버전. 정의가 바뀌는 등록과 revert 에서만 오른다. macro_event 의 최대 content 버전과 같아야 한다.';

--------------------------------------------------------------------------------
-- 2. macro_event — 버전 이력
--------------------------------------------------------------------------------
-- FK 를 걸지 않는다. revert 로 macro 행을 지워도(`screen_macro` 는 CASCADE 로 함께 지워진다) 이력은
-- 남아야 하고, 지운 macro 를 다시 보여 줄 근거가 이 표뿐이다. knowledge_event 와 같은 판단이다.
--
-- knowledge_event 는 `after` JSONB 한 칸에 content 를 담지만 여기는 칸을 나눈다. macro 의 content
-- 는 `macro` 행의 세 칸 그대로이고, 나눠 두면 revert 가 그 세 칸을 손으로 풀지 않고 옮긴다.
--
-- `name` 은 모든 event 에 있다. DELETE event 만 남은 macro 도 화면에 이름이 나와야 한다.
-- content 세 칸은 DELETE 에만 NULL 이다. 그 NULL 이 "이 event 는 버전을 만들지 않았다" 는 표시다.
CREATE TABLE IF NOT EXISTS macro_event (
    id                      BIGSERIAL PRIMARY KEY,
    macro_id                BIGINT NOT NULL,
    -- 원본이 지워진 뒤에도 어느 build 의 macro 였는지 남겨야 한다.
    content_map_id          BIGINT NOT NULL,
    -- 이 변경을 일으킨 QA run 의 `qa_try`. 사람이 한 revert 와 backfill 은 NULL 이다.
    qa_try_id               BIGINT,
    event                   VARCHAR(20) NOT NULL
                            CHECK (event IN ('CREATE', 'UPDATE', 'DELETE', 'RESTORE')),
    version                 INT NOT NULL,
    name                    VARCHAR(200) NOT NULL,
    source                  TEXT,
    definition_json         JSONB,
    parameter_names         JSONB,
    -- 이 event 가 되돌린 event(macro_event.id). revert 가 아니면 NULL 이다.
    reverts_event_id        BIGINT,
    -- revert 를 누른 사람(app_user.id). FK 를 걸지 않는 이유는 위와 같다.
    reverted_by_app_user_id BIGINT,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ck_macro_event_content_matches_event CHECK (
        (event = 'DELETE') = (source IS NULL)
        AND (source IS NULL) = (definition_json IS NULL)
        AND (source IS NULL) = (parameter_names IS NULL)
    ),
    -- revert 는 HTTP 경로에서만 일어나고 그 경로에는 늘 로그인한 사람이 있다.
    CONSTRAINT ck_macro_event_revert_has_actor CHECK (
        (reverts_event_id IS NULL) = (reverted_by_app_user_id IS NULL)
    )
);

-- 같은 버전이 두 번 기록되는 것을 막는다. 동시 등록이나 등록과 revert 가 겹치면 한쪽 트랜잭션이
-- 통째로 되돌아가고, 행과 이력이 어긋난 채 남지 않는다. `uq_knowledge_event_version` 과 같은
-- 목적이다. DELETE/RESTORE 는 버전을 올리지 않으므로 뺀다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_macro_event_version
    ON macro_event (macro_id, version) WHERE event IN ('CREATE', 'UPDATE');

-- run 하나가 바꾼 macro 를 모으는 조회.
CREATE INDEX IF NOT EXISTS idx_macro_event_try ON macro_event (qa_try_id);

-- macro 하나의 최신 event 를 읽는 조회. revert 가 허용되는지가 이것으로 정해진다.
CREATE INDEX IF NOT EXISTS idx_macro_event_target ON macro_event (macro_id, id);

-- 어떤 event 가 이미 되돌려졌는지 읽는 조회.
CREATE INDEX IF NOT EXISTS idx_macro_event_reverts
    ON macro_event (reverts_event_id) WHERE reverts_event_id IS NOT NULL;

--------------------------------------------------------------------------------
-- 3. knowledge_event 의 revert 표시
--------------------------------------------------------------------------------
ALTER TABLE knowledge_event
    ADD COLUMN IF NOT EXISTS reverts_event_id        BIGINT,
    ADD COLUMN IF NOT EXISTS reverted_by_app_user_id BIGINT;

ALTER TABLE knowledge_event
    DROP CONSTRAINT IF EXISTS ck_knowledge_event_revert_has_actor;
ALTER TABLE knowledge_event
    ADD CONSTRAINT ck_knowledge_event_revert_has_actor CHECK (
        (reverts_event_id IS NULL) = (reverted_by_app_user_id IS NULL)
    );

COMMENT ON COLUMN knowledge_event.reverts_event_id IS
    '이 event 가 되돌린 knowledge_event.id. revert 가 아니면 NULL.';
COMMENT ON COLUMN knowledge_event.reverted_by_app_user_id IS
    'revert 를 누른 app_user.id. revert 가 아니면 NULL.';

-- V27 은 `qa_try_id` 와 `(knowledge_id, version)` 부분 유니크만 두었다. 항목 하나의 최신 event 를
-- 읽는 조회는 knowledge_id 단독으로는 정렬을 못 받으므로 (knowledge_id, id) 를 더한다.
CREATE INDEX IF NOT EXISTS idx_knowledge_event_target ON knowledge_event (knowledge_id, id);

CREATE INDEX IF NOT EXISTS idx_knowledge_event_reverts
    ON knowledge_event (reverts_event_id) WHERE reverts_event_id IS NOT NULL;

--------------------------------------------------------------------------------
-- 4. 이력 backfill
--------------------------------------------------------------------------------
-- revert 는 "run 이전의 내용" 을 이전 버전의 event 에서 읽는다. 그 event 가 없으면 되돌릴 내용을
-- 모르고, 그 run 의 UPDATE 는 영영 revert 할 수 없다.
--
-- **macro**: 모든 행에 버전 1 CREATE 를 만든다. 지금 행의 내용이 곧 버전 1 이다(이 migration 전에는
-- 버전이 없었다). 만든 run 은 모르므로 `qa_try_id` 는 NULL 이고, 시각은 그 내용이 적힌
-- `updated_at` 이다.
INSERT INTO macro_event (
    macro_id, content_map_id, qa_try_id, event, version,
    name, source, definition_json, parameter_names, created_at
)
SELECT m.id, m.content_map_id, NULL, 'CREATE', 1,
       m.name, m.source, m.definition_json, m.parameter_names, m.updated_at
  FROM macro m
 WHERE NOT EXISTS (SELECT 1 FROM macro_event e WHERE e.macro_id = m.id);

-- **knowledge**: V27 은 이력을 backfill 하지 않았다. 없는 이력을 지어내지 않으려는 것이었다.
-- 여기서 채우는 것은 그 판단과 부딪히지 않는다 — 아직 버전 1 이고 event 가 하나도 없는 행은 지금
-- 행의 내용이 버전 1 의 내용 그 자체다. 지어내는 값이 없다. 이 행을 다음 run 이 고치면 버전 1 의
-- 내용은 어디에도 남지 않으므로, 지금 적어 두지 않으면 그 UPDATE 는 revert 할 수 없다.
--
-- 이미 버전 2 이상인데 버전 1 event 가 없는 행은 그 내용이 이미 사라졌으므로 채우지 않는다.
--
-- `knowledge_entry_facts` view 는 버전 1 event 가 없는 행에 같은 값(`qa_try_id` NULL,
-- `created_at` = 행의 값)을 합성해 넣고 있었으므로, 이 backfill 뒤에도 view 의 결과는 같다.
INSERT INTO knowledge_event (knowledge_id, project_id, qa_try_id, event, version, after, created_at)
SELECT k.id, k.project_id, NULL, 'CREATE', 1,
       jsonb_build_object('tag', k.tag, 'summary', k.summary, 'description', k.description),
       k.created_at
  FROM knowledge k
 WHERE k.version = 1
   AND NOT EXISTS (SELECT 1 FROM knowledge_event e WHERE e.knowledge_id = k.id);
