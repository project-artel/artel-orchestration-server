-- V2__create_test_scenario.sql
-- TestScenario 챗봇이 생성한 시나리오(step 분리 결과)를 저장하는 테이블.
-- payload는 Agent가 정의하는 opaque JSON이며 Orchestration은 내부를 조회하지 않으므로 TEXT로 저장한다.
-- (향후 JSON 내부 쿼리가 필요해지면 PostgreSQL JSONB 컬럼으로 승격)

CREATE TABLE IF NOT EXISTS test_scenario (
    id               BIGSERIAL PRIMARY KEY,
    client_id        VARCHAR(255) NOT NULL,
    agent_session_id VARCHAR(255),
    payload          TEXT NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_test_scenario_client UNIQUE (client_id)
);

CREATE INDEX IF NOT EXISTS idx_test_scenario_client ON test_scenario (client_id);
