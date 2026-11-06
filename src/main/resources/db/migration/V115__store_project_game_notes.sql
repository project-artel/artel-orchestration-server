--------------------------------------------------------------------------------
-- V115. 프로젝트별 게임 노트와 붙일 genre module 을 버전으로 저장한다 (ARTEL-1061)
--------------------------------------------------------------------------------
-- 게임을 만든 사람이 agent-server planner 에게 직접 알려 주는 글이다. content map 과 판 중 관측에 없는
-- 사실(scan 이 읽지 못하는 asset 의 표, 디자이너가 지키는 규칙)을 여기에 쓴다. 출발점은
-- `artel-kimgooner-jev-poc` 의 `jev_poc/game_notes.py` 다.
--
-- 저장할 때마다 새 버전 한 행을 넣고 고치거나 지우지 않는다. QA 런은 시작하는 순간의 최신 버전을
-- agent-server 세션 개설 본문의 `game_notes` 로 받는다.
--
-- 버전 채번은 (project_id, version) PK 가 막는다. 같은 baseVersion 으로 온 두 저장은 둘 다 n+1 을 넣으려
-- 하고, `INSERT ... ON CONFLICT DO NOTHING` 에서 늦은 쪽이 0 행이 되어 409 `game_notes_conflict` 로 끝난다.
CREATE TABLE IF NOT EXISTS project_game_note (
    project_id BIGINT  NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    version    INTEGER NOT NULL CHECK (version >= 1),
    -- planner 프롬프트에 매번 들어가서 길이를 막는다. PoC 의 `MAX_NOTE_CHARS` 와 같다.
    body       TEXT    NOT NULL CHECK (char_length(body) <= 20000),
    -- genre module 이름(`ui-flow`, `turn-based`). 값의 목록은 코드(`GenreModule`)가 정한다. agent-server 에
    -- module 이 늘 때마다 migration 이 붙지 않도록 CHECK 로 값을 묶지 않는다.
    genres     TEXT[]  NOT NULL DEFAULT '{}',
    -- `project_document.uploaded_by` 와 같다. app_user 는 지우지 않고 막는다(V97).
    created_by BIGINT  NOT NULL REFERENCES app_user (id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (project_id, version)
);

COMMENT ON TABLE project_game_note IS
    '프로젝트별 게임 노트. 저장마다 한 행을 더하는 append-only 표이고, 가장 큰 version 이 현재 노트다.';
COMMENT ON COLUMN project_game_note.version IS
    '프로젝트 안에서 1 부터 오르는 버전. PUT 의 baseVersion + 1 이다.';
COMMENT ON COLUMN project_game_note.body IS
    'planner 에게 주는 자유 글. 줄바꿈을 LF 로 맞추고 앞뒤 공백을 지운 값이다. 20000 자 이하.';
COMMENT ON COLUMN project_game_note.genres IS
    'planner 프롬프트에 붙일 genre module 이름. GenreModule 의 선언 순서로 정렬해 중복 없이 넣는다.';
COMMENT ON COLUMN project_game_note.created_by IS
    '이 버전을 저장한 app_user.id.';
