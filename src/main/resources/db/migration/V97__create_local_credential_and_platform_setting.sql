-- 직접 설치(self-host)한 ARTEL 이 GitHub OAuth 없이도 돌도록, 이메일과 비밀번호 로그인, ADMIN
-- 등급, 관리 화면에서 넣는 OpenRouter API key 를 담는 자리를 만든다.
--
-- platform_role 에 ADMIN 이 새로 생기지만 컬럼은 VARCHAR(32) 이고 CHECK 제약이 없어 스키마를
-- 바꿀 것이 없다. 누가 ADMIN 이 되는지는 코드가 정한다 — 첫 가입자다(docs/platform-role.md).

-- 계정을 막는 스위치. 행을 지우지 않는 이유는 qa_try.started_by 와 qa_run.started_by 가 ON DELETE
-- 절 없이 app_user 를 참조해, QA 를 한 번이라도 돌린 사람은 지울 수 없기 때문이다.
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS disabled BOOLEAN NOT NULL DEFAULT FALSE;

-- 비밀번호 로그인 자격증명. app_user 하나에 최대 한 행이다.
--
-- email 을 app_user.email 과 따로 두는 이유는 뜻이 달라서다. app_user.email 은 초대를 받는 연락
-- 주소이고 확인 절차(email_verification)를 거쳐야 그 계정의 것이 된다. 여기 email 은 로그인할 때
-- 적는 이름이라, 확인 여부와 무관하게 한 계정만 가져야 한다.
CREATE TABLE IF NOT EXISTS local_credential (
    app_user_id BIGINT PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    -- 소문자로 정규화해 넣는다. 아래 unique index 도 lower() 를 씌워 대소문자만 다른 두 계정을 막는다.
    email VARCHAR(320) NOT NULL,
    -- BCrypt 해시 원문(`$2a$10$...`, 60자). 비밀번호 원문은 어디에도 저장하지 않는다.
    password_hash VARCHAR(100) NOT NULL,
    -- 관리자가 만든 계정과 관리자가 초기화한 비밀번호는 true 로 시작한다. true 인 동안에는
    -- GET /api/auth/me 와 POST /api/auth/password 말고는 전부 403 이다.
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    password_changed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_local_credential_email
    ON local_credential (lower(email));

-- 관리 화면에서 바꾸는 설치 단위 설정. 키 하나에 값 하나다.
--
-- encrypted 가 true 인 값은 AES-GCM 암호문이고, 키는 ARTEL_SECRETS_KEY 에서 만든다. 이 테이블을
-- 읽을 수 있는 사람이 OpenRouter API key 를 곧바로 쓸 수 없게 하려는 것이다.
CREATE TABLE IF NOT EXISTS platform_setting (
    setting_key VARCHAR(100) PRIMARY KEY,
    setting_value TEXT NOT NULL,
    encrypted BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
