--------------------------------------------------------------------------------
-- game_build 에 editor 전용 행을 둔다 (ARTEL-1014)
--
-- SDK 는 Unity editor 와 development build 에서만 돈다. 둘 다 Player Settings 의 같은
-- `Application.version` 을 보고하므로, (project_id, version) 하나로 찾으면 editor 실행이 같은 버전의
-- development build 행에 내려앉아 그 행의 scene_scan 과 content map 을 덮어쓴다.
--
-- editor 는 계속 바뀌는 작업 상태라 게임 버전이 아니다. 그래서 프로젝트마다 editor 행을 하나만 두고,
-- 등록마다 그 행의 version 과 scene_scan 을 바꾼다. 유니크 규칙도 둘로 나눈다.
--
--   is_editor = FALSE — 지금과 같이 (project_id, version) 마다 한 행
--   is_editor = TRUE  — project_id 마다 한 행. version 이 development build 와 같아도 된다
--
-- 기존 행은 전부 FALSE 로 둔다. 이미 섞인 행이 editor 였는지는 알 수 없다.
--------------------------------------------------------------------------------

ALTER TABLE game_build
    ADD COLUMN IF NOT EXISTS is_editor BOOLEAN NOT NULL DEFAULT FALSE;

-- V4 가 건 UNIQUE 제약이다. 제약을 지우면 뒤의 인덱스도 함께 지워지므로 같은 이름을 다시 쓸 수 있다.
ALTER TABLE game_build DROP CONSTRAINT IF EXISTS uk_game_build_project_version;

CREATE UNIQUE INDEX IF NOT EXISTS uk_game_build_project_version
    ON game_build (project_id, version)
    WHERE is_editor = FALSE;

CREATE UNIQUE INDEX IF NOT EXISTS uk_game_build_project_editor
    ON game_build (project_id)
    WHERE is_editor = TRUE;
