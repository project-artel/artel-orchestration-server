--------------------------------------------------------------------------------
-- V121. 공유 글(PR)마다 프로젝트 안 번호 (ARTEL-1114)
--------------------------------------------------------------------------------
-- 화면의 `#` 는 DB id 가 아니라 프로젝트마다 1 부터 매기는 번호다. 이슈 번호(V120)와 같은 방식이다 — 넣을 때
-- 트리거가 프로젝트별 카운터 행을 올려 받는다. 그 행의 잠금이 동시에 쓴 두 글이 같은 번호를 받지 않게 한다.

ALTER TABLE issue_discussion ADD COLUMN IF NOT EXISTS project_no BIGINT;

UPDATE issue_discussion d SET project_no = numbered.no
FROM (SELECT id, row_number() OVER (PARTITION BY project_id ORDER BY id) AS no FROM issue_discussion) numbered
WHERE numbered.id = d.id AND d.project_no IS NULL;

CREATE TABLE IF NOT EXISTS issue_discussion_counter (
    project_id BIGINT PRIMARY KEY REFERENCES project (id) ON DELETE CASCADE,
    last_no BIGINT NOT NULL
);
INSERT INTO issue_discussion_counter (project_id, last_no)
SELECT project_id, max(project_no) FROM issue_discussion GROUP BY project_id
ON CONFLICT (project_id) DO UPDATE SET last_no = GREATEST(issue_discussion_counter.last_no, EXCLUDED.last_no);

CREATE UNIQUE INDEX IF NOT EXISTS ux_issue_discussion_project_no ON issue_discussion (project_id, project_no);

CREATE OR REPLACE FUNCTION issue_discussion_assign_project_no() RETURNS trigger AS $$
BEGIN
    IF NEW.project_no IS NULL THEN
        INSERT INTO issue_discussion_counter (project_id, last_no) VALUES (NEW.project_id, 0)
        ON CONFLICT (project_id) DO NOTHING;
        UPDATE issue_discussion_counter SET last_no = last_no + 1
        WHERE project_id = NEW.project_id
        RETURNING last_no INTO NEW.project_no;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_issue_discussion_assign_project_no ON issue_discussion;
CREATE TRIGGER trg_issue_discussion_assign_project_no BEFORE INSERT ON issue_discussion
    FOR EACH ROW EXECUTE FUNCTION issue_discussion_assign_project_no();
