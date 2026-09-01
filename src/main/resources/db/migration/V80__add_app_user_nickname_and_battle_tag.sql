-- 사용자가 고른 표시 이름과 BattleTag(ARTEL-730).
--
-- nickname은 oauth_identity.display_name과 다른 값이다. display_name은 로그인마다 제공자 값으로
-- 덮어써지므로(`OAuthUserService.refreshedWith`) 사용자가 직접 고른 이름을 담을 자리가 없었다.
-- NULL이면 아직 고르지 않은 것이다.
--
-- battle_tag는 선택 값이라 NULL을 허용한다. 형식(1~24자 + `#` + 숫자 1~8자) 검증은 애플리케이션이
-- API 경계에서 한다 — CHECK 제약으로 걸면 형식을 나중에 넓힐 때마다 마이그레이션이 필요해진다.
--
-- 둘 다 uniqueness 제약을 두지 않는다. nickname 유일성은 이 이슈의 범위 밖이다.
ALTER TABLE app_user ADD COLUMN nickname VARCHAR(64);
ALTER TABLE app_user ADD COLUMN battle_tag VARCHAR(32);
