-- V3__create_test_scenario.sql
-- TestScenario 챗봇이 생성한 시나리오(step 분리 결과)를 저장하는 테이블.
--
-- 한 프로젝트는 여러 시나리오를 가질 수 있다(Project 1:N TestScenario) — UNIQUE 없음.
-- project_id는 팀에서 개발 중인 Project 도메인을 논리적으로 참조하지만, 해당 테이블이 아직 없으므로
-- 실제 FK 제약은 걸지 않고 컬럼과 인덱스만 둔다(추후 project 테이블 확정 시 FK 추가).
-- payload는 Agent가 정의하는 시나리오 JSON을 그대로 담으며 JSONB로 저장한다.
-- clientId/agentSessionId는 각각 전송 계층/Redis(TTL) 관리 대상이라 영속화하지 않는다.

CREATE TABLE IF NOT EXISTS test_scenario (
    id         BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL,
    payload    JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_test_scenario_project ON test_scenario (project_id);
