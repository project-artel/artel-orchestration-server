CREATE TABLE IF NOT EXISTS qa_try (
    id BIGSERIAL PRIMARY KEY,
    test_scenario_id BIGINT NOT NULL REFERENCES test_scenario (id),
    game_instance_id BIGINT NOT NULL REFERENCES game_instance (id),
    started_by BIGINT NOT NULL REFERENCES app_user (id),
    agent_session_id VARCHAR(255),
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('STARTING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_qa_try_completed_at CHECK (
        (status IN ('STARTING', 'RUNNING') AND completed_at IS NULL)
        OR (status IN ('COMPLETED', 'FAILED', 'CANCELLED') AND completed_at IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_qa_try_instance_status
    ON qa_try (game_instance_id, status);

CREATE UNIQUE INDEX IF NOT EXISTS uk_qa_try_active_instance
    ON qa_try (game_instance_id)
    WHERE status IN ('STARTING', 'RUNNING');

CREATE TABLE IF NOT EXISTS qa_log (
    id BIGSERIAL PRIMARY KEY,
    qa_try_id BIGINT NOT NULL REFERENCES qa_try (id) ON DELETE CASCADE,
    message_id VARCHAR(255),
    correlation_id VARCHAR(255),
    direction VARCHAR(30) NOT NULL CHECK (
        direction IN ('AGENT_TO_ORCHE', 'ORCHE_TO_AGENT', 'ORCHE_TO_SDK', 'SDK_TO_ORCHE', 'ORCHE_INTERNAL')
    ),
    type VARCHAR(30) NOT NULL CHECK (
        type IN ('LOG', 'ACTION', 'ACTION_RESULT', 'GAME_STATE', 'STATUS', 'ERROR')
    ),
    message TEXT,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_qa_log_try_id_desc
    ON qa_log (qa_try_id, id DESC);

CREATE UNIQUE INDEX IF NOT EXISTS uk_qa_log_message_direction
    ON qa_log (qa_try_id, direction, message_id)
    WHERE message_id IS NOT NULL;
