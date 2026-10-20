--------------------------------------------------------------------------------
-- V120. 이슈 번호 · 보관 · 카테고리, 공유 글의 리뷰 · Draft · 대댓글 (ARTEL-1114)
--------------------------------------------------------------------------------

-- ## 이슈마다 프로젝트 안 번호
--
-- 화면의 `#9` 는 DB 의 전역 id 라 프로젝트마다 띄엄띄엄이다. 프로젝트마다 1 부터 매기는 번호를 따로 둔다.
-- 이슈는 프로젝트를 직접 들지 않고 실행 · 시나리오를 거쳐야 닿으므로, 넣을 때 트리거가 프로젝트를 찾아
-- `project_id` 와 번호를 함께 채운다. 번호는 프로젝트별 카운터 행을 `UPDATE ... RETURNING` 으로 올려 받는다 —
-- 그 행의 잠금이 동시에 들어온 두 이슈가 같은 번호를 받지 않게 한다.

ALTER TABLE issue ADD COLUMN IF NOT EXISTS project_id BIGINT REFERENCES project (id) ON DELETE CASCADE;
ALTER TABLE issue ADD COLUMN IF NOT EXISTS project_no BIGINT;
-- 보관(숨김). 지우지 않고 목록 · 개수에서만 뺀다. null 이면 보관하지 않은 것이다.
ALTER TABLE issue ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;

UPDATE issue i SET project_id = ts.project_id
FROM qa_try qt JOIN test_scenario ts ON ts.id = qt.test_scenario_id
WHERE qt.id = i.qa_try_id AND i.project_id IS NULL;

UPDATE issue i SET project_no = numbered.no
FROM (SELECT id, row_number() OVER (PARTITION BY project_id ORDER BY id) AS no FROM issue WHERE project_id IS NOT NULL) numbered
WHERE numbered.id = i.id AND i.project_no IS NULL;

CREATE TABLE IF NOT EXISTS issue_project_counter (
    project_id BIGINT PRIMARY KEY REFERENCES project (id) ON DELETE CASCADE,
    last_no BIGINT NOT NULL
);
INSERT INTO issue_project_counter (project_id, last_no)
SELECT project_id, max(project_no) FROM issue WHERE project_id IS NOT NULL GROUP BY project_id
ON CONFLICT (project_id) DO UPDATE SET last_no = GREATEST(issue_project_counter.last_no, EXCLUDED.last_no);

CREATE UNIQUE INDEX IF NOT EXISTS ux_issue_project_no ON issue (project_id, project_no);
CREATE INDEX IF NOT EXISTS ix_issue_project_archived ON issue (project_id, archived_at);

CREATE OR REPLACE FUNCTION issue_assign_project_no() RETURNS trigger AS $$
BEGIN
    IF NEW.project_id IS NULL THEN
        SELECT ts.project_id INTO NEW.project_id
        FROM qa_try qt JOIN test_scenario ts ON ts.id = qt.test_scenario_id
        WHERE qt.id = NEW.qa_try_id;
    END IF;
    IF NEW.project_id IS NOT NULL AND NEW.project_no IS NULL THEN
        INSERT INTO issue_project_counter (project_id, last_no) VALUES (NEW.project_id, 0)
        ON CONFLICT (project_id) DO NOTHING;
        UPDATE issue_project_counter SET last_no = last_no + 1
        WHERE project_id = NEW.project_id
        RETURNING last_no INTO NEW.project_no;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_issue_assign_project_no ON issue;
CREATE TRIGGER trg_issue_assign_project_no BEFORE INSERT ON issue
    FOR EACH ROW EXECUTE FUNCTION issue_assign_project_no();

-- ## 카테고리
--
-- 사용자가 프로젝트마다 정의하고, 이슈와 공유 글에 여러 개 붙인다.

CREATE TABLE IF NOT EXISTS issue_category (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    name VARCHAR(60) NOT NULL,
    -- 화면이 고르는 색 이름(팔레트 키). 자유 hex 를 받지 않는다 — 다크 · 라이트 대비를 화면이 책임진다.
    color VARCHAR(16) NOT NULL DEFAULT 'gray',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, name)
);
CREATE TABLE IF NOT EXISTS issue_category_issue (
    category_id BIGINT NOT NULL REFERENCES issue_category (id) ON DELETE CASCADE,
    issue_id BIGINT NOT NULL REFERENCES issue (id) ON DELETE CASCADE,
    PRIMARY KEY (category_id, issue_id)
);
CREATE INDEX IF NOT EXISTS ix_issue_category_issue_issue ON issue_category_issue (issue_id);
CREATE TABLE IF NOT EXISTS issue_discussion_category (
    category_id BIGINT NOT NULL REFERENCES issue_category (id) ON DELETE CASCADE,
    discussion_id BIGINT NOT NULL REFERENCES issue_discussion (id) ON DELETE CASCADE,
    PRIMARY KEY (category_id, discussion_id)
);
CREATE INDEX IF NOT EXISTS ix_issue_discussion_category_discussion ON issue_discussion_category (discussion_id);

-- ## 공유 글: 목적 · Draft · 리뷰 · 대댓글
--
-- "정보 공유"(SHARE)는 "이슈 해결 요청"(RESOLUTION)이 된다. "확인 요청"(QUESTION)은 해결한 이슈를 검토자에게
-- 승인받는 글이다 — 승인이 프로젝트 설정 수 이상이면 글이 해결(CLOSED)된다. Draft 는 아직 내보이지 않은 글이다.

ALTER TABLE issue_discussion DROP CONSTRAINT IF EXISTS issue_discussion_purpose_check;
UPDATE issue_discussion SET purpose = 'RESOLUTION' WHERE purpose = 'SHARE';
ALTER TABLE issue_discussion ADD CONSTRAINT issue_discussion_purpose_check
    CHECK (purpose IN ('RESOLUTION', 'QUESTION', 'DECISION'));
ALTER TABLE issue_discussion DROP CONSTRAINT IF EXISTS issue_discussion_state_check;
ALTER TABLE issue_discussion ADD CONSTRAINT issue_discussion_state_check
    CHECK (state IN ('DRAFT', 'OPEN', 'CLOSED'));

CREATE TABLE IF NOT EXISTS issue_discussion_reviewer (
    discussion_id BIGINT NOT NULL REFERENCES issue_discussion (id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    approved_at TIMESTAMPTZ,
    PRIMARY KEY (discussion_id, user_id)
);

-- 대댓글은 최상위 댓글 아래 한 단계만이다. 그 규칙은 서비스가 본다(부모의 부모가 있으면 400).
ALTER TABLE issue_discussion_comment ADD COLUMN IF NOT EXISTS parent_id BIGINT;
ALTER TABLE issue_discussion_comment DROP CONSTRAINT IF EXISTS issue_discussion_comment_parent_fk;
ALTER TABLE issue_discussion_comment ADD CONSTRAINT issue_discussion_comment_parent_fk
    FOREIGN KEY (discussion_id, parent_id) REFERENCES issue_discussion_comment (discussion_id, id) ON DELETE CASCADE;

-- 확인 요청이 해결되는 승인 수. 프로젝트 OWNER 가 프로젝트 설정에서 바꾼다.
ALTER TABLE project ADD COLUMN IF NOT EXISTS discussion_approvals_required INT NOT NULL DEFAULT 1
    CHECK (discussion_approvals_required BETWEEN 1 AND 20);
