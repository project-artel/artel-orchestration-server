-- 사용자가 고른 이름과, 같은 이름을 쓰는 사람을 가르는 user_tag(ARTEL-730).
--
-- nickname은 oauth_identity.display_name과 다른 값이다. display_name은 로그인마다 제공자 값으로
-- 덮어써지므로(`OAuthUserService.refreshedWith`) 사용자가 직접 고른 이름을 담을 자리가 없었다.
--
-- user_tag는 0으로 채운 숫자 문자열이고, 서버가 배정한다 — 클라이언트는 보낼 수 없다. 기본 네
-- 자리이며, 한 nickname 아래에서 네 자리가 모두 나가면 다섯 자리로 늘어난다. 길이가 다르면 다른
-- 값이라 `0042`와 `00042`가 겹치지 않는다. VARCHAR(16)은 그 확장 여지를 남기면서 컬럼을 묶는다.
-- 화면에 나가는 `nickname#user_tag`는 클라이언트가 두 값을 붙여 만든다.
--
-- 둘 다 NOT NULL이다 — 이름이 없는 사용자는 없다. 그래서 컬럼을 nullable로 먼저 붙이고 기존 행을
-- 채운 뒤 NOT NULL로 조인다. 곧바로 NOT NULL을 걸면 이미 있는 행 때문에 ALTER가 실패한다.
ALTER TABLE app_user ADD COLUMN nickname VARCHAR(64);
ALTER TABLE app_user ADD COLUMN user_tag VARCHAR(16);

-- 기존 행의 nickname은 제공자가 준 이름에서 만든다. COALESCE가 세 단계인 것은 그 이름이 비어 있을
-- 수 있기 때문이다 — display_name이 공백뿐이면 oauth_identity.login으로, 그것도 비어 있으면 'user'로
-- 떨어뜨린다. 이름이 겹쳐도 user_tag가 사람을 가르므로 같은 fallback을 여럿이 써도 해가 없다.
-- `OAuthUserService.nicknameFrom`이 새 사용자에게 적용하는 순서와 같다.
UPDATE app_user
SET nickname = COALESCE(
    NULLIF(TRIM(LEFT(app_user.display_name, 64)), ''),
    NULLIF(TRIM(LEFT((
        SELECT identity.login
        FROM oauth_identity AS identity
        WHERE identity.app_user_id = app_user.id
        ORDER BY identity.last_login_at DESC, identity.id
        LIMIT 1
    ), 64)), ''),
    'user'
);

-- 번호는 같은 nickname 안에서 id 순으로 0부터 매긴다(애플리케이션도 0부터 배정한다).
-- LPAD의 길이를 GREATEST(4, ...)로 두면 9999까지는 네 자리로 채우고 그 뒤부터는 자릿수를 그대로
-- 살린다 — 자리가 모자랄 때 늘어나는 규칙이 특별 분기 없이 나온다.
WITH assigned AS (
    SELECT
        id,
        (ROW_NUMBER() OVER (PARTITION BY nickname ORDER BY id) - 1)::text AS number
    FROM app_user
)
UPDATE app_user
SET user_tag = LPAD(assigned.number, GREATEST(4, LENGTH(assigned.number)), '0')
FROM assigned
WHERE app_user.id = assigned.id;

ALTER TABLE app_user ALTER COLUMN nickname SET NOT NULL;
ALTER TABLE app_user ALTER COLUMN user_tag SET NOT NULL;

-- 같은 nickname 아래에서 user_tag가 사람을 가른다. 이 제약이 (nickname, user_tag) 한 쌍을 한 행으로
-- 만들어, 그 쌍만으로 사람을 찾을 수 있게 한다. 동시에 두 요청이 같은 번호를 고르는 것도 여기서 막힌다.
ALTER TABLE app_user ADD CONSTRAINT uk_app_user_nickname_user_tag UNIQUE (nickname, user_tag);
