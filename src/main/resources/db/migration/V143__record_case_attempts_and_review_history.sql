-- 검증 시도와 사람 평가의 수정 이력을 분리한다. 영상 만료는 이 행들을 삭제하지 않는다.
CREATE TABLE qa_case_attempt (
    id UUID PRIMARY KEY,
    qa_try_id BIGINT NOT NULL REFERENCES qa_try(id) ON DELETE CASCADE,
    -- 삭제한 TC도 snapshot과 당시 ID를 보존한다. 살아 있는 TC인지 start API에서 검사한다.
    test_case_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL REFERENCES project(id),
    case_snapshot JSONB NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    qa_log_id BIGINT UNIQUE REFERENCES qa_log(id) ON DELETE CASCADE,
    verdict VARCHAR(20) CHECK (verdict IN ('PASSED', 'FAILED', 'INCONCLUSIVE', 'INTERRUPTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((verdict IS NULL) = (finished_at IS NULL))
);
CREATE INDEX qa_case_attempt_project_time ON qa_case_attempt(project_id, created_at DESC, id);
CREATE INDEX qa_case_attempt_case ON qa_case_attempt(test_case_id, created_at DESC);

ALTER TABLE qa_verdict_review ALTER COLUMN qa_run_id DROP NOT NULL;
ALTER TABLE qa_verdict_review ALTER COLUMN ai_passed DROP NOT NULL;
ALTER TABLE qa_verdict_review ALTER COLUMN decision TYPE VARCHAR(20);
ALTER TABLE qa_verdict_review DROP CONSTRAINT qa_verdict_review_decision_check;
ALTER TABLE qa_verdict_review ADD CONSTRAINT qa_verdict_review_decision_check
    CHECK (decision IN ('PASSED', 'FAILED', 'INCONCLUSIVE'));

CREATE TABLE qa_verdict_review_revision (
    id BIGSERIAL PRIMARY KEY,
    qa_log_id BIGINT NOT NULL REFERENCES qa_log(id) ON DELETE CASCADE,
    decision VARCHAR(20) NOT NULL CHECK (decision IN ('PASSED', 'FAILED', 'INCONCLUSIVE')),
    note TEXT,
    decided_by BIGINT REFERENCES app_user(id) ON DELETE SET NULL,
    decided_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX qa_verdict_review_revision_log ON qa_verdict_review_revision(qa_log_id, id DESC);
INSERT INTO qa_verdict_review_revision(qa_log_id, decision, note, decided_by, decided_at)
SELECT qa_log_id, decision, note, decided_by, COALESCE(decided_at, created_at)
FROM qa_verdict_review WHERE decision IS NOT NULL;

CREATE FUNCTION record_qa_review_revision() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.decision IS NOT NULL THEN
        INSERT INTO qa_verdict_review_revision(qa_log_id, decision, note, decided_by, decided_at)
        VALUES (NEW.qa_log_id, NEW.decision, NEW.note, NEW.decided_by, NEW.decided_at);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER qa_review_revision AFTER INSERT OR UPDATE OF decision, note, decided_by, decided_at
    ON qa_verdict_review FOR EACH ROW EXECUTE FUNCTION record_qa_review_revision();

-- 수동 취소·연결 유실 등 모든 종료 경로에서 열린 검증을 중단으로 남긴다.
CREATE FUNCTION close_interrupted_case_attempts() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE attempt RECORD; log_id BIGINT; finished TIMESTAMPTZ;
BEGIN
    IF NEW.status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED') OR OLD.status = NEW.status THEN RETURN NEW; END IF;
    finished := COALESCE(NEW.completed_at, clock_timestamp());
    FOR attempt IN SELECT * FROM qa_case_attempt WHERE qa_try_id = NEW.id AND verdict IS NULL FOR UPDATE LOOP
        INSERT INTO qa_log(qa_try_id, direction, type, message, payload, created_at)
        VALUES (NEW.id, 'ORCHE_INTERNAL', 'STATUS', 'TC 검증이 결과 보고 전에 중단됐습니다.',
            jsonb_build_object('case_id', attempt.test_case_id, 'attempt_id', attempt.id, 'is_verification', true,
                               'case_outcome', 'INTERRUPTED', 'status', 'INCONCLUSIVE'), finished)
        RETURNING id INTO log_id;
        UPDATE qa_case_attempt SET finished_at = finished, verdict = 'INTERRUPTED', qa_log_id = log_id WHERE id = attempt.id;
    END LOOP;
    RETURN NEW;
END;
$$;
CREATE TRIGGER qa_case_attempt_interrupt AFTER UPDATE OF status ON qa_try
    FOR EACH ROW EXECUTE FUNCTION close_interrupted_case_attempts();

-- STATUS 원본의 ID가 검증 시도와 평가의 공통 키다. 이전 agent는 시작 시각을 알 수 없으므로 null로 남긴다.
CREATE FUNCTION link_case_attempt_log() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE case_id BIGINT; attempt_id UUID; outcome TEXT;
BEGIN
    IF NEW.type <> 'STATUS' OR NEW.payload->>'is_verification' IS DISTINCT FROM 'true'
       OR NEW.payload->>'case_id' !~ '^[0-9]+$' OR NEW.payload->>'result' IS NOT NULL THEN RETURN NEW; END IF;
    outcome := COALESCE(NEW.payload->>'case_outcome', CASE NEW.payload->>'status' WHEN 'COMPLETED' THEN 'PASSED' WHEN 'FAILED' THEN 'FAILED' END);
    IF outcome IS NULL OR outcome NOT IN ('PASSED','FAILED','INCONCLUSIVE','INTERRUPTED') THEN RETURN NEW; END IF;
    case_id := (NEW.payload->>'case_id')::BIGINT;
    IF NEW.payload->>'attempt_id' IS NOT NULL THEN
        attempt_id := (NEW.payload->>'attempt_id')::UUID;
        UPDATE qa_case_attempt SET qa_log_id = NEW.id, finished_at = NEW.created_at, verdict = outcome
        WHERE id = attempt_id AND qa_try_id = NEW.qa_try_id AND test_case_id = case_id AND verdict IS NULL;
        RETURN NEW;
    END IF;
    INSERT INTO qa_case_attempt(id, qa_try_id, test_case_id, project_id, case_snapshot, finished_at, qa_log_id, verdict, created_at)
    SELECT md5('qa-log:' || NEW.id)::UUID, NEW.qa_try_id, c.id, c.project_id, to_jsonb(c), NEW.created_at, NEW.id, outcome, NEW.created_at
    FROM test_case c JOIN test_scenario s ON s.project_id = c.project_id JOIN qa_try t ON t.test_scenario_id = s.id
    WHERE c.id = case_id AND t.id = NEW.qa_try_id ON CONFLICT DO NOTHING;
    RETURN NEW;
END;
$$;
CREATE TRIGGER qa_case_attempt_log AFTER INSERT ON qa_log FOR EACH ROW EXECUTE FUNCTION link_case_attempt_log();
