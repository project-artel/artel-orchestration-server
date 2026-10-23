CREATE TABLE demonstration_session (
    id UUID PRIMARY KEY,
    project_id BIGINT NOT NULL,
    test_case_id BIGINT NOT NULL,
    scenario_id BIGINT NOT NULL,
    game_instance_id BIGINT NOT NULL,
    game_build_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    generation BIGINT NOT NULL,
    sdk_connection_id TEXT NOT NULL,
    case_json TEXT NOT NULL,
    case_hash TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('RECORDING','FINALIZING','SEALED','INTERRUPTED')),
    control_ready BOOLEAN NOT NULL DEFAULT FALSE,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    source_hash TEXT,
    sealed_event_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    stopped_at TIMESTAMPTZ
);
CREATE INDEX demonstration_session_case ON demonstration_session(project_id, test_case_id, created_at DESC);
CREATE INDEX demonstration_session_active ON demonstration_session(game_instance_id) WHERE status = 'RECORDING';
CREATE TABLE demonstration_event (
    id BIGSERIAL PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES demonstration_session(id),
    kind TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    request_id BIGINT UNIQUE,
    command_sequence BIGINT,
    command_status TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(session_id, command_sequence)
);
CREATE INDEX demonstration_event_session ON demonstration_event(session_id, id);
CREATE TABLE demonstration_source_revision (
    session_id UUID NOT NULL REFERENCES demonstration_session(id),
    source_hash TEXT NOT NULL,
    sealed_event_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(session_id, source_hash)
);
CREATE TABLE verification_procedure (
    id BIGSERIAL PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES demonstration_session(id),
    revision INTEGER NOT NULL,
    source_hash TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('DRAFT','APPROVED','SUPERSEDED','REVOKED')),
    procedure_json TEXT NOT NULL,
    approved_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(session_id, revision)
);
CREATE TABLE qa_procedure_usage (
    qa_try_id BIGINT NOT NULL,
    procedure_id BIGINT NOT NULL REFERENCES verification_procedure(id),
    snapshot_json TEXT NOT NULL,
    PRIMARY KEY(qa_try_id, procedure_id)
);
ALTER TABLE knowledge DROP CONSTRAINT knowledge_source_check;
ALTER TABLE knowledge ADD CONSTRAINT knowledge_source_check CHECK (source IN ('DOCS','QA','DEMONSTRATION'));
