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
--
-- reported_at vs created_at: 시각이 둘로 갈린다. reported_at은 Agent가 프레임에 찍은 이벤트
-- 시각(envelope.timestamp) — 게임에서 버그가 실제로 관측된 순간이다. created_at은 우리가 그 프레임을
-- 받아 DB에 저장한 수신 시각(서버 clock). 네트워크 지연·재전송으로 둘이 벌어질 수 있어, 타임라인의
-- 정확한 발생 시점은 reported_at을 쓰고 created_at은 수신·감사 기록으로 남긴다. envelope.timestamp는
-- 모든 Agent 프레임이 항상 채우므로 NOT NULL로 둔다.

CREATE TABLE IF NOT EXISTS issue (
    id BIGSERIAL PRIMARY KEY,
    qa_try_id BIGINT NOT NULL REFERENCES qa_try (id) ON DELETE CASCADE,
    message_id VARCHAR(255),
    correlation_id VARCHAR(255),
    severity VARCHAR(20) NOT NULL
        CHECK (severity IN ('BLOCKER', 'CRITICAL', 'MAJOR', 'MINOR', 'TRIVIAL')),
    title TEXT NOT NULL,
    detail JSONB NOT NULL DEFAULT '{}'::jsonb,
    reported_at TIMESTAMP WITH TIME ZONE NOT NULL,
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
