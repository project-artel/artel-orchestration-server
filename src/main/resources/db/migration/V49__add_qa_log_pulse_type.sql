-- 판독(pulse)을 QA 타임라인에 남기기 위한 로그 타입 (ARTEL-414).
--
-- `QaLogService.TYPES` 만 고치면 되는 줄 알기 쉬운데, 게이트가 둘이다. 코틀린 쪽 집합은
-- `require` 로 막고 이 CHECK 는 INSERT 에서 막는다. 앞의 하나만 열면 핸들러가 판독을 받아
-- 브리지까지 간 뒤 `qa_log_type_check` 위반으로 죽는다 — 브리지를 목으로 세운 테스트는
-- DB 에 닿지 않으므로 그 실패를 보지 못한다. 실제로 로컬에서 SDK 자리를 흉내 낸 소켓으로
-- 판독을 흘려 본 뒤에야 드러났다.
--
-- 판독 원문이 이 행에 그대로 담긴다. 전량 판독이 실측 약 18 KB 이고, 이 행은 SSE 로도
-- 발행되므로 가벼운 값은 아니다. 원문을 런 단위 스토리지로 옮기고 여기엔 도착 사실만
-- 남기는 것은 ARTEL-449 의 몫이다.

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
        'SCREENSHOT',
        'PULSE'
    )
);
