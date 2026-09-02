-- 프로젝트 밖의 등급. project_member 의 OWNER 와 MEMBER 는 한 프로젝트 안에서만 뜻이 있어
-- "모든 프로젝트를 본다" 를 표현하지 못한다. 그 자리를 여기 둔다.
--
-- DEVELOPER 는 참여하지 않은 프로젝트의 조회를 통과한다. 쓰기는 열지 않는다 — 프로젝트 삭제,
-- 기획서 업로드, 기대 판정 라벨 수정은 이 등급과 무관하게 project_member 를 그대로 요구한다.
--
-- 등급을 주는 화면도 API 도 없다. 값은 운영 DB 에서 직접 바꾸며, 방법은
-- docs/platform-role.md 에 있다. 그래서 이 마이그레이션은 컬럼만 만들고 사람은 담지 않는다.
ALTER TABLE app_user ADD COLUMN platform_role VARCHAR(32) NOT NULL DEFAULT 'USER';
