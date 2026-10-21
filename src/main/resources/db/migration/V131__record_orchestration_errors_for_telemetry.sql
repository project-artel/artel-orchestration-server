--------------------------------------------------------------------------------
-- V120. telemetry report 에 실을 orchestration 의 5xx 오류를 남긴다
--------------------------------------------------------------------------------
-- 계획은 `.plan/general/2026-10-20-opt-in-telemetry-from-self-hosted-installs.md` 다.
--
-- `ApiExceptionHandler` 가 5xx 로 답한 예외 하나가 한 행이다. consent 가 켜져 있을 때만 쌓인다.
--
-- 예외 message 는 남기지 않는다. message 에는 경로, 호스트, 요청 값처럼 설치 안의 정보가 들어갈 수 있다.
-- 남기는 것은 예외 클래스 이름, 응답의 오류 code, 그리고 `kr.artel` 아래 stack frame 최대 10개
-- (`class:method:line`)뿐이다.
--
-- 보내는 job 이 14일보다 오래된 행을 지운다(`artel.telemetry.error-retention-days`). report 는 하루치만 읽으므로
-- 그 뒤의 행은 쓸 곳이 없다.

CREATE TABLE IF NOT EXISTS telemetry_error_event (
    id BIGSERIAL PRIMARY KEY,
    -- `Class.getName()`. 예: `org.springframework.dao.DataIntegrityViolationException`.
    exception_type VARCHAR(300) NOT NULL,
    -- 응답에 실린 오류 code. 예: `internal_error`, `storage_unavailable`.
    code VARCHAR(100) NOT NULL,
    -- `kr.artel` 아래 frame 의 `class:method:line` 문자열 배열. 위에서부터 최대 10개다.
    frames JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 하루 구간 집계와 14일 지난 행 삭제가 둘 다 created_at 으로 거른다.
CREATE INDEX IF NOT EXISTS idx_telemetry_error_event_created_at ON telemetry_error_event (created_at);
