-- 저작이 추천한 TC(ARTEL-1273 의 TC 초안을 사용자가 저장하기 전까지 들고 있는 자리).
-- 저장하면 test_case 가 생기고 시나리오 스텝의 recommendation_id 가 그 TC 의 case_id 로 바뀐다.
CREATE TABLE case_recommendation (
    id             BIGSERIAL PRIMARY KEY,
    project_id     BIGINT      NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    test_run_id    BIGINT      NOT NULL REFERENCES test_run (id) ON DELETE CASCADE,
    scene          TEXT        NOT NULL DEFAULT '',
    precondition   TEXT        NOT NULL DEFAULT '',
    step           TEXT        NOT NULL DEFAULT '',
    expected_value TEXT        NOT NULL,
    why            TEXT        NOT NULL DEFAULT '',
    status         VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SAVED', 'DISCARDED')),
    test_case_id   BIGINT      REFERENCES test_case (id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_case_recommendation_run ON case_recommendation (test_run_id, status);
