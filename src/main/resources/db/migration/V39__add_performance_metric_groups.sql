-- ARTEL-435: 지표군이 늘어도 마이그레이션이 붙지 않는 저장 구조.
--
-- V38은 지표마다 컬럼 하나였다. 새 군 하나가 ALTER TABLE 하나를 부르는 구조라
-- "지표군은 계속 늘어난다"는 전제와 맞지 않는다. 여기서는 군과 잎을 값으로 다뤄
-- 군 추가에 스키마 변경이 필요 없게 한다. 기존 컬럼은 건드리지 않는다.

-- SDK가 수집을 *시도하는* 군 이름. UNSUPPORTED("재려다 못 쟀다")와
-- NOT_REPORTED("이 SDK는 이 군을 모른다")를 가르는 유일한 근거다. 이 컬럼이 NULL인
-- 연결은 이 필드 이전 SDK이므로 새 군이 전부 NOT_REPORTED가 되고, 그것이 정확한 답이다.
-- 서버에 SDK 버전 표를 두는 대안은 릴리스마다 서버를 고쳐야 하고 잊으면 조용히 틀린다.
ALTER TABLE sdk_device_context
    ADD COLUMN IF NOT EXISTS collected_groups TEXT[];

-- 런 요약도 같은 목록을 든다. 빌드 추세는 런이 여럿이라, 런마다 DEVICE_CONTEXT를 한 번씩
-- 더 읽으면 조회가 런 수에 비례해 느려진다. is_editor를 요약에 복사해 둔 것과 같은 이유다.
ALTER TABLE sdk_performance_run_summary
    ADD COLUMN IF NOT EXISTS collected_groups TEXT[];

-- 원본 군 payload. 받은 그대로 둔다 — 이해한 것만 남기면 롤업 규칙이 바뀌었을 때
-- 복원할 수 없다. 조회 경로는 이 테이블을 보지 않는다.
CREATE TABLE IF NOT EXISTS sdk_performance_sample_group (
    sample_id BIGINT NOT NULL REFERENCES sdk_performance_sample (id) ON DELETE CASCADE,
    group_name VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    PRIMARY KEY (sample_id, group_name)
);

-- 런에서 그 군의 값이 실려 온 표본 수. sampleRatio의 분자이고 MEASURED 판정의 근거다.
CREATE TABLE IF NOT EXISTS sdk_performance_run_group (
    qa_run_id BIGINT NOT NULL REFERENCES qa_run (id) ON DELETE CASCADE,
    group_name VARCHAR(64) NOT NULL,
    sample_count BIGINT NOT NULL,
    -- 값을 실은 표본만 센다. 봉투만 오고 숫자 잎이 없는 군은 0으로 남아 UNSUPPORTED가 된다.
    -- 숫자가 아닌 군 속성. 지금은 renderCounters.source(PROFILER_RECORDER /
    -- EDITOR_UNITY_STATS)뿐이다. 출처가 다른 같은 이름의 값을 한 필드에 담지 않기 위해
    -- 응답에 실어 화면이 검증할 수 있게 한다.
    source VARCHAR(32),
    PRIMARY KEY (qa_run_id, group_name)
);

-- 런 롤업. 숫자 잎 하나가 한 행이다. 군이 늘면 행만 늘고 열은 그대로다.
CREATE TABLE IF NOT EXISTS sdk_performance_run_group_metric (
    qa_run_id BIGINT NOT NULL REFERENCES qa_run (id) ON DELETE CASCADE,
    group_name VARCHAR(64) NOT NULL,
    leaf_path VARCHAR(128) NOT NULL,
    sample_count BIGINT NOT NULL,
    value_sum DOUBLE PRECISION NOT NULL,
    value_max DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (qa_run_id, group_name, leaf_path)
);

-- 시계열 셀. V38의 sdk_performance_run_series와 같은 1초 격자를 쓴다.
CREATE TABLE IF NOT EXISTS sdk_performance_run_series_group (
    qa_run_id BIGINT NOT NULL REFERENCES qa_run (id) ON DELETE CASCADE,
    bucket_at TIMESTAMP WITH TIME ZONE NOT NULL,
    group_name VARCHAR(64) NOT NULL,
    leaf_path VARCHAR(128) NOT NULL,
    sample_count BIGINT NOT NULL,
    value_sum DOUBLE PRECISION NOT NULL,
    value_max DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (qa_run_id, bucket_at, group_name, leaf_path)
);

-- 원본 표본 보존 정책(ARTEL-434에서 결정). 원본은 유한 보존, 요약·시계열은 영구 보존.
-- 삭제 잡이 이 인덱스로 오래된 행을 찾는다. qa_run_id가 없는(런 밖) 표본도 같이 지운다.
CREATE INDEX IF NOT EXISTS idx_sdk_performance_sample_received
    ON sdk_performance_sample (received_at);
