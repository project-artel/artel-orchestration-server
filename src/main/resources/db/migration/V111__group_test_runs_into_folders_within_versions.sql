--------------------------------------------------------------------------------
-- V111. 테스트 런을 빌드 버전 안의 폴더로 묶는다 (ARTEL-1049)
--------------------------------------------------------------------------------
-- 테스트 런 화면은 빌드 버전 트리이고, 런은 빌드 버전 하나를 따른다(ARTEL-1048). 런이 많아지면 버전 안에서
-- 다시 묶을 자리가 필요하다. 폴더는 **한 빌드 버전 안에만** 있다 — 다른 버전의 런을 같은 폴더에 넣지 않는다.
--
-- ## 지우기 규칙을 FK 가 맡는다
--
-- 폴더를 지우면 하위 폴더는 `parent_id ... ON DELETE CASCADE` 로 함께 지워지고, 그 안의 런은
-- `test_run.folder_id ... ON DELETE SET NULL` 로 그 버전 바로 아래로 나온다. 빌드를 지우면 그 버전의 폴더도
-- 지워진다(런은 `game_build_id` 가 이미 SET NULL 이다).
--
-- ## 같은 버전인지는 DB 가 보지 않는다
--
-- 런의 `game_build_id` 와 폴더의 `game_build_id` 가 같은지, 부모 폴더가 같은 버전인지는 FK 로 걸 수 없다.
-- 쓰는 경로가 `TestRunFolderService` · `TestRunService` 둘이고, 거기서 확인한다.

CREATE TABLE IF NOT EXISTS test_run_folder (
    id BIGSERIAL PRIMARY KEY,

    project_id BIGINT NOT NULL REFERENCES project (id) ON DELETE CASCADE,

    -- 이 폴더가 있는 빌드 버전. null 이면 "버전 없음" 안의 폴더다.
    game_build_id BIGINT REFERENCES game_build (id) ON DELETE CASCADE,

    -- null 이면 그 버전 바로 아래 폴더다.
    parent_id BIGINT REFERENCES test_run_folder (id) ON DELETE CASCADE,

    name VARCHAR(200) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_test_run_folder_project ON test_run_folder (project_id);

ALTER TABLE test_run ADD COLUMN IF NOT EXISTS folder_id BIGINT REFERENCES test_run_folder (id) ON DELETE SET NULL;
