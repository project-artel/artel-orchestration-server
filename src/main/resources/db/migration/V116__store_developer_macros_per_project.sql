--------------------------------------------------------------------------------
-- V116. 게임 개발자가 MCP 로 등록한 macro 를 프로젝트에 둔다
--------------------------------------------------------------------------------
-- 계약은 `.plan/general/2026-10-15-developer-macro-mcp.md` 다(ARTEL-1081, ARTEL-1088).
--
-- 게임을 만든 사람의 코딩 에이전트가 원격 MCP 서버를 거쳐 macro 를 검사하고 등록한다. 등록된 macro 는
-- planner–executer 엔진의 planner 만 `from macros import` 로 가져다 쓴다.
--
-- ## 기존 `macro` 표와 섞지 않는 이유
--
-- `macro`(`V98`)는 QA agent 가 실제로 돌려 보고 등록한 정의만 담고 build 의 `content_map` 에 매달린다.
-- 개발자 macro 는 돌려 보지 않은 정의이고 `content_map` 이 없어도 존재해야 한다. 한 표에 두면
-- "`macro` 의 정의는 agent 가 돌려 본 것이다" 라는 원칙이 깨지고, tool loop agent 의 `MACRO_READ`·
-- `MACRO_SEARCH` 가 개발자 macro 를 읽게 된다.
--
-- ## 프로젝트 소속, build 는 기록만
--
-- 이름의 유일 범위는 프로젝트다. `game_build_id` 는 그 macro 를 어느 build 를 보고 썼는지 남기는
-- 칸이고 유일 범위에 들지 않는다 — 같은 이름을 build 마다 따로 두지 않는다(마지막 기준 build 하나).
-- 그 build 가 같은 프로젝트의 것인지는 등록 경로(`DeveloperMacroService`)가 본다.
--
-- 삭제는 행 삭제다. 이력은 후속 작업이다.

CREATE TABLE IF NOT EXISTS developer_macro (
    id BIGSERIAL PRIMARY KEY,

    project_id BIGINT NOT NULL REFERENCES project (id) ON DELETE CASCADE,

    -- 진입점 `def` 의 이름. `from macros import` 가 이 이름으로 가져온다.
    name VARCHAR(200) NOT NULL,

    -- **원본.** 사람이 읽고 diff 하는 것이 이 칸이다. `macro.source` 와 같은 상한을 둔다.
    source TEXT NOT NULL CHECK (char_length(source) <= 20000),

    -- **파생.** agent-server `POST /internal/macros/check` 가 돌려준 정의를 그대로 담는다. 질의 대상이
    -- 아니다. 엔진은 읽을 때마다 `source` 를 지금 등록된 macro 들로 다시 파싱하므로 이 칸은 검사가
    -- 통과했을 때의 결과를 남기는 몫이다.
    definition_json JSONB NOT NULL,

    -- 진입점 `def` 의 parameter 이름. 순서가 뜻을 가진다(`macro.parameter_names` 와 같다).
    parameter_names JSONB NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(parameter_names) = 'array'),

    -- 진입점 `def` 의 docstring. 개발자 macro 는 docstring 이 필수라(agent-server 검사) 비지 않는다.
    -- planner prompt 가 이 칸을 보여 준다.
    docstring VARCHAR(2000) NOT NULL,

    -- 이 macro 를 쓸 때 기준으로 삼은 build. 마지막 등록의 값이다.
    game_build_id BIGINT NOT NULL REFERENCES game_build (id) ON DELETE CASCADE,

    -- 정의가 실제로 바뀔 때만 오른다. 같은 source 를 다시 등록하면 그대로다.
    version INT NOT NULL DEFAULT 1,

    -- 사용자를 지워도 macro 는 프로젝트의 것이라 남는다.
    created_by BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    updated_by BIGINT REFERENCES app_user (id) ON DELETE SET NULL,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 등록은 이 index 를 충돌 대상으로 `ON CONFLICT ... DO UPDATE` 한다. 조회 후 분기하면 같은 이름이
-- 동시에 둘 올 때 그 사이로 빠져나간다. 선두 컬럼이 `project_id` 라 프로젝트 단위 목록도 이 index 를 탄다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_developer_macro_project_name
    ON developer_macro (project_id, name);
