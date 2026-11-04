-- 모델 API 오류로 멈춘 QA 를 실패가 아니라 미판정으로 끝낸다(ARTEL-1371).
--
-- agent 는 크레딧 부족 · 인증 · 없는 모델 오류가 나면 그 시나리오를 result=UNDECIDED 로 닫고 런을 멈춘다
-- (agent ARTEL-1370). 게임을 검증한 결과가 아니므로 FAILED 로 두면 통과율 · 실패 집계 · TC 판정이
-- 오염된다. 그래서 끝 상태 UNDECIDED 를 더하고, 까닭(stop_*)을 함께 둔다.
--
-- stop_code 는 agent 의 오류 코드(llm_credit_exhausted · llm_auth_failed · llm_model_unavailable)이거나,
-- 앞 시나리오가 멈춰 시작하지 못한 시도의 not_started 다.

ALTER TABLE qa_try DROP CONSTRAINT IF EXISTS qa_try_status_check;
ALTER TABLE qa_try ADD CONSTRAINT qa_try_status_check
    CHECK (status IN ('PENDING', 'STARTING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'UNDECIDED'));
ALTER TABLE qa_try DROP CONSTRAINT IF EXISTS ck_qa_try_completed_at;
ALTER TABLE qa_try ADD CONSTRAINT ck_qa_try_completed_at CHECK (
    (status IN ('PENDING', 'STARTING', 'RUNNING') AND completed_at IS NULL)
    OR (status IN ('COMPLETED', 'FAILED', 'CANCELLED', 'UNDECIDED') AND completed_at IS NOT NULL)
);
ALTER TABLE qa_try
    ADD COLUMN stop_code VARCHAR(64),
    ADD COLUMN stop_provider VARCHAR(32),
    ADD COLUMN stop_message TEXT;

ALTER TABLE qa_run DROP CONSTRAINT IF EXISTS qa_run_status_check;
ALTER TABLE qa_run ADD CONSTRAINT qa_run_status_check
    CHECK (status IN ('STARTING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'UNDECIDED'));
ALTER TABLE qa_run DROP CONSTRAINT IF EXISTS ck_qa_run_completed_at;
ALTER TABLE qa_run ADD CONSTRAINT ck_qa_run_completed_at CHECK (
    (status IN ('STARTING', 'RUNNING') AND completed_at IS NULL)
    OR (status IN ('COMPLETED', 'FAILED', 'CANCELLED', 'UNDECIDED') AND completed_at IS NOT NULL)
);
-- 판정이 하나도 없이 오류로 끝난 런은 지우지 않고 버림으로 표시만 한다. QA 히스토리 · 통계 · 알림함 ·
-- TC 판정 조회가 이 값이 있는 런을 뺀다. 데이터는 조사용으로 남는다.
ALTER TABLE qa_run
    ADD COLUMN stop_code VARCHAR(64),
    ADD COLUMN stop_provider VARCHAR(32),
    ADD COLUMN stop_message TEXT,
    ADD COLUMN discarded_at TIMESTAMP WITH TIME ZONE;

-- 끝난 시도의 대기 중 질문을 닫는 트리거가 UNDECIDED 도 끝으로 보게 한다(V145).
CREATE OR REPLACE FUNCTION cancel_qa_operator_questions() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status IN ('COMPLETED','FAILED','CANCELLED','UNDECIDED') THEN
        UPDATE qa_operator_question SET state = 'CANCELLED' WHERE qa_try_id = NEW.id AND state = 'WAITING';
    END IF;
    RETURN NEW;
END;
$$;
