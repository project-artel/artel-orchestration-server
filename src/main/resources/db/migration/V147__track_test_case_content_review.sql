-- TC 내용 품질은 build별 검증 판정과 별도로 관리한다. 삭제한 TC의 보고 출처도 보존한다.
ALTER TABLE qa_verdict_review DROP CONSTRAINT qa_verdict_review_decision_check;
ALTER TABLE qa_verdict_review ADD CONSTRAINT qa_verdict_review_decision_check
    CHECK (decision IN ('PASSED', 'FAILED', 'INCONCLUSIVE', 'TC_CONTENT_ISSUE'));
ALTER TABLE qa_verdict_review_revision DROP CONSTRAINT qa_verdict_review_revision_decision_check;
ALTER TABLE qa_verdict_review_revision ADD CONSTRAINT qa_verdict_review_revision_decision_check
    CHECK (decision IN ('PASSED', 'FAILED', 'INCONCLUSIVE', 'TC_CONTENT_ISSUE'));

CREATE TABLE qa_case_content_review (
    test_case_id BIGINT PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    state VARCHAR(24) NOT NULL CHECK (state IN ('NEEDS_REVISION', 'NEEDS_REVALIDATION', 'READY')),
    note TEXT,
    source_log_id BIGINT REFERENCES qa_log(id) ON DELETE SET NULL,
    -- report가 attempt를 먼저 잠그므로 역순 FK lock을 만들지 않고 원래 UUID를 보존한다.
    source_attempt_id UUID,
    reported_snapshot JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    revised_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ
);
CREATE INDEX qa_case_content_review_project ON qa_case_content_review(project_id, test_case_id);

-- agent는 test_step/expected, DB snapshot은 step/expected_value를 사용한다.
-- metadata, 상태, timestamps는 검증 내용이 아니므로 비교에 넣지 않는다.
CREATE FUNCTION qa_case_agent_content(snapshot JSONB) RETURNS JSONB LANGUAGE sql IMMUTABLE AS $$
    SELECT jsonb_build_object(
        'scene', snapshot->>'scene', 'precondition', snapshot->>'precondition',
        'step', COALESCE(snapshot->>'test_step', snapshot->>'step'),
        'expected', COALESCE(snapshot->>'expected', snapshot->>'expected_value'),
        'cheats', COALESCE(NULLIF(snapshot->'functions'->'cheats', 'null'::jsonb), '"all"'::jsonb),
        'behaviors', COALESCE(NULLIF(snapshot->'functions'->'behaviors', 'null'::jsonb), '"all"'::jsonb)
    )
$$;
CREATE FUNCTION qa_case_snapshot_matches(snapshot JSONB, current_case JSONB) RETURNS BOOLEAN LANGUAGE sql IMMUTABLE AS $$
    SELECT qa_case_agent_content(snapshot) = qa_case_agent_content(current_case)
       AND (NOT snapshot ? 'condition' OR snapshot->'condition' IS NOT DISTINCT FROM current_case->'condition')
       AND (NOT snapshot ? 'expected_items' OR snapshot->'expected_items' IS NOT DISTINCT FROM current_case->'expected_items')
$$;
CREATE FUNCTION qa_case_full_content(snapshot JSONB) RETURNS JSONB LANGUAGE sql IMMUTABLE AS $$
    SELECT qa_case_agent_content(snapshot) || jsonb_build_object('condition', snapshot->'condition', 'expected_items', snapshot->'expected_items')
$$;

-- TC 행을 먼저 잠그고 lifecycle 행을 수정한다. 내용 편집 trigger도 같은 잠금 순서를 사용한다.
CREATE FUNCTION reconcile_case_content_review() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE attempt qa_case_attempt%ROWTYPE; current_case test_case%ROWTYPE;
BEGIN
    SELECT * INTO attempt FROM qa_case_attempt WHERE qa_log_id = NEW.qa_log_id;
    IF attempt.id IS NULL THEN RETURN NEW; END IF;
    SELECT * INTO current_case FROM test_case WHERE id = attempt.test_case_id AND project_id = attempt.project_id FOR UPDATE;
    IF current_case.id IS NULL THEN RETURN NEW; END IF;
    IF NEW.decision = 'TC_CONTENT_ISSUE' THEN
        -- 과거 내용의 평가를 현재 TC의 수정 요청으로 바꾸지 않는다.
        IF NOT qa_case_snapshot_matches(attempt.case_snapshot, to_jsonb(current_case)) THEN RETURN NEW; END IF;
        INSERT INTO qa_case_content_review(test_case_id, project_id, state, note, source_log_id, source_attempt_id, reported_snapshot)
        VALUES (current_case.id, current_case.project_id, 'NEEDS_REVISION', NEW.note, NEW.qa_log_id, attempt.id, attempt.case_snapshot)
        ON CONFLICT (test_case_id) DO UPDATE SET state = 'NEEDS_REVISION', note = EXCLUDED.note,
            source_log_id = EXCLUDED.source_log_id, source_attempt_id = EXCLUDED.source_attempt_id,
            reported_snapshot = EXCLUDED.reported_snapshot, updated_at = clock_timestamp(), revised_at = NULL, resolved_at = NULL;
    ELSE
        -- 현재 보고를 철회해도 이미 내용을 고친 뒤의 재검증 대기는 해제하지 않는다.
        UPDATE qa_case_content_review SET state = 'READY', updated_at = clock_timestamp(), resolved_at = clock_timestamp()
        WHERE test_case_id = current_case.id AND source_log_id = NEW.qa_log_id AND state = 'NEEDS_REVISION';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER qa_review_content AFTER INSERT OR UPDATE OF decision, note ON qa_verdict_review
    FOR EACH ROW EXECUTE FUNCTION reconcile_case_content_review();

CREATE FUNCTION mark_case_content_revised() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF qa_case_full_content(to_jsonb(OLD)) IS DISTINCT FROM qa_case_full_content(to_jsonb(NEW)) THEN
        UPDATE qa_case_content_review SET state = 'NEEDS_REVALIDATION', revised_at = clock_timestamp(),
            updated_at = clock_timestamp(), resolved_at = NULL WHERE test_case_id = NEW.id;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER test_case_content_revised AFTER UPDATE ON test_case
    FOR EACH ROW EXECUTE FUNCTION mark_case_content_revised();

CREATE FUNCTION resolve_case_content_revalidation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE current_case test_case%ROWTYPE;
BEGIN
    IF NEW.verdict IS NULL OR NEW.verdict NOT IN ('PASSED', 'FAILED') OR NEW.started_at IS NULL THEN RETURN NEW; END IF;
    IF TG_OP = 'UPDATE' AND OLD.verdict IS NOT DISTINCT FROM NEW.verdict THEN RETURN NEW; END IF;
    SELECT * INTO current_case FROM test_case WHERE id = NEW.test_case_id AND project_id = NEW.project_id FOR UPDATE;
    IF current_case.id IS NULL OR NOT qa_case_snapshot_matches(NEW.case_snapshot, to_jsonb(current_case)) THEN RETURN NEW; END IF;
    UPDATE qa_case_content_review SET state = 'READY', resolved_at = clock_timestamp(), updated_at = clock_timestamp()
    WHERE test_case_id = current_case.id AND state = 'NEEDS_REVALIDATION' AND NEW.started_at > revised_at;
    RETURN NEW;
END;
$$;
CREATE TRIGGER qa_attempt_content AFTER INSERT OR UPDATE OF verdict ON qa_case_attempt
    FOR EACH ROW EXECUTE FUNCTION resolve_case_content_revalidation();
