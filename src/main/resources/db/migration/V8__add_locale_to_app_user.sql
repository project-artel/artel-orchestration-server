-- 사용자가 홈 UI에서 고른 표시 언어. NULL이면 아직 고르지 않은 것이고,
-- 클라이언트가 브라우저 언어로 기본값을 정한다. 허용 값 검증은 애플리케이션이 한다.
ALTER TABLE app_user ADD COLUMN locale VARCHAR(8);
