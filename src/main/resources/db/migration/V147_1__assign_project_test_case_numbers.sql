-- backfill과 trigger 설치 사이에 새 TC가 번호 없이 저장되지 않도록 쓰기를 잠근다.
LOCK TABLE test_case IN SHARE ROW EXCLUSIVE MODE;

CREATE TABLE test_case_identifier (
    test_case_id BIGINT PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    case_number BIGINT NOT NULL CHECK (case_number > 0),
    UNIQUE (project_id, case_number)
);
CREATE TABLE test_case_number_counter (
    project_id BIGINT PRIMARY KEY REFERENCES project(id) ON DELETE CASCADE,
    last_number BIGINT NOT NULL CHECK (last_number > 0)
);

-- 현재 TC의 소유 프로젝트를 우선한다. 삭제 TC의 과거 기록이 여러 프로젝트를 가리키면 번호를 만들지 않는다.
WITH legacy_log_references AS (
    SELECT ltrim(l.payload->>'case_id', '0') AS case_id,
        s.project_id
    FROM qa_log l JOIN qa_try t ON t.id = l.qa_try_id
    JOIN test_scenario s ON s.id = t.test_scenario_id JOIN project p ON p.id = s.project_id
    WHERE l.payload->>'case_id' ~ '^[0-9]+$'
), legacy_log_ids AS (
    SELECT CASE WHEN length(case_id) BETWEEN 1 AND 19
        AND (length(case_id) < 19 OR case_id <= '9223372036854775807') THEN case_id::BIGINT END AS test_case_id,
        project_id FROM legacy_log_references
), history AS (
    SELECT a.test_case_id, a.project_id FROM qa_case_attempt a
    JOIN qa_try t ON t.id = a.qa_try_id JOIN test_scenario s ON s.id = t.test_scenario_id AND s.project_id = a.project_id
    JOIN project p ON p.id = a.project_id WHERE a.test_case_id > 0
    UNION SELECT test_case_id, project_id FROM legacy_log_ids WHERE test_case_id IS NOT NULL
), ownership AS (
    SELECT c.id AS test_case_id, c.project_id FROM test_case c JOIN project p ON p.id = c.project_id
    UNION ALL
    SELECT h.test_case_id, min(h.project_id) FROM history h
    WHERE NOT EXISTS (SELECT 1 FROM test_case c WHERE c.id = h.test_case_id)
    GROUP BY h.test_case_id HAVING count(DISTINCT h.project_id) = 1
)
INSERT INTO test_case_identifier(test_case_id, project_id, case_number)
SELECT test_case_id, project_id, row_number() OVER (PARTITION BY project_id ORDER BY test_case_id) FROM ownership;

INSERT INTO test_case_number_counter(project_id, last_number)
SELECT project_id, max(case_number) FROM test_case_identifier GROUP BY project_id;

CREATE FUNCTION assign_test_case_number() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE previous_project BIGINT; next_number BIGINT; inserted_id BIGINT;
BEGIN
    SELECT project_id INTO previous_project FROM test_case_identifier WHERE test_case_id = NEW.id;
    IF FOUND THEN
        IF previous_project <> NEW.project_id THEN
            RAISE EXCEPTION 'TC의 프로젝트는 변경할 수 없습니다.' USING ERRCODE = '23514';
        END IF;
        -- ON CONFLICT 재전송은 허용하지만 삭제된 내부 ID를 새 TC로 재사용하지 않는다.
        PERFORM 1 FROM test_case WHERE id = NEW.id FOR KEY SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION '삭제된 TC ID는 재사용할 수 없습니다.' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    INSERT INTO test_case_number_counter AS counter(project_id, last_number) VALUES (NEW.project_id, 1)
    ON CONFLICT (project_id) DO UPDATE SET last_number = counter.last_number + 1
    RETURNING last_number INTO next_number;
    INSERT INTO test_case_identifier(test_case_id, project_id, case_number) VALUES (NEW.id, NEW.project_id, next_number)
    ON CONFLICT (test_case_id) DO NOTHING RETURNING test_case_id INTO inserted_id;
    IF inserted_id IS NULL THEN
        -- 같은 내부 ID가 동시에 들어왔으면 먼저 저장된 번호를 유지한다. 사용되지 않은 번호의 공백은 허용한다.
        SELECT project_id INTO previous_project FROM test_case_identifier WHERE test_case_id = NEW.id;
        IF previous_project <> NEW.project_id THEN
            RAISE EXCEPTION 'TC의 프로젝트는 변경할 수 없습니다.' USING ERRCODE = '23514';
        END IF;
        PERFORM 1 FROM test_case WHERE id = NEW.id FOR KEY SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION '삭제된 TC ID는 재사용할 수 없습니다.' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER test_case_assign_number BEFORE INSERT ON test_case FOR EACH ROW EXECUTE FUNCTION assign_test_case_number();

CREATE FUNCTION preserve_test_case_identity() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.project_id IS DISTINCT FROM OLD.project_id THEN
        RAISE EXCEPTION 'TC ID와 프로젝트는 변경할 수 없습니다.' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER test_case_preserve_identity BEFORE UPDATE OF id, project_id ON test_case
    FOR EACH ROW EXECUTE FUNCTION preserve_test_case_identity();
