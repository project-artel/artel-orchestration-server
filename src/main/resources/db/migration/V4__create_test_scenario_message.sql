-- V4__create_test_scenario_message.sql
-- TestScenario 작성 중 오간 채팅 메시지를 사용자별 프라이빗 스레드로 저장한다.
--
-- 대화는 공유되지 않고 각 사용자에게만 보인다: 조회는 (test_scenario_id, app_user_id)로 스코프된다.
-- role로 사용자(USER)와 Agent(ASSISTANT)를 구분한다. content는 메시지 텍스트이며,
-- 시나리오(step 구조)는 test_scenario.payload에 별도로 저장되므로 여기 담지 않는다.

CREATE TABLE IF NOT EXISTS test_scenario_message (
    id               BIGSERIAL PRIMARY KEY,
    test_scenario_id BIGINT NOT NULL,
    app_user_id      BIGINT NOT NULL,
    role             VARCHAR(20) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content          TEXT NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tsm_test_scenario FOREIGN KEY (test_scenario_id)
        REFERENCES test_scenario (id) ON DELETE CASCADE,
    CONSTRAINT fk_tsm_app_user FOREIGN KEY (app_user_id)
        REFERENCES app_user (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_tsm_scenario_user
    ON test_scenario_message (test_scenario_id, app_user_id, created_at);
