-- V22__create_test_run_message.sql
-- 저작 챗봇 대화를 TestRun(실행 세트) 단위 프라이빗 스레드로 저장한다(ARTEL-206 Step 6).
--
-- 이전에는 test_scenario_message로 시나리오 1개당 대화를 저장했지만, v2 저작은 한 번의 대화로
-- 여러 시나리오를 추가·수정하므로 대화의 주체가 시나리오가 아니라 "런"이다. 시나리오를 바꿔 봐도
-- 같은 런 안에서는 대화가 이어져야 한다. 그래서 스레드 키를 (test_run_id, app_user_id)로 옮긴다.
--
-- 대화는 공유되지 않고 각 사용자에게만 보인다. role로 사용자(USER)와 Agent(ASSISTANT)를 구분한다.
-- 시나리오 본체(케이스 조합)는 test_scenario/test_scenario_case에 별도로 저장되므로 여기 담지 않는다.
-- pre-prod(운영 데이터 없음)라 이전 없이 옛 테이블을 드롭한다.

DROP TABLE IF EXISTS test_scenario_message;

CREATE TABLE IF NOT EXISTS test_run_message (
    id           BIGSERIAL PRIMARY KEY,
    test_run_id  BIGINT NOT NULL,
    app_user_id  BIGINT NOT NULL,
    role         VARCHAR(20) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content      TEXT NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_trm_test_run FOREIGN KEY (test_run_id)
        REFERENCES test_run (id) ON DELETE CASCADE,
    CONSTRAINT fk_trm_app_user FOREIGN KEY (app_user_id)
        REFERENCES app_user (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_trm_run_user
    ON test_run_message (test_run_id, app_user_id, created_at);
