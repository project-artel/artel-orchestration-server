--------------------------------------------------------------------------------
-- V99. test case 를 묶는 중첩 folder 와 `test_case.folder_id` (ARTEL-969)
--------------------------------------------------------------------------------
-- 사용자가 test case 를 folder 로 묶고, content map 에서 생성한 test case 를 build 별 folder 에
-- 넣을 자리를 만든다(ARTEL-971).
--
-- ## 지우기 규칙을 FK 가 맡는다
--
-- folder 하나를 지우면 하위 folder 는 `parent_id ... ON DELETE CASCADE` 로 함께 지워지고, 그 안의
-- test case 는 `test_case.folder_id ... ON DELETE SET NULL` 로 folder 없음이 된다. 서비스는 `DELETE`
-- 한 문장만 실행한다.
--
-- ## 같은 프로젝트인지는 DB 가 보지 않는다
--
-- `parent_id` 의 folder 와 `test_case.folder_id` 의 folder 가 같은 프로젝트인지는 FK 로 걸 수 없다.
-- 쓰는 경로가 `TestCaseFolderService` 하나이고, 거기서 확인한다.

CREATE TABLE IF NOT EXISTS test_case_folder (
    id BIGSERIAL PRIMARY KEY,

    project_id BIGINT NOT NULL REFERENCES project (id) ON DELETE CASCADE,

    -- null 이면 최상위 folder 다.
    parent_id BIGINT REFERENCES test_case_folder (id) ON DELETE CASCADE,

    name VARCHAR(200) NOT NULL,

    -- content map 에서 test case 를 생성할 때 만든 folder 만 가진다. content map 하나에 folder 하나다.
    -- build 를 지우면 content map 이 cascade 로 지워지는데, 사용자가 그 folder 에 모아 둔 test case
    -- 구성은 남긴다 — folder 는 일반 folder 가 된다.
    content_map_id BIGINT UNIQUE REFERENCES content_map (id) ON DELETE SET NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 같은 부모 아래 이름은 겹치지 않는다. `(parent_id, name)` 하나로 쓰면 최상위 folder 끼리는
-- `parent_id` 가 null 이라 서로 다른 값으로 취급되어 막지 못한다. `NULLS NOT DISTINCT` 는 Postgres 15
-- 부터라 partial index 두 개로 나눈다.
CREATE UNIQUE INDEX IF NOT EXISTS ux_test_case_folder_child_name
    ON test_case_folder (parent_id, name) WHERE parent_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_test_case_folder_root_name
    ON test_case_folder (project_id, name) WHERE parent_id IS NULL;
CREATE INDEX IF NOT EXISTS ix_test_case_folder_project ON test_case_folder (project_id);

-- null 이면 folder 없음이다. 기존 행은 전부 null 로 시작한다.
ALTER TABLE test_case
    ADD COLUMN IF NOT EXISTS folder_id BIGINT REFERENCES test_case_folder (id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS ix_test_case_folder_id ON test_case (folder_id);
