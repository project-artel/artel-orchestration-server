--------------------------------------------------------------------------------
-- V122. 공유 글이 다루는 빌드 버전 (ARTEL-1114)
--------------------------------------------------------------------------------
-- 글을 쓸 때 어느 게임 빌드 버전의 이야기인지 고른다. 게시판은 그 버전으로 거르고, 본문의 TC 참조도 그 버전의 TC
-- 에서 고른다. 비어 있으면 버전을 정하지 않은 글이다. 빌드를 지우면 글은 남고 버전만 빈다.

ALTER TABLE issue_discussion ADD COLUMN IF NOT EXISTS game_build_id BIGINT REFERENCES game_build (id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS ix_issue_discussion_project_build ON issue_discussion (project_id, game_build_id);
