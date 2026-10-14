--------------------------------------------------------------------------------
-- V108. 케이스 라이브러리의 버전 묶음과 버전 숨김 (ARTEL-1023)
--------------------------------------------------------------------------------
-- 라이브러리 왼쪽의 버전 목록을 사람이 정리한다.
-- - 버전 묶음: 버전과 같은 층에 두는 이름 붙은 묶음 하나(예: `1.X`, `2.X`). 묶음 안에 묶음은 없다.
-- - 숨김: 목록에서 뺀 버전. 지우지 않고, 숨긴 버전 보기에서 다시 보이게 할 수 있다.
--
-- 둘 다 프로젝트가 함께 보는 정리라 사용자마다 따로 두지 않는다. folder 와 같다.
--
-- `game_build` 에 칸을 더하지 않는다. build 는 SDK 가 보고하는 게임 정보이고, 이것은 라이브러리 화면의
-- 정리다. 설정 행이 없는 build 는 묶음 없음 · 보임이다.

CREATE TABLE IF NOT EXISTS test_case_version_group (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_test_case_version_group_name UNIQUE (project_id, name)
);

CREATE TABLE IF NOT EXISTS test_case_version_setting (
    game_build_id BIGINT PRIMARY KEY REFERENCES game_build (id) ON DELETE CASCADE,
    -- 묶음을 지우면 그 안의 버전은 묶음 없음으로 돌아간다.
    group_id BIGINT REFERENCES test_case_version_group (id) ON DELETE SET NULL,
    hidden BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_test_case_version_setting_group ON test_case_version_setting (group_id);
