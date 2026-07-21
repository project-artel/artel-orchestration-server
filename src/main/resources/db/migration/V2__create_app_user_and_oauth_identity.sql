-- Artel 사용자 본체. OAuth 제공자와 무관한 안정적인 식별자를 가지며,
-- 이 id가 JWT의 sub 클레임으로 노출된다.
CREATE TABLE IF NOT EXISTS app_user (
    id BIGSERIAL PRIMARY KEY,
    display_name VARCHAR(255) NOT NULL,
    email VARCHAR(320),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 외부 OAuth 제공자에서 정규화한 신원. 하나의 app_user에 여러 제공자를 연결할 수 있도록
-- app_user와 1:N으로 분리했다. (provider, provider_user_id)가 제공자 계정을 유일하게 식별한다.
CREATE TABLE IF NOT EXISTS oauth_identity (
    id BIGSERIAL PRIMARY KEY,
    app_user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    provider VARCHAR(64) NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    login VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    avatar_url VARCHAR(2048),
    email VARCHAR(320),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_oauth_identity_provider_identity UNIQUE (provider, provider_user_id)
);

CREATE INDEX IF NOT EXISTS idx_oauth_identity_app_user
    ON oauth_identity (app_user_id);

CREATE INDEX IF NOT EXISTS idx_oauth_identity_provider_login
    ON oauth_identity (provider, login);
