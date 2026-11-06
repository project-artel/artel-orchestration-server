-- instance_key 제거. SDK는 이제 로그인해서 받은 토큰으로 인증하고, 인스턴스는 sdk_uuid로
-- 자신을 밝힌다. 키는 더 이상 읽는 곳이 없다.
--
-- 되돌리려면 컬럼을 다시 만드는 것으로는 부족하고 키를 재발급해야 한다. V20과 나눠 둔 이유가
-- 그것이다. 배포된 구버전 SDK는 이 시점부터 등록에 실패한다.
ALTER TABLE game_instance
    DROP CONSTRAINT IF EXISTS uk_game_instance_key;

ALTER TABLE game_instance
    DROP COLUMN IF EXISTS instance_key;
