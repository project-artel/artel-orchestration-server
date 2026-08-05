-- QA 실행을 런(TR) 단위로 묶는다. (ARTEL-259)
--
-- 한 qa_run = 한 세션이 런의 시나리오들을 순차 실행하는 실행 단위. qa_try는 여전히 시나리오당이고
-- (통계 ARTEL-243·이슈 ARTEL-245/246·run_config ARTEL-239가 전부 qa_try 기준이라 그대로 보존),
-- 이제 qa_run_id로 부모 런을 가리킨다. run_config는 세션 공통 설정 스냅샷.
CREATE TABLE IF NOT EXISTS qa_run (
    id BIGSERIAL PRIMARY KEY,
    test_run_id BIGINT NOT NULL REFERENCES test_run (id),
    game_instance_id BIGINT NOT NULL REFERENCES game_instance (id),
    started_by BIGINT NOT NULL REFERENCES app_user (id),
    agent_session_id VARCHAR(255),
    status VARCHAR(20) NOT NULL
        CHECK (status IN ('STARTING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    run_config JSONB NOT NULL DEFAULT '{}'::jsonb,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_qa_run_completed_at CHECK (
        (status IN ('STARTING', 'RUNNING') AND completed_at IS NULL)
        OR (status IN ('COMPLETED', 'FAILED', 'CANCELLED') AND completed_at IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_qa_run_instance_status ON qa_run (game_instance_id, status);

-- 한 게임 인스턴스에 활성 런은 하나만(기존 qa_try 활성 유니크와 같은 취지 — 시나리오는 순차라
-- 활성 qa_try도 항상 하나다).
CREATE UNIQUE INDEX IF NOT EXISTS uk_qa_run_active_instance
    ON qa_run (game_instance_id)
    WHERE status IN ('STARTING', 'RUNNING');

-- qa_try를 부모 런에 연결한다. 기존 qa_try 행은 런 없이 단독 실행이었으므로 nullable로 둔다
-- (무손상; 단독 실행 경로는 하위호환으로 유지).
ALTER TABLE qa_try ADD COLUMN IF NOT EXISTS qa_run_id BIGINT REFERENCES qa_run (id);
CREATE INDEX IF NOT EXISTS idx_qa_try_run_id ON qa_try (qa_run_id);
