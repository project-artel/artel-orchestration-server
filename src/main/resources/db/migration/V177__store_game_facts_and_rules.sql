--------------------------------------------------------------------------------
-- V177. 게임 개발자가 MCP 로 선언한 fact 와 rule 을 프로젝트에 둔다
--------------------------------------------------------------------------------
-- planner–executer 엔진의 결정 모델(Jev)은 객체 트리 속 코드 이름(`CardManager.myCards.Count`)보다 이름과 뜻이 붙은
-- 값(`cards_in_hand`: 손에 든 카드 수)을 훨씬 잘 읽는다. fact 는 게임 값 하나에 플레이어 말의 이름과 뜻을 붙인 것이다.
-- planner 가 step 마다 쓸 fact 를 고르고, 엔진은 그 값을 결정 모델 state 의 맨 위에 싣는다.
--
-- rule 은 값과 행동의 관계를 말하는 게임 지식 한 문장이다(예: 조합 버튼은 두 슬롯이 모두 찼을 때만 보인다). planner
-- 만 읽는다. 조언(무엇을 하라)이 아니라 게임이 어떻게 도는지다.
--
-- 둘 다 게임을 만든 사람의 코딩 에이전트가 원격 MCP 서버를 거쳐 선언하고, agent-server 가 런 시작 때 읽는다.
-- 게임 소스에 관한 것(코드 위치 등)은 두지 않는다.
--
-- ## `game_identity`(V173)와 따로 두는 이유
--
-- identity 는 대상 해석이 쓰는 "이 값이 무엇을 가리키는가" 이고, fact 는 결정 모델이 매 turn 읽는 값이다. 읽는 쪽과
-- 칸이 다르다(fact 에는 엔진이 읽을 source 이름과 값의 타입이 있다).
--
-- ## 프로젝트 소속, build 는 기록만
--
-- `game_identity` 와 같다. 이름의 유일 범위는 프로젝트이고 `game_build_id` 는 어느 build 를 보고 썼는지 남긴다.
--
-- ## rule 과 fact 의 관계
--
-- rule 은 자기가 말하는 fact 의 이름을 `facts` 에 싣는다. 등록 경로(`GameFactService`)가 그 fact 가 같은 프로젝트에
-- 있는지 보고, rule 이 쓰는 fact 는 지우지 못하게 한다. 이름 배열이라 외래 키는 걸 수 없다.

CREATE TABLE IF NOT EXISTS game_fact (
    id BIGSERIAL PRIMARY KEY,

    project_id BIGINT NOT NULL REFERENCES project (id) ON DELETE CASCADE,

    -- 플레이어 말의 이름. snake_case 40자 이하. 예: `cards_in_hand`.
    name VARCHAR(40) NOT NULL,

    -- `objects` 는 살아 있는 게임 객체가 가진 컴포넌트의 member, `statics` 는 static member.
    scope VARCHAR(10) NOT NULL CHECK (scope IN ('objects', 'statics')),

    -- 엔진이 읽는 이름 그대로. 예: `CardManager.myCards.Count`, `CombineZone.Instance.isCasting`.
    member VARCHAR(200) NOT NULL,

    type VARCHAR(10) NOT NULL CHECK (type IN ('integer', 'number', 'boolean', 'string')),

    -- 값의 단위를 말하는 짧은 낱말. 없으면 NULL.
    unit VARCHAR(30),

    -- 값이 무엇인지 플레이어 말로 한 문장.
    means VARCHAR(120) NOT NULL,

    game_build_id BIGINT NOT NULL REFERENCES game_build (id) ON DELETE CASCADE,

    -- 선언한 칸이 실제로 바뀔 때만 오른다.
    version INT NOT NULL DEFAULT 1,

    created_by BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    updated_by BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_game_fact_project_name ON game_fact (project_id, name);

CREATE TABLE IF NOT EXISTS game_rule (
    id BIGSERIAL PRIMARY KEY,

    project_id BIGINT NOT NULL REFERENCES project (id) ON DELETE CASCADE,

    -- snake_case 60자 이하. 예: `combine_needs_both_slots`.
    name VARCHAR(60) NOT NULL,

    -- 게임이 어떻게 도는지 한 문장. 조언이 아니다.
    says VARCHAR(160) NOT NULL,

    -- 이 rule 이 말하는 fact 의 이름. 하나 이상.
    facts TEXT[] NOT NULL,

    game_build_id BIGINT NOT NULL REFERENCES game_build (id) ON DELETE CASCADE,

    version INT NOT NULL DEFAULT 1,

    created_by BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    updated_by BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_game_rule_project_name ON game_rule (project_id, name);
