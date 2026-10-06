--------------------------------------------------------------------------------
-- V96. 등록된 macro 를 지도에 적고, 어느 `screen` 에서 쓸 수 있는지를 잇는다
--------------------------------------------------------------------------------
-- `capability` 행은 버튼 하나가 하는 일 하나를 given · when · then 세 칸으로 담아 action 시퀀스를
-- 담을 칸이 없다. QA agent 가 `register_macro` 로 등록한 정의(이름과 parameter 를 붙인 action
-- 시퀀스)를 적을 자리를 만든다.
--
-- 담는 것 둘:
--   1. macro            — 등록된 정의. `content_map` 에 매단다
--   2. screen_macro     — macro 와 `screen` 의 다대다 관계
--
-- ## `content_map` 에 매다는 이유
--
-- `content_map` 은 build 당 하나다(`V63`). macro 도 build 당으로 붙어야 게임 build 가 바뀌면 그
-- macro 가 같이 무효가 되고, `content_map.rooted_by` 가 이미 보장하는 것처럼 evidence scan 을 안
-- 돌린 build 에서도 적을 자리가 있다.
--
-- `scene` 에 매달지 않는 이유는 macro 하나가 scene 경계를 넘을 수 있어서다. `screen` 에 매달지
-- 않는 이유는 `screen` 이 런타임 관측으로 굳는 행이기 때문이다(`ARTEL-654`) — 아직 안 굳었거나
-- 나중에 다시 갈라질 `screen` 에 macro 를 매달면 macro 가 같이 흔들린다.
--
-- ## statement 를 행으로 나누지 않는 이유
--
-- `if` 가 statement 를 중첩시켜서 평평한 표로는 몸통이 어디서 끝나는지 담지 못한다. 그리고 JSON 이
-- 질의 대상이 아니라서 tree 를 SQL 에 모델링할 이유도 없다. 텍스트가 원본이고 JSON 은 파생이다.
--
-- ## 물려받는 격리 문제
--
-- `capability` 와 같다. `content_map` 에서 `game_build` 로 바로 매달려 `knowledge.scope_id` 같은
-- 축이 없으므로, 벤치마크 arm 을 나눠 도는 런은 이 표에 대해서도 `ContentMapMode.FROZEN` 으로
-- 쓰기가 막혀야 한다. 실제로 막는 것은 `ARTEL-922` 다.

