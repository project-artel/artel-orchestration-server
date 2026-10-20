--------------------------------------------------------------------------------
-- V119. QA 이슈를 묶는 중첩 folder (ARTEL-1114)
--------------------------------------------------------------------------------
-- 이슈 화면 왼쪽은 케이스 라이브러리와 같은 모양이다 — 버전 목록 아래 사용자 folder tree. 우클릭으로 folder 를
-- 만들고 이슈를 끌어 담는다. 모양은 `test_case_folder` · `test_case_folder_item`(V99 · V107)을 따른다.
--
-- - folder 를 지우면 하위 folder 는 `parent_id ... ON DELETE CASCADE` 로, 담긴 항목은 `folder_id ... CASCADE` 로
--   함께 지워진다. 이슈 자체는 남는다.
-- - 한 이슈가 여러 folder 에 들 수 있다(복제하면 같은 이슈가 두 folder 에 있다). 그래서 항목 표를 따로 둔다.
-- - 같은 프로젝트인지는 DB 가 보지 않는다. 쓰는 경로가 `IssueFolderService` 하나이고, 거기서 확인한다.

CREATE TABLE IF NOT EXISTS issue_folder (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    -- null 이면 최상위 folder 다.
    parent_id BIGINT REFERENCES issue_folder (id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 같은 부모 아래 이름은 겹치지 않는다. 최상위끼리는 parent_id 가 null 이라 partial index 를 둘로 나눈다(V99 와 같다).
CREATE UNIQUE INDEX IF NOT EXISTS ux_issue_folder_child_name
    ON issue_folder (parent_id, name) WHERE parent_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_issue_folder_root_name
    ON issue_folder (project_id, name) WHERE parent_id IS NULL;
CREATE INDEX IF NOT EXISTS ix_issue_folder_project ON issue_folder (project_id);

CREATE TABLE IF NOT EXISTS issue_folder_item (
    folder_id BIGINT NOT NULL REFERENCES issue_folder (id) ON DELETE CASCADE,
    issue_id BIGINT NOT NULL REFERENCES issue (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (folder_id, issue_id)
);
CREATE INDEX IF NOT EXISTS ix_issue_folder_item_issue ON issue_folder_item (issue_id);
