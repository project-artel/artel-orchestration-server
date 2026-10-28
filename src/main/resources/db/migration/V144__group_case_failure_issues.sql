-- 삭제한 TC의 실패 이력도 원래 ID로 조회한다.
ALTER TABLE issue ADD COLUMN test_case_id BIGINT;
ALTER TABLE issue ADD COLUMN previous_issue_id BIGINT REFERENCES issue(id);
ALTER TABLE issue ADD COLUMN auto_closed BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE issue ADD COLUMN merged_into BIGINT REFERENCES issue(id);
CREATE UNIQUE INDEX issue_one_open_case ON issue(test_case_id) WHERE test_case_id IS NOT NULL AND status = 'OPEN';
CREATE TABLE qa_case_failure (
    attempt_id UUID PRIMARY KEY REFERENCES qa_case_attempt(id) ON DELETE CASCADE,
    issue_id BIGINT NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    valid_failure BOOLEAN NOT NULL,
    false_positive BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX qa_case_failure_issue ON qa_case_failure(issue_id);

CREATE FUNCTION reconcile_case_failure(attempt_key UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE attempt qa_case_attempt%ROWTYPE; human_decision TEXT; valid BOOLEAN; issue_key BIGINT; previous_key BIGINT;
        existing issue%ROWTYPE; other issue%ROWTYPE;
BEGIN
    SELECT * INTO attempt FROM qa_case_attempt WHERE id = attempt_key;
    IF attempt.verdict IS NULL THEN RETURN; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('qa-tc-issue:' || attempt.test_case_id::text, 0));
    SELECT decision INTO human_decision FROM qa_verdict_review WHERE qa_log_id = attempt.qa_log_id;
    valid := human_decision = 'FAILED' OR (attempt.verdict = 'FAILED' AND COALESCE(human_decision, 'INCONCLUSIVE') = 'INCONCLUSIVE');
    valid := COALESCE(valid, false);
    SELECT i.* INTO existing FROM issue i JOIN qa_case_failure f ON f.issue_id = i.id WHERE f.attempt_id = attempt_key;
    issue_key := existing.id;
    IF valid AND issue_key IS NULL THEN
        SELECT id INTO issue_key FROM issue WHERE test_case_id = attempt.test_case_id AND status = 'OPEN';
        IF issue_key IS NULL THEN
            SELECT id INTO previous_key FROM issue WHERE test_case_id = attempt.test_case_id ORDER BY id DESC LIMIT 1;
            INSERT INTO issue(qa_try_id, severity, title, detail, reported_at, test_case_id, previous_issue_id)
            VALUES(attempt.qa_try_id, 'MAJOR', 'TC ' || attempt.test_case_id || ' 검증 실패',
                   jsonb_build_object('source', 'TEST_CASE', 'testCaseId', attempt.test_case_id::text, 'previousIssueId', previous_key::text),
                   attempt.finished_at, attempt.test_case_id, previous_key) RETURNING id INTO issue_key;
        END IF;
        INSERT INTO qa_case_failure(attempt_id, issue_id, valid_failure) VALUES(attempt_key, issue_key, true);
    ELSIF valid AND existing.auto_closed THEN
        -- 기존 자동 종료 issue의 정체성을 보존하고 다른 열린 issue를 그쪽으로 합친다.
        FOR other IN SELECT * FROM issue WHERE test_case_id = attempt.test_case_id AND status = 'OPEN' AND id <> issue_key LOOP
            UPDATE qa_case_failure SET issue_id = issue_key WHERE issue_id = other.id;
            UPDATE issue SET status = 'RESOLVED', merged_into = issue_key, resolved_at = now(), updated_at = now() WHERE id = other.id;
        END LOOP;
        UPDATE issue SET status = 'OPEN', auto_closed = false, resolved_at = NULL, resolved_by = NULL, updated_at = now() WHERE id = issue_key;
    END IF;
    IF issue_key IS NOT NULL THEN
        UPDATE qa_case_failure SET valid_failure = valid,
            false_positive = COALESCE(attempt.verdict = 'FAILED' AND human_decision = 'PASSED', false), updated_at = now()
        WHERE attempt_id = attempt_key;
        IF NOT EXISTS(SELECT 1 FROM qa_case_failure WHERE issue_id = issue_key AND valid_failure) THEN
            UPDATE issue SET status = 'RESOLVED', auto_closed = true, resolved_at = now(), resolved_by = NULL, updated_at = now()
            WHERE id = issue_key AND status = 'OPEN';
        END IF;
    END IF;
END;
$$;

CREATE FUNCTION reconcile_attempt_issue() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN PERFORM reconcile_case_failure(NEW.id); RETURN NEW; END;
$$;
CREATE TRIGGER qa_attempt_issue AFTER INSERT OR UPDATE OF verdict ON qa_case_attempt
    FOR EACH ROW EXECUTE FUNCTION reconcile_attempt_issue();

CREATE FUNCTION reconcile_review_issue() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE attempt_key UUID;
BEGIN
    SELECT id INTO attempt_key FROM qa_case_attempt WHERE qa_log_id = NEW.qa_log_id;
    IF attempt_key IS NOT NULL THEN PERFORM reconcile_case_failure(attempt_key); END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER qa_review_issue AFTER INSERT OR UPDATE OF decision ON qa_verdict_review
    FOR EACH ROW EXECUTE FUNCTION reconcile_review_issue();
