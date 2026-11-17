ALTER TABLE qa_verdict_review ADD COLUMN revised_case_snapshot JSONB;
ALTER TABLE qa_verdict_review_revision ADD COLUMN revised_case_snapshot JSONB;

CREATE OR REPLACE FUNCTION record_qa_review_revision() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.decision IS NOT NULL THEN
        INSERT INTO qa_verdict_review_revision(qa_log_id, decision, note, decided_by, decided_at, revised_case_snapshot)
        VALUES (NEW.qa_log_id, NEW.decision, NEW.note, NEW.decided_by, NEW.decided_at, NEW.revised_case_snapshot);
    END IF;
    RETURN NEW;
END;
$$;

ALTER TABLE qa_case_content_review ADD COLUMN independent_report BOOLEAN NOT NULL DEFAULT FALSE;

CREATE FUNCTION report_qa_case_content(log_id BIGINT, report_note TEXT, reporter BIGINT DEFAULT NULL) RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE attempt qa_case_attempt%ROWTYPE; current_case test_case%ROWTYPE;
BEGIN
    SELECT * INTO attempt FROM qa_case_attempt WHERE qa_log_id = log_id;
    IF attempt.id IS NULL THEN RETURN FALSE; END IF;
    SELECT * INTO current_case FROM test_case WHERE id = attempt.test_case_id AND project_id = attempt.project_id FOR UPDATE;
    IF current_case.id IS NULL OR NOT qa_case_snapshot_matches(attempt.case_snapshot, to_jsonb(current_case)) THEN
        RETURN FALSE;
    END IF;
    INSERT INTO qa_case_content_review(test_case_id, project_id, state, note, source_log_id, source_attempt_id, reported_snapshot, independent_report)
    VALUES (current_case.id, current_case.project_id, 'NEEDS_REVISION', report_note, log_id, attempt.id, attempt.case_snapshot, reporter IS NOT NULL)
    ON CONFLICT (test_case_id) DO UPDATE SET state = 'NEEDS_REVISION', note = EXCLUDED.note,
        source_log_id = EXCLUDED.source_log_id, source_attempt_id = EXCLUDED.source_attempt_id,
        reported_snapshot = EXCLUDED.reported_snapshot,
        independent_report = qa_case_content_review.independent_report OR EXCLUDED.independent_report, updated_at = clock_timestamp(), revised_at = NULL, resolved_at = NULL;
    IF reporter IS NOT NULL THEN
        INSERT INTO qa_verdict_review_revision(qa_log_id, decision, note, decided_by, decided_at)
        VALUES (log_id, 'TC_CONTENT_ISSUE', report_note, reporter, clock_timestamp());
    END IF;
    RETURN TRUE;
END;
$$;

CREATE OR REPLACE FUNCTION reconcile_case_content_review() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.decision = 'TC_CONTENT_ISSUE' THEN
        PERFORM report_qa_case_content(NEW.qa_log_id, NEW.note);
    ELSE
        UPDATE qa_case_content_review SET state = 'READY', updated_at = clock_timestamp(), resolved_at = clock_timestamp()
        WHERE source_log_id = NEW.qa_log_id AND state = 'NEEDS_REVISION' AND NOT independent_report;
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION reconcile_case_failure(attempt_key UUID) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE attempt qa_case_attempt%ROWTYPE; human_decision TEXT; valid BOOLEAN; issue_key BIGINT; previous_key BIGINT;
        existing issue%ROWTYPE; other issue%ROWTYPE;
BEGIN
    SELECT * INTO attempt FROM qa_case_attempt WHERE id = attempt_key;
    IF attempt.verdict IS NULL THEN RETURN; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('qa-tc-issue:' || attempt.test_case_id::text, 0));
    SELECT CASE WHEN revised_case_snapshot IS NULL THEN decision ELSE 'TC_CONTENT_ISSUE' END
        INTO human_decision FROM qa_verdict_review WHERE qa_log_id = attempt.qa_log_id;
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
