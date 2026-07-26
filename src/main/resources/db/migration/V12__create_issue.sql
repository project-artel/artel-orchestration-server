-- QA 실행(qa_try) 중 Agent가 게임에서 발견해 보고하는 "이슈(버그)".
--
-- 한 번의 QA 실행이 여러 이슈를 만든다(qa_try 1:N). 이슈는 그 실행이 만들어낸 증거이므로
-- qa_log와 동일하게 qa_try에 하드 FK로 매달고 ON DELETE CASCADE를 건다 — 실행 행을 지우면
-- 그 실행이 남긴 로그와 이슈가 함께 사라진다(증거는 실행보다 오래 살아남지 않는다).
--
-- severity: BLOCKER > CRITICAL > MAJOR > MINOR > TRIVIAL 순의 심각도 사다리. qa_try.status와
-- 같은 방식으로 VARCHAR + CHECK로 값을 강제한다.
--
-- message_id: Agent 프레임의 멱등 키. 라우터가 UUID 검증을 통과한 프레임만 넘기므로 실제로는
-- 항상 채워지지만, 컬럼은 qa_log와 동일하게 nullable로 두고 부분 유니크 인덱스로 재전송을 막는다.
-- title/severity는 조회·정렬을 위해 컬럼으로 승격하고, Agent가 보낸 payload 전체는 detail(JSONB)에
-- 담아 구조화된 근거(expected/actual, 재현 스텝, 스크린샷 참조 등)를 잃지 않는다.

CREATE TABLE IF NOT EXISTS issue (
    id BIGSERIAL PRIMARY KEY,
    qa_try_id BIGINT NOT NULL REFERENCES qa_try (id) ON DELETE CASCADE,
    message_id VARCHAR(255),
    correlation_id VARCHAR(255),
    severity VARCHAR(20) NOT NULL
        CHECK (severity IN ('BLOCKER', 'CRITICAL', 'MAJOR', 'MINOR', 'TRIVIAL')),
    title TEXT NOT NULL,
    detail JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 목록 조회는 한 실행의 이슈를 최신순(id DESC)으로 읽는다. qa_log의 idx_qa_log_try_id_desc와
-- 동일한 형태로 그 조회를 직접 받친다.
CREATE INDEX IF NOT EXISTS idx_issue_qa_try_id_desc
    ON issue (qa_try_id, id DESC);

CREATE UNIQUE INDEX IF NOT EXISTS uk_issue_message
    ON issue (qa_try_id, message_id)
    WHERE message_id IS NOT NULL;
