-- MCP 로 만든 시나리오는 사람이 승인하기 전까지 QA 에 쓰지 않는다. 기존 시나리오는 그대로 쓴다.
ALTER TABLE test_scenario ADD COLUMN review_status VARCHAR(20) NOT NULL DEFAULT 'READY'
    CHECK (review_status IN ('DRAFT', 'READY'));

-- 승인을 요청한 사람. 승인 알림은 이 사람에게만 간다. 승인하면 비운다.
ALTER TABLE test_scenario ADD COLUMN review_requested_by BIGINT;
ALTER TABLE test_case ADD COLUMN review_requested_by BIGINT;

-- 승인된 시나리오를 고친 것이면 true. 이전 본문이 남지 않아 거절(삭제)할 수 없다.
ALTER TABLE test_scenario ADD COLUMN review_edits_approved BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_test_scenario_review_queue ON test_scenario (project_id, review_requested_by) WHERE review_status = 'DRAFT';
CREATE INDEX idx_test_case_review_queue ON test_case (project_id, review_requested_by) WHERE review_status = 'DRAFT';
