CREATE TABLE project_llm_key (
    project_id BIGINT NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    service VARCHAR(20) NOT NULL CHECK (service IN ('anthropic', 'openai', 'openrouter')),
    encrypted_key TEXT,
    key_hint VARCHAR(40),
    verified_at TIMESTAMP WITH TIME ZONE,
    owner_user_id BIGINT,
    PRIMARY KEY (project_id, service),
    FOREIGN KEY (owner_user_id, service)
        REFERENCES user_llm_key (app_user_id, service) ON DELETE CASCADE,
    FOREIGN KEY (project_id, owner_user_id)
        REFERENCES project_member (project_id, app_user_id) ON DELETE CASCADE,
    CHECK (
        (owner_user_id IS NULL AND encrypted_key IS NOT NULL AND key_hint IS NOT NULL AND verified_at IS NOT NULL)
        OR
        (owner_user_id IS NOT NULL AND encrypted_key IS NULL AND key_hint IS NULL AND verified_at IS NULL)
    )
);

CREATE INDEX project_llm_key_owner_idx
    ON project_llm_key (owner_user_id, service)
    WHERE owner_user_id IS NOT NULL;

ALTER TABLE qa_run DROP CONSTRAINT qa_run_key_source_check,
    ADD CHECK (key_source IN ('platform', 'user', 'project'));
ALTER TABLE qa_try DROP CONSTRAINT qa_try_key_source_check,
    ADD CHECK (key_source IN ('platform', 'user', 'project'));
ALTER TABLE llm_usage DROP CONSTRAINT llm_usage_key_source_check,
    ADD CHECK (key_source IN ('platform', 'user', 'project'));
