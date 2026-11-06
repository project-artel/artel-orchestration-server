CREATE TABLE issue_discussion (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    author_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL,
    body TEXT NOT NULL CHECK (length(body) BETWEEN 1 AND 20000),
    purpose VARCHAR(16) NOT NULL CHECK (purpose IN ('SHARE', 'QUESTION', 'DECISION')),
    state VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN', 'CLOSED')),
    pinned BOOLEAN NOT NULL DEFAULT FALSE,
    mention_ids BIGINT[] NOT NULL DEFAULT '{}',
    decision_comment_id BIGINT,
    version INTEGER NOT NULL DEFAULT 0,
    client_request_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, author_id, client_request_id)
);
CREATE INDEX issue_discussion_project_page ON issue_discussion(project_id, pinned DESC, id DESC);
CREATE TABLE issue_discussion_issue (
    discussion_id BIGINT NOT NULL REFERENCES issue_discussion(id) ON DELETE CASCADE,
    issue_id BIGINT NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    PRIMARY KEY (discussion_id, issue_id)
);
CREATE INDEX issue_discussion_issue_reverse ON issue_discussion_issue(issue_id, discussion_id);
CREATE TABLE issue_discussion_comment (
    id BIGSERIAL PRIMARY KEY,
    discussion_id BIGINT NOT NULL REFERENCES issue_discussion(id) ON DELETE CASCADE,
    author_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    body TEXT NOT NULL CHECK (length(body) BETWEEN 1 AND 10000),
    mention_ids BIGINT[] NOT NULL DEFAULT '{}',
    version INTEGER NOT NULL DEFAULT 0,
    client_request_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (discussion_id, author_id, client_request_id),
    UNIQUE (discussion_id, id)
);
-- 같은 글의 댓글만 결정으로 지정할 수 있다. 삭제는 이번 API 범위에 포함하지 않는다.
ALTER TABLE issue_discussion ADD CONSTRAINT issue_discussion_decision_fk
    FOREIGN KEY (id, decision_comment_id) REFERENCES issue_discussion_comment(discussion_id, id)
    DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE issue_discussion_subscription (
    discussion_id BIGINT NOT NULL REFERENCES issue_discussion(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    PRIMARY KEY (discussion_id, user_id)
);
CREATE INDEX issue_discussion_subscription_user ON issue_discussion_subscription(user_id, discussion_id);
CREATE TABLE issue_discussion_reaction (
    comment_id BIGINT NOT NULL REFERENCES issue_discussion_comment(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    PRIMARY KEY (comment_id, user_id)
);