--------------------------------------------------------------------------------
-- 1. macro — 등록된 정의
--------------------------------------------------------------------------------
-- **등록된 것만 담는다.** 등록하지 않은 초안은 런의 상태에 살고 런이 끝나면 사라진다
-- (`ARTEL-925`). 초안에 남길 값이 없었다는 뜻이므로 이 표에 오지 않는다.
CREATE TABLE IF NOT EXISTS macro (
    id BIGSERIAL PRIMARY KEY,

    content_map_id BIGINT NOT NULL REFERENCES content_map (id) ON DELETE CASCADE,

    -- 진입점 `def` 의 이름. `run_macro(name, args)` 가 이것으로 부른다.
    name VARCHAR(200) NOT NULL,

    -- **원본.** `ast.parse` 가 주석과 공백을 버리므로 JSON 에서 다시 찍어내면 agent 가 적은 그대로가
    -- 아니고, 정의가 바뀌었을 때 사람이 보는 diff 가 실제로 바뀐 줄을 가리키지 않는다. 사람이 읽고
    -- diff 하는 몫이 이 칸이다.
    source TEXT NOT NULL,

    -- **파생.** 실행할 때마다 다시 파싱하지 않기 위한 실행용 캐시다. `register_macro` 가 한 번
    -- 만들고 실행은 이것만 본다. **질의 대상이 아니다** — 그래서 GIN index 를 두지 않는다.
    --
    -- helper `def` 를 각각 담고 호출을 호출로 담는다. 한 source 안의 helper 호출을 펼친 결과가
    -- 아니다. 펼쳐 담으면 저장된 정의가 사람이 적은 정의와 다른 모양이 되고, `read_macro` 가
    -- 돌려주는 것과 실행되는 것이 갈린다.
    definition_json JSONB NOT NULL,

    -- 진입점 `def` 줄에서 읽은 parameter 이름. **순서가 뜻을 가진다** — `run_macro` 가 실행 시점에
    -- 인자를 위치로 대응시킨다.
    --
    -- `definition_json` 안에도 같은 이름이 있으므로 이 칸은 일부러 둔 사본이다. 인자를 위치로
    -- 대응시키는 쪽이 tree 를 풀지 않고 개수와 순서를 보게 하는 것이 목적이고, 원본은 tree 쪽이다.
    -- 선언 타입은 여기 두지 않는다 — 타입 검사는 tree 를 이미 읽은 자리에서 한다.
    parameter_names JSONB NOT NULL DEFAULT '[]'::jsonb,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 이름의 유일 범위는 같은 `content_map_id` 안이다. 곧 build 하나 안에서 유일하다.
--
-- **등록 갱신이 이 index 를 타고 제자리에서 일어난다.** `register_macro` 는 같은 이름의 행을 지우고
-- 새로 넣지 않고 `ON CONFLICT ... DO UPDATE` 로 갱신한다. 지우고 넣으면 아래 `ON DELETE CASCADE` 로
-- 관계 행이 같이 사라지는데, 그것은 agent 가 공들여 단 것이다. 그러므로 갱신 경로가 행의 `id` 를
-- 바꾸지 않아야 하고, 이 index 가 그 경로의 충돌 대상이다.
--
-- 멱등을 앱의 `if` 로 막지 않는 이유: 같은 이름이 동시에 둘 오면 조회와 INSERT 사이로 빠져나간다.
-- 기존 knowledge · capability 쓰기가 전부 유니크 index 로 막는다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_macro_name
    ON macro (content_map_id, name);

-- `parameter_names` 는 배열이어야 한다. 객체가 들어오면 "순서 있게" 가 뜻을 잃는다.
ALTER TABLE macro
    DROP CONSTRAINT IF EXISTS ck_macro_parameter_names_array;
ALTER TABLE macro
    ADD CONSTRAINT ck_macro_parameter_names_array
        CHECK (jsonb_typeof(parameter_names) = 'array');

-- `require` 는 조건과 `remedy` 문자열을 한 쌍으로 받는다(`ARTEL-917`). **`remedy` 는 필수 칸이다.**
-- 비어 있거나 없는 `remedy` 를 가진 `require` 는 이 표가 받는 JSON 이 아니다.
--
-- `remedy` 없는 `require` 는 agent 에게 "이 조건이 안 맞았다" 까지만 말하고 무엇을 하면 맞는지는
-- 말하지 않는다. 그 상태의 macro 는 실패했을 때 agent 가 다음에 무엇을 할지 고를 수 없다.
--
-- `$.**` 로 tree 전체를 훑는 이유: `require` 가 `if` 몸통 안에 중첩될 수 있어 고정 경로로는 닿지
-- 않는다. 이 CHECK 가 의지하는 것은 "`require` node 는 `kind` 가 `"require"` 이고 `remedy` 를
-- 든다" 하나뿐이고, `statements` · `body` · `orelse` 같은 나머지 key 이름에는 기대지 않는다 —
-- JSON tree 의 모양은 `ARTEL-918` 이 확정한다.
--
-- `like_regex "^\s*$"` 가 공백만 든 `remedy` 를 함께 막는다. `@.remedy.type() != "string"` 이
-- 앞에 있어 null 과 숫자도 걸린다.
ALTER TABLE macro
    DROP CONSTRAINT IF EXISTS ck_macro_require_carries_remedy;
ALTER TABLE macro
    ADD CONSTRAINT ck_macro_require_carries_remedy
        CHECK (
            NOT jsonb_path_exists(
                definition_json,
                '$.** ? (@.kind == "require" && (!exists(@.remedy) || @.remedy.type() != "string" || @.remedy like_regex "^\\s*$"))'
            )
        );

-- `content_map_id` 하나로 좁힌 조회에 index 를 따로 두지 않는다. `uk_macro_name` 의 선두 컬럼이
-- `content_map_id` 라 그 index 가 이름 조회와 build 단위 목록 조회를 함께 받는다.

--------------------------------------------------------------------------------
-- 2. screen_macro — 지금 이 화면에서 쓸 수 있는 macro
--------------------------------------------------------------------------------
-- `screen_capability`(`V40`)가 선례다. 한 행은 (macro, `screen`) 한 쌍이고 같은 쌍은 한 번만 선다.
--
-- ## 다대다인 이유
--
-- 두 방향이 모두 있다. 대화창을 닫는 macro 처럼 여러 `screen` 에서 통하는 것이 있고, 반대로 한
-- macro 가 `screen` 경계를 넘기도 한다.
--
-- 지금은 첫 `require(scene() == ...)` 가 하던 일을 이 구조가 대신한다.
--
-- ## 빈 관계는 뜻을 가진다
--
-- 어느 `screen` 과도 안 이어진 macro 를 허용한다. 그 상태의 뜻은 **아직 어디서 쓸지 모른다**는
-- 것이고, 아무 데서나 쓸 수 있다는 뜻이 아니다. 그래서 관계 행을 NOT NULL 로 강제하지 않는다.
CREATE TABLE IF NOT EXISTS screen_macro (
    screen_id BIGINT NOT NULL REFERENCES screen (id) ON DELETE CASCADE,
    macro_id BIGINT NOT NULL REFERENCES macro (id) ON DELETE CASCADE,
    PRIMARY KEY (screen_id, macro_id)
);

-- `ON DELETE CASCADE` 가 양쪽에 있어 어느 쪽 행이 지워지든 그 쌍의 관계 행이 같이 지워진다.
-- 지워지는 것은 관계 행뿐이고 반대편 행은 남는다 — `screen` 을 지워도 macro 는 남고, macro 를
-- 지워도 `screen` 은 남는다.
--
-- `screen` 쪽 조회에 index 를 따로 두지 않는다. PK 의 선두 컬럼이 `screen_id` 라 그 index 가 곧
-- `screen` 으로 macro 를 찾는 조회의 index 다. `screen_capability` 도 PK
-- `(screen_id, capability_id)` 하나로 그쪽을 받고 반대 방향에만 index 를 더했다.
--
-- macro 쪽 조회에는 index 가 필요하다. PK 의 두 번째 컬럼이라 선두가 없으면 안 타고, "이 macro 가
-- 어느 `screen` 들에 달렸나" 는 `register_macro` 가 관계를 더할 때마다 묻는 질문이다.
CREATE INDEX IF NOT EXISTS idx_screen_macro_macro
    ON screen_macro (macro_id);
