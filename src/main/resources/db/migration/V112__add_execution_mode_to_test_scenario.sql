--------------------------------------------------------------------------------
-- test_scenario 에 execution_mode 를 둔다 (ARTEL-1028)
--
-- ORDERED — 지금까지의 시나리오. steps 가 순서 있는 행위 목록이고 QA agent 가 그 순서대로 수행한다.
-- FREE    — TC 묶음. step 하나가 TC 하나이고, 순서와 검증 방법은 QA agent 가 정한다.
--
-- 기존 행은 전부 ORDERED 다.
--------------------------------------------------------------------------------

ALTER TABLE test_scenario
    ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT 'ORDERED';

ALTER TABLE test_scenario
    ADD CONSTRAINT test_scenario_execution_mode_check CHECK (execution_mode IN ('ORDERED', 'FREE'));
