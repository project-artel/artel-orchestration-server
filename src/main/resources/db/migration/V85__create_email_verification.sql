-- 계정이 답하는 이메일 주소의 소유 확인(ARTEL-732).
--
-- 지금까지 `app_user.email` 은 OAuth 제공자가 준 값을 가입 때 한 번 넣은 것뿐이었고, 사용자가
-- 넣거나 고칠 경로가 없었다. GitHub 이 공개 이메일을 주지 않은 계정은 NULL 로 남아 초대를 받을
-- 수 없었는데, 그 상태를 스스로 벗어날 방법이 없었다.
--
-- 사용자가 직접 적은 주소를 그대로 믿을 수는 없다. 남의 주소를 적어 넣으면 그 주소로 간 초대를
-- 가져가는 길이 열린다. 그래서 주소를 받는 것과 그 주소를 계정의 것으로 삼는 것을 갈랐다.
-- `email_verification` 이 앞이고, `app_user.email_verified_at` 이 뒤다.

-- 이 주소가 이 계정의 것으로 확정된 시각. NULL 이면 아직 확인되지 않았고, 초대 수신 판정에서
-- 그 계정은 이메일이 없는 것과 같이 다뤄진다.
ALTER TABLE app_user ADD COLUMN email_verified_at TIMESTAMP WITH TIME ZONE;

-- 이미 들어 있는 주소는 GitHub 이 준 것이다. GitHub 은 계정의 공개 이메일로 **자기가 확인을 마친
-- 주소만** 고를 수 있게 하므로, 이 값들은 사용자가 적어 넣은 것이 아니라 제공자가 보증한 것이다.
-- 그래서 확인된 것으로 옮긴다. 옮기지 않으면 기존 사용자 전원이 다음 배포와 동시에 초대함을 잃고,
-- 왜 잃었는지도 알 수 없다.
--
-- 시각은 `now()` 가 아니라 `updated_at` 이다. 확인이 일어난 때는 배포 시각이 아니라 제공자가 그
-- 값을 마지막으로 써 준 때다.
--
-- 한 주소에 여러 행이 걸려 있으면 가장 오래된 계정 하나만 옮긴다. `app_user.email` 에 지금까지
-- unique 제약이 없었으므로 같은 주소를 가진 행이 실제로 여럿일 수 있고, 전부 옮기면 아래 unique
-- index 를 만들 수 없다. 남은 계정은 주소를 그대로 갖되 확인되지 않은 상태이고, 계정 설정에서
-- 다시 확인하면 된다 — 그때 이 index 가 409 로 답한다.
UPDATE app_user u
SET email_verified_at = u.updated_at
WHERE u.email IS NOT NULL
  AND u.id = (SELECT MIN(x.id) FROM app_user x WHERE lower(x.email) = lower(u.email));

-- 확인을 마친 주소는 계정 사이에 겹치지 않는다. 겹치면 초대 하나가 두 사람의 초대함에 들어간다.
--
-- 조회로 먼저 확인하지 않고 제약으로 막는 이유는 `uk_project_invitation_pending` 과 같다 —
-- 조회와 저장 사이에 다른 요청이 같은 주소를 확정하면 둘 다 통과한다. 서비스가
-- DataIntegrityViolationException 을 잡아 409 로 옮긴다.
--
-- 확인되지 않은 주소는 이 index 밖이다. 아직 아무것도 주장하지 않는 값이라 겹쳐도 해가 없다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_app_user_verified_email
    ON app_user (lower(email))
    WHERE email_verified_at IS NOT NULL;

-- 발급한 확인 토큰. 주소마다 한 건이 아니라 발급마다 한 건이라, 같은 주소로 여러 번 눌러도
-- 앞의 것이 지워지지 않는다. 마지막 것만 남기면, 먼저 받은 메일로 확인한 사람이 실패한다.
CREATE TABLE IF NOT EXISTS email_verification (
    id BIGSERIAL PRIMARY KEY,
    app_user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    -- 확인하려는 주소. 소문자로 정규화해 저장한다. 길이는 app_user.email 과 같게 맞춘다.
    email VARCHAR(320) NOT NULL,
    -- 토큰 원문은 저장하지 않는다. 이 테이블을 읽을 수 있는 사람이 남의 주소를 확정할 수 있으면
    -- 확인이 확인이 아니다. SHA-256 을 hex 로 담아 64자다.
    token_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- 이 토큰을 써서 주소가 확정된 시각. 한 번 쓴 토큰은 다시 통하지 않는다.
    consumed_at TIMESTAMP WITH TIME ZONE
);

-- 확인은 매번 이 index 로 토큰 하나를 찾는다. 해시가 곧 조회 키라 unique 로 둔다 — 서로 다른
-- 발급이 같은 해시를 갖는 것은 토큰이 겹쳤다는 뜻이고, 그때는 저장이 실패하는 편이 맞다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_email_verification_token_hash
    ON email_verification (token_hash);

-- 계정 설정 화면이 "확인을 기다리는 주소"를 물을 때 이 순서로 읽는다.
CREATE INDEX IF NOT EXISTS idx_email_verification_pending
    ON email_verification (app_user_id, consumed_at, expires_at);
