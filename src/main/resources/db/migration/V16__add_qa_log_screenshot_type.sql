-- QA 캡처 증거를 타임라인에 남기기 위한 로그 타입.
--
-- 이미지 바이트는 여기 들어오지 않는다. 행에는 객체 키·크기·대상 id·다운로드 URL만 담고,
-- 실제 바이트는 스토리지에 있다. qa_log.payload는 중계 프레임 전문을 적재하고 SSE로도
-- 발행되므로, 캡처 한 장이 메가바이트 단위로 이 테이블과 스트림을 불리면 안 된다.

ALTER TABLE qa_log DROP CONSTRAINT IF EXISTS qa_log_type_check;

ALTER TABLE qa_log ADD CONSTRAINT qa_log_type_check CHECK (
    type IN (
        'LOG',
        'ACTION',
        'ACTION_RESULT',
        'GAME_STATE',
        'STATUS',
        'ERROR',
        'CHAT',
        'SCREENSHOT'
    )
);
