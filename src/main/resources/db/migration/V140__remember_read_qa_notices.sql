-- QA 끝 알림을 읽었는지(ARTEL-1254). 상단바 알림함이 내가 시작해 끝난 실행을 보이고, 읽지 않은 수를 센다.
--
-- 행이 있으면 읽은 것이다. 실행을 시작한 사람만 그 알림을 받으므로 (사람, 실행) 쌍이면 충분하다.
CREATE TABLE IF NOT EXISTS qa_notice_read (
    app_user_id BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    qa_run_id   BIGINT      NOT NULL REFERENCES qa_run (id) ON DELETE CASCADE,
    read_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (app_user_id, qa_run_id)
);

-- 이 기능 전에 끝난 실행은 읽은 것으로 둔다. 처음 연 알림함이 지난 실행 전부를 새 알림으로 세지 않게 한다.
INSERT INTO qa_notice_read (app_user_id, qa_run_id)
SELECT started_by, id FROM qa_run WHERE completed_at IS NOT NULL
ON CONFLICT DO NOTHING;

-- 알림함이 읽는 것: 내가 시작해 끝난 실행, 최신순.
CREATE INDEX IF NOT EXISTS idx_qa_run_finished_by_starter ON qa_run (started_by, completed_at DESC) WHERE completed_at IS NOT NULL;
