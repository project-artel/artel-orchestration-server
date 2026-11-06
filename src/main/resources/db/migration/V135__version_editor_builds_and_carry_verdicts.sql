--------------------------------------------------------------------------------
-- editor 도 버전마다 행을 두고, 앞 버전 판정을 이어받은 출처를 남긴다 (ARTEL-1180)
--
-- V105(ARTEL-1014)는 editor 를 프로젝트당 한 행으로 두고 등록마다 version 을 덮었다. editor 가 같은 버전의
-- development build 행을 덮지 않게 하려는 것이었고, 그 대가로 editor 에서는 TC 를 만들 수 없었다.
-- 게임 개발자는 QA 를 editor 에서 더 많이 돌리므로, editor 도 Player Settings Version 마다 한 행을 둔다.
-- 같은 버전 재등록은 그 행의 scene_scan 을 덮으므로 build 행과는 여전히 섞이지 않는다.
--
--   is_editor = FALSE — (project_id, version) 마다 한 행 (그대로)
--   is_editor = TRUE  — (project_id, version) 마다 한 행 (프로젝트당 한 행에서 바뀜)
--
-- 기존 editor 행은 마지막으로 보고된 version 의 행으로 남는다.
--
-- verdict_carried_from_build_id: 이 버전 몫의 판정을 앞 버전 몫에서 이어받았으면 그 build. 이 버전에서
-- 직접 판정이 나거나 다시 검증하도록 돌리면 비운다. 화면이 "이번에 본 것" 과 "물려받은 것" 을 가른다.
--------------------------------------------------------------------------------

DROP INDEX IF EXISTS uk_game_build_project_editor;

CREATE UNIQUE INDEX IF NOT EXISTS uk_game_build_project_editor_version
    ON game_build (project_id, version)
    WHERE is_editor = TRUE;

ALTER TABLE test_case_version
    ADD COLUMN IF NOT EXISTS verdict_carried_from_build_id BIGINT
        REFERENCES game_build (id) ON DELETE SET NULL;
