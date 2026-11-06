-- CLI 가 쓰는, 폐기할 수 있는 자격증명(ARTEL-780).
--
-- 지금 이 서버가 내는 토큰은 전부 상태 없는 JWT 라 서명과 만료 말고는 아무 근거가 없다. 한 번
-- 발급하면 만료를 기다리는 것 외에 회수할 방법이 없다. 노트북을 잃어버린 사람이 할 수 있는 일이
-- `ARTEL_JWT_SECRET` 을 바꿔 전원을 로그아웃시키는 것뿐이라는 뜻이다.
--
-- CLI 토큰은 그 반대다. 살아 있는지를 매 요청에 이 테이블로 확인하므로, `revoked_at` 을 채우는
-- 것만으로 그 다음 요청이 401 이 된다. 캐시를 두지 않는 이유가 이것이다 — 캐시를 두면 폐기가
-- TTL 만큼 늦게 듣는다.
CREATE TABLE IF NOT EXISTS cli_token (
    id BIGSERIAL PRIMARY KEY,
    app_user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    -- 사람이 붙이는 이름("노트북", "CI"). 어느 토큰을 폐기할지 고르는 데 쓰는 유일한 단서다.
    name VARCHAR(100) NOT NULL,
    -- 토큰 원문은 저장하지 않는다. 이 테이블을 읽을 수 있는 사람이 남의 계정으로 API 를 부를 수
    -- 있으면 폐기가 무의미하다. `email_verification.token_hash` 와 같은 모양으로 SHA-256 을 hex
    -- 64자로 담는다. 해시하는 대상은 `artel_` 접두사를 포함한 전체 문자열이라, 조회 키가 사용자가
    -- 붙여 넣는 값과 정확히 같다.
    token_hash CHAR(64) NOT NULL,
    -- 이 토큰이 여는 범위. 이번에 아무 코드도 읽지 않지만 nullable 로 두지 않는다. 좁은 scope 가
    -- 생기는 날 기존 행이 NULL(= 무슨 뜻인지 아무도 모르는 값)이 아니라 'full'(= 전부 열린다)이라고
    -- 말하고 있어야 한다.
    scope VARCHAR(64) NOT NULL DEFAULT 'full',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 마지막으로 이 토큰이 요청을 통과시킨 시각. 5분 해상도다 — 요청마다 쓰면 읽기 전용 요청이
    -- 전부 쓰기가 된다. "마지막으로 언제 썼나"에는 답하고 "몇 번 썼나"에는 답하지 않는다.
    last_used_at TIMESTAMP WITH TIME ZONE,
    -- NULL 이면 만료가 없다. 계약이 만료 없는 토큰을 허용하고, 그 상태의 표현이 NULL 이다.
    -- 사람이 큰 숫자를 적어 사실상 영구 토큰을 만들면서 만료가 있는 척하는 것은 서비스가 막는다
    -- (expiresInDays 상한 365). 영구가 필요하면 NULL 을 명시적으로 골라야 한다.
    expires_at TIMESTAMP WITH TIME ZONE,
    -- 폐기된 시각. 행을 지우지 않고 남기는 이유는 목록 화면이 "폐기했다"를 보여줘야 하기 때문이다.
    -- 지워 버리면 사용자는 자기가 폐기한 것인지 애초에 없던 것인지 구분할 수 없다.
    revoked_at TIMESTAMP WITH TIME ZONE
);

-- 인증은 매 요청에 이 index 로 토큰 하나를 찾는다. 해시가 곧 조회 키라 unique 로 둔다 — 서로 다른
-- 발급이 같은 해시를 갖는 것은 32바이트 난수가 겹쳤다는 뜻이고, 그때는 저장이 실패하는 편이 맞다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_cli_token_token_hash
    ON cli_token (token_hash);

-- 목록 화면이 자기 토큰을 최신 순으로 읽는 순서.
CREATE INDEX IF NOT EXISTS idx_cli_token_owner
    ON cli_token (app_user_id, created_at DESC);
