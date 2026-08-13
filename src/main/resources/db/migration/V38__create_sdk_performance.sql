-- ARTEL-372/378: preserve every SDK performance sample, attribute it to the
-- qa_run active at receipt time, and maintain read-optimized run aggregates.

ALTER TABLE game_instance
    ADD COLUMN IF NOT EXISTS last_game_build_id BIGINT REFERENCES game_build (id);

CREATE TABLE IF NOT EXISTS sdk_device_context (
    id BIGSERIAL PRIMARY KEY,
    websocket_session_id VARCHAR(255) NOT NULL UNIQUE,
    game_instance_id BIGINT NOT NULL REFERENCES game_instance (id),
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    device_model VARCHAR(200), processor_type VARCHAR(200), processor_count INT,
    system_memory_mb INT, graphics_device_name VARCHAR(200), graphics_device_type VARCHAR(64),
    graphics_memory_mb INT, operating_system VARCHAR(200), quality_level INT,
    resolution_width INT, resolution_height INT, refresh_rate_hz DOUBLE PRECISION,
    dpi DOUBLE PRECISION, full_screen_mode VARCHAR(64), target_frame_rate INT,
    v_sync_count INT, is_editor BOOLEAN, is_debug_build BOOLEAN,
    scripting_backend VARCHAR(64), sdk_version VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_sdk_device_context_instance_received
    ON sdk_device_context (game_instance_id, received_at DESC);

CREATE TABLE IF NOT EXISTS sdk_performance_sample (
    id BIGSERIAL PRIMARY KEY,
    websocket_session_id VARCHAR(255) NOT NULL,
    game_instance_id BIGINT NOT NULL REFERENCES game_instance (id),
    qa_run_id BIGINT REFERENCES qa_run (id),
    message_id BIGINT,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    frame_count INT NOT NULL, sampled_ms DOUBLE PRECISION NOT NULL,
    mean_ms DOUBLE PRECISION NOT NULL, min_ms DOUBLE PRECISION NOT NULL,
    max_ms DOUBLE PRECISION NOT NULL, p95_ms DOUBLE PRECISION NOT NULL,
    p99_ms DOUBLE PRECISION NOT NULL, one_percent_low_fps DOUBLE PRECISION NOT NULL,
    point_one_percent_low_fps DOUBLE PRECISION NOT NULL, hitch_count INT NOT NULL,
    hitch_threshold_ms DOUBLE PRECISION NOT NULL, budget_ms DOUBLE PRECISION NOT NULL,
    is_focused BOOLEAN NOT NULL, battery_status VARCHAR(32),
    has_process BOOLEAN NOT NULL,
    process_cpu_percent DOUBLE PRECISION, process_working_set_bytes BIGINT,
    process_private_bytes BIGINT, process_managed_heap_bytes BIGINT,
    process_gen0_collections INT, process_gen1_collections INT,
    process_gen2_collections INT, process_sampled_ms DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_sdk_performance_sample_run_received
    ON sdk_performance_sample (qa_run_id, received_at) WHERE qa_run_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_sdk_performance_sample_instance_received
    ON sdk_performance_sample (game_instance_id, received_at);

-- Updated for every attributed sample. API reads this table, never raw samples.
CREATE TABLE IF NOT EXISTS sdk_performance_run_summary (
    qa_run_id BIGINT PRIMARY KEY REFERENCES qa_run (id) ON DELETE CASCADE,
    game_instance_id BIGINT NOT NULL REFERENCES game_instance (id),
    game_build_id BIGINT REFERENCES game_build (id),
    is_editor BOOLEAN,
    sample_count BIGINT NOT NULL DEFAULT 0,
    covered_ms DOUBLE PRECISION NOT NULL DEFAULT 0,
    frame_count BIGINT NOT NULL DEFAULT 0,
    frame_time_sum_ms DOUBLE PRECISION NOT NULL DEFAULT 0,
    frame_p95_weighted_sum DOUBLE PRECISION NOT NULL DEFAULT 0,
    frame_p99_weighted_sum DOUBLE PRECISION NOT NULL DEFAULT 0,
    one_low_weighted_sum DOUBLE PRECISION NOT NULL DEFAULT 0,
    hitch_count BIGINT NOT NULL DEFAULT 0,
    budget_mode_ms DOUBLE PRECISION,
    process_sample_count BIGINT NOT NULL DEFAULT 0,
    cpu_weighted_sum DOUBLE PRECISION NOT NULL DEFAULT 0,
    cpu_weight_ms DOUBLE PRECISION NOT NULL DEFAULT 0,
    cpu_percent_max DOUBLE PRECISION,
    working_set_bytes_max BIGINT,
    gen0_collections BIGINT NOT NULL DEFAULT 0,
    gen1_collections BIGINT NOT NULL DEFAULT 0,
    gen2_collections BIGINT NOT NULL DEFAULT 0,
    discharging_sample_count BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
-- 빌드 추세는 에디터 런만 뺀다. is_editor가 NULL인 런(DEVICE_CONTEXT를 못 받은 런)은
-- 에디터라고 단정할 근거가 없으므로 남긴다. `= FALSE`로 적으면 NULL까지 조용히 빠져
-- 화면에서 런이 이유 없이 사라진다.
CREATE INDEX IF NOT EXISTS idx_sdk_performance_summary_build
    ON sdk_performance_run_summary (game_build_id, qa_run_id) WHERE is_editor IS DISTINCT FROM TRUE;

-- budget_ms 최빈값을 증분으로 구하기 위한 런별 도수분포. 값 종류가 몇 개뿐이라 런당
-- 행이 한 줌이고, 원본 샘플을 매 초 훑지 않고도 최빈값을 갱신할 수 있다.
CREATE TABLE IF NOT EXISTS sdk_performance_run_budget (
    qa_run_id BIGINT NOT NULL REFERENCES qa_run (id) ON DELETE CASCADE,
    budget_ms DOUBLE PRECISION NOT NULL,
    sample_count BIGINT NOT NULL,
    PRIMARY KEY (qa_run_id, budget_ms)
);

-- Read-optimized one-second cells. Missing cells are generated as null by query.
CREATE TABLE IF NOT EXISTS sdk_performance_run_series (
    qa_run_id BIGINT NOT NULL REFERENCES qa_run (id) ON DELETE CASCADE,
    bucket_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sample_count BIGINT NOT NULL,
    frame_count BIGINT NOT NULL,
    sampled_ms DOUBLE PRECISION NOT NULL,
    frame_time_sum_ms DOUBLE PRECISION NOT NULL,
    frame_p95_weighted_sum DOUBLE PRECISION NOT NULL,
    frame_max_ms DOUBLE PRECISION NOT NULL,
    hitch_count BIGINT NOT NULL,
    process_sample_count BIGINT NOT NULL,
    cpu_weighted_sum DOUBLE PRECISION NOT NULL,
    cpu_weight_ms DOUBLE PRECISION NOT NULL,
    working_set_bytes_max BIGINT,
    focused_sample_count BIGINT NOT NULL,
    PRIMARY KEY (qa_run_id, bucket_at)
);
