CREATE TABLE qa_operator_question (
    id UUID PRIMARY KEY,
    qa_try_id BIGINT NOT NULL REFERENCES qa_try(id) ON DELETE CASCADE,
    project_id BIGINT NOT NULL REFERENCES project(id),
    question TEXT NOT NULL CHECK (length(question) BETWEEN 1 AND 4000),
    state VARCHAR(20) NOT NULL DEFAULT 'WAITING' CHECK (state IN ('WAITING', 'ANSWERED', 'CANCELLED')),
    answer TEXT,
    answered_by BIGINT REFERENCES app_user(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    answered_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX qa_question_one_waiting ON qa_operator_question(qa_try_id) WHERE state = 'WAITING';
CREATE INDEX qa_question_project ON qa_operator_question(project_id, created_at DESC);
CREATE TABLE qa_question_subscription (
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    app_user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    PRIMARY KEY(project_id, app_user_id)
);
CREATE FUNCTION cancel_qa_operator_questions() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status IN ('COMPLETED','FAILED','CANCELLED') THEN
        UPDATE qa_operator_question SET state = 'CANCELLED' WHERE qa_try_id = NEW.id AND state = 'WAITING';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER qa_question_cancel AFTER UPDATE OF status ON qa_try
    FOR EACH ROW EXECUTE FUNCTION cancel_qa_operator_questions();
