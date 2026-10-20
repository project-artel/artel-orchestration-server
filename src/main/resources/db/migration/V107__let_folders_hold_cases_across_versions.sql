--------------------------------------------------------------------------------
-- V107. folder 가 케이스를 담는다 — `test_case_folder_item` (ARTEL-1023)
--------------------------------------------------------------------------------
-- V106 으로 케이스는 버전을 넘어 같은 `test_case` 행이 됐다. 그래서 folder 는 이제 "어느 버전의 TC" 가
-- 아니라 케이스를 담고, 화면은 고른 버전으로 그 케이스를 풀어 그린다.
--
-- ## 한 케이스가 여러 folder 에 들어간다
--
-- 사용자 folder 는 케이스를 모아 두는 목록이다. folder 를 복제하면 같은 케이스가 두 folder 에 있다.
-- 그래서 `test_case.folder_id` 한 칸으로는 담을 수 없어 항목 표를 따로 둔다.
--
-- ## 버전별 숨김
--
-- `hidden_game_build_ids` 는 이 folder 에서 그 케이스를 숨길 build 들이다. 케이스 자체나 다른 folder 에는
-- 영향이 없다. build 가 지워져도 배열에 id 가 남지만, 없는 build 로는 아무도 보지 않으므로 해가 없다.
--
-- ## content map folder 는 정리한다
--
-- #293 이 build 마다 만들던 `"<label> content map"` folder 는 버전 목록(ARTEL-1022)이 대신한다.
-- - 하위 folder 가 없으면 지운다. 그 안의 케이스는 folder 없음이 된다.
-- - 사용자가 하위 folder 를 만들어 썼으면 일반 folder 로 남긴다. 항목도 옮긴다.
-- 생성은 더 이상 folder 를 만들지 않으므로 `content_map_id` 칸도 지운다.

CREATE TABLE IF NOT EXISTS test_case_folder_item (
    id BIGSERIAL PRIMARY KEY,

    folder_id BIGINT NOT NULL REFERENCES test_case_folder (id) ON DELETE CASCADE,
    test_case_id BIGINT NOT NULL REFERENCES test_case (id) ON DELETE CASCADE,

    -- folder 안 순서. 0 부터이고, 쓰는 쪽이 빈틈 없이 다시 매긴다.
    position INT NOT NULL,

    hidden_game_build_ids BIGINT[] NOT NULL DEFAULT '{}',

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uk_test_case_folder_item UNIQUE (folder_id, test_case_id)
);

CREATE INDEX IF NOT EXISTS ix_test_case_folder_item_case ON test_case_folder_item (test_case_id);

-- 1) 하위 folder 가 없는 content map folder 를 지운다. 케이스의 folder_id 는 FK 로 null 이 된다.
DELETE FROM test_case_folder f
 WHERE f.content_map_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM test_case_folder c WHERE c.parent_id = f.id);

-- 2) 남은 소속을 항목으로 옮긴다. 순서는 케이스 id 순이다.
INSERT INTO test_case_folder_item (folder_id, test_case_id, position)
SELECT t.folder_id, t.id, (ROW_NUMBER() OVER (PARTITION BY t.folder_id ORDER BY t.id) - 1)::INT
  FROM test_case t
 WHERE t.folder_id IS NOT NULL;

-- 3) 옛 칸을 지운다.
DROP INDEX IF EXISTS ix_test_case_folder_id;
ALTER TABLE test_case DROP COLUMN IF EXISTS folder_id;
ALTER TABLE test_case_folder DROP COLUMN IF EXISTS content_map_id;
