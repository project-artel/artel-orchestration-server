--------------------------------------------------------------------------------
-- V150. 게임 개발자가 MCP 로 선언한 게임 값의 뜻을 프로젝트에 둔다
--------------------------------------------------------------------------------
-- planner–executer 엔진은 게임 state 의 어떤 값이 무엇을 가리키는지 알아야 계획을 세운다. 예를 들어
-- `StageDataSingleton.stagePosition` 은 방금 들어간 stage 이고 0 부터 세며, `MapMove.StagePosition` 은 도달한 가장 먼
-- stage 이고 처음 깰 때 1 오른다. 이름만 보고는 둘을 가를 수 없다.
--
-- 그 뜻은 게임을 만든 사람의 코딩 에이전트가 원격 MCP 서버를 거쳐 선언하고, agent-server 가 런 시작 때 읽는다.
--
-- ## `identifies` 와 `role`
--
-- 위 두 값은 두 칸으로 갈린다. 무엇을 서로 가르는 값인지(`identifies`, 둘 다 `stage`)와 그 중 어느 것을
-- 가리키는지(`role`, 앞은 `current`, 뒤는 `progress`)다. `identifies` 가 같은 선언끼리 묶어 읽을 수
-- 있도록 `identifies` 는 짧은 소문자 명사로 받는다.
--
-- 게임 소스 파일에 관한 것(코드 위치 등)은 이 서버에 두지 않는다.
--
-- ## `developer_macro` 와 섞지 않는 이유
--
-- developer macro(`V116`)는 실행하는 행동이고 이 표의 행은 읽는 값이다. 검사(agent-server)·버전 비교·읽는 쪽이 모두
-- 다르다. 한 표에 두면 `source`·`definition_json` 같은 macro 칸이 값 선언에 빈 채로 붙는다.
--
-- ## 프로젝트 소속, build 는 기록만
--
-- `developer_macro` 와 같다. 이름의 유일 범위는 프로젝트이고, `game_build_id` 는 그 선언을 어느 build 를 보고 썼는지
-- 남기는 칸이라 유일 범위에 들지 않는다. 그 build 가 같은 프로젝트의 것인지는 등록 경로(`GameIdentityService`)가 본다.
--
-- 삭제는 행 삭제다.

CREATE TABLE IF NOT EXISTS game_identity (
    id BIGSERIAL PRIMARY KEY,

    project_id BIGINT NOT NULL REFERENCES project (id) ON DELETE CASCADE,

    -- 게임 state 가 부르는 이름 그대로의 `Type.member`. 예: `StageDataSingleton.stagePosition`.
    name VARCHAR(200) NOT NULL,

    -- 값의 종류. `ordinal` 은 세는 번호, `enum` 은 정해진 값 중 하나, `id` 는 대상을 가리키는 식별자,
    -- `flag` 는 참·거짓, `text` 는 사람이 읽는 문자열이다.
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('ordinal', 'enum', 'id', 'flag', 'text')),

    -- `ordinal` 이 0 부터 세는지 1 부터 세는지. 다른 종류이거나 선언하지 않았으면 NULL 이다.
    base INT CHECK (base IN (0, 1)),

    -- 값이 무엇을 가리키는지 한 문장.
    meaning VARCHAR(500) NOT NULL,

    -- 값이 언제 바뀌는지.
    changes_when VARCHAR(500) NOT NULL,

    -- 값이 무엇을 서로 가르는지 짧은 소문자 명사. 예: `stage`, `map chapter`, `enemy wave`, `tutorial step`.
    identifies VARCHAR(60) NOT NULL,

    -- `identifies` 중 어느 쪽을 가리키는지. `current` 는 지금 고른·들어간 것, `progress` 는 플레이가 어디까지
    -- 왔는지(도달한 가장 먼 것, 깬 수, 다음에 깰 것), `other` 는 둘 다 아닌 것이다.
    role VARCHAR(20) NOT NULL CHECK (role IN ('current', 'progress', 'other')),

    -- 이 선언을 쓸 때 기준으로 삼은 build. 마지막 등록의 값이다.
    game_build_id BIGINT NOT NULL REFERENCES game_build (id) ON DELETE CASCADE,

    -- 선언한 칸이 실제로 바뀔 때만 오른다. 같은 선언을 다시 등록하면 그대로다.
    version INT NOT NULL DEFAULT 1,

    -- 사용자를 지워도 선언은 프로젝트의 것이라 남는다.
    created_by BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    updated_by BIGINT REFERENCES app_user (id) ON DELETE SET NULL,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 등록은 이 index 를 충돌 대상으로 `ON CONFLICT ... DO UPDATE` 한다(`uk_developer_macro_project_name` 과 같은 이유).
-- 선두 컬럼이 `project_id` 라 프로젝트 단위 목록도 이 index 를 탄다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_game_identity_project_name
    ON game_identity (project_id, name);
