-- OAuth identities normalized from external login providers.
CREATE TABLE IF NOT EXISTS oauth_user (
    id BIGSERIAL PRIMARY KEY,
    provider VARCHAR(64) NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    login VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    avatar_url VARCHAR(2048),
    email VARCHAR(320),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_oauth_user_provider_identity UNIQUE (provider, provider_user_id)
);

CREATE INDEX IF NOT EXISTS idx_oauth_user_provider_login
    ON oauth_user (provider, login);
