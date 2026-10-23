-- QA 판정의 추가 검토와 사람의 확정(ARTEL-1254).
--
-- QA 실행이 끝나면 코드 검사(ARTEL-1252)가 사람이 다시 볼 판정을 고른다. 그 판정마다 한 행을 두고(decision NULL),
-- 사람이 통과 · 실패로 확정하면 decision 과 메모를 채운다. 실행마다 남은 검토 수를 목록이 바로 셀 수 있게 하려고
-- 고른 결과를 저장한다 — 코드 검사는 `PULSE` 를 처음부터 접어야 해서 목록마다 다시 계산하기에는 무겁다.
--
-- AI 판정 원본은 qa_log 의 STATUS 행에 그대로 남는다. 이 표는 그 위에 사람의 결정만 얹는다. 사람이 고른 판정이
-- 아니어도(추가 검토가 아닌 판정) 사람이 확정하면 행이 생긴다.
CREATE TABLE IF NOT EXISTS qa_verdict_review (
    qa_log_id    BIGINT      PRIMARY KEY REFERENCES qa_log (id) ON DELETE CASCADE,
    qa_run_id    BIGINT      NOT NULL REFERENCES qa_run (id) ON DELETE CASCADE,
    qa_try_id    BIGINT      NOT NULL REFERENCES qa_try (id) ON DELETE CASCADE,
    test_case_id BIGINT      REFERENCES test_case (id) ON DELETE SET NULL,
    -- 코드 검사가 추가 검토로 골랐나. 사람이 그냥 확정한 판정은 false 다.
    flagged      BOOLEAN     NOT NULL,
    ai_passed    BOOLEAN     NOT NULL,
    decision     VARCHAR(10) CHECK (decision IN ('PASSED', 'FAILED')),
    note         TEXT,
    decided_by   BIGINT      REFERENCES app_user (id) ON DELETE SET NULL,
    decided_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 실행마다 남은 추가 검토 수.
CREATE INDEX IF NOT EXISTS idx_qa_verdict_review_pending ON qa_verdict_review (qa_run_id) WHERE flagged AND decision IS NULL;

-- 같은 TC 의 사람 확정 기록(다음 QA 에 넘길 것, ARTEL-1256).
CREATE INDEX IF NOT EXISTS idx_qa_verdict_review_case ON qa_verdict_review (test_case_id) WHERE decision IS NOT NULL;
