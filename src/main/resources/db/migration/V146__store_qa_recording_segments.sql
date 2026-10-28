CREATE TABLE qa_recording (
    id UUID PRIMARY KEY,
    owner_key TEXT NOT NULL UNIQUE,
    qa_run_id BIGINT REFERENCES qa_run(id) ON DELETE CASCADE,
    qa_try_id BIGINT REFERENCES qa_try(id) ON DELETE CASCADE,
    project_id BIGINT NOT NULL REFERENCES project(id),
    game_instance_id BIGINT NOT NULL REFERENCES game_instance(id),
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    error TEXT,
    CHECK ((qa_run_id IS NULL) <> (qa_try_id IS NULL))
);
CREATE TABLE qa_video_segment (
    id UUID PRIMARY KEY,
    recording_id UUID NOT NULL REFERENCES qa_recording(id) ON DELETE CASCADE,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ NOT NULL,
    object_key TEXT NOT NULL UNIQUE,
    size_bytes BIGINT NOT NULL CHECK (size_bytes BETWEEN 1 AND 33554432),
    state TEXT NOT NULL DEFAULT 'UPLOADING' CHECK (state IN ('UPLOADING','READY','EXPIRED')),
    CHECK (ended_at >= started_at)
);
CREATE INDEX qa_segment_recording ON qa_video_segment(recording_id, started_at);
CREATE TABLE qa_video_gap (
    id UUID PRIMARY KEY,
    recording_id UUID NOT NULL REFERENCES qa_recording(id) ON DELETE CASCADE,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ NOT NULL,
    reason TEXT NOT NULL,
    CHECK (ended_at >= started_at)
);
