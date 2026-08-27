-- 에이전트의 tool 호출과 그 답을 QA 타임라인에 남기기 위한 로그 타입 (ARTEL-608).
--
-- `ACTION` 으로는 모자란다. 그것은 조작 tool 이 SDK 로 내보낸 요청이라, QA 에이전트 tool
-- 28개 중 15개만 남기고 나머지 13개 — 지식 검색·기록, 스텝 판정, 이슈 보고 등 — 는 아무
-- 흔적도 남기지 않았다. 그래서 로그를 읽어도 에이전트가 무엇을 불렀는지 알 수 없었다.
--
-- 게이트가 둘인 것을 잊지 말 것. `QaLogService.TYPES` 의 `require` 와 이 CHECK 가 같은
-- 목록을 각자 들고 있고, 한쪽만 열면 통과한 값이 INSERT 에서 죽는다. 어긋나면
-- `QaLogTypeGateParityTest` 가 실패한다.

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
        'PULSE',
        'TOOL',
        'TOOL_RESULT'
    )
);
