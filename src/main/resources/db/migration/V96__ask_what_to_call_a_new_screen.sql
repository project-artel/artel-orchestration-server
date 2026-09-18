-- ARTEL-910: 새로 생긴 screen 의 이름을 agent 에게 묻고 답을 screen.name 에 쓴다.
--
-- ## 스키마는 안 바뀐다
--
-- `screen.name VARCHAR(255)` 는 V40 이 만든 뒤로 한 번도 쓰인 적이 없다. 이 이슈가 채우는 것이 그
-- 칸이고, 칸 자체는 이미 있으므로 이 파일에 컬럼 변경이 없다. 바뀌는 것은 아래 하나뿐이다.
--
-- ## qa_log 타입 — 새 프레임 둘
--
--   SCREEN_NAME_REQUEST  방금 생긴 화면의 이름을 묻는다 (ORCHE_TO_AGENT)
--   SCREEN_NAME          그 답 (AGENT_TO_ORCHE). correlationId 가 질문의 messageId 다
--
-- 타임라인에 남겨야 하는 이유는 `SCREEN_SETTLED`(V68) 와 같다 — "이름이 왜 저 모양인가" 와
-- "왜 이름이 없나" 를 되짚을 자리가 이 두 행 말고 없다. 그림이 붙은 채로 물었는지는 질문 행의
-- message 에 적히고, 답을 왜 안 썼는지(빈 문자열·60자 초과)는 답 행의 message 에 적힌다.
--
-- 게이트가 둘이라는 것을 잊지 말 것(V57 · V61 · V68 주석). `QaLogService.TYPES` 의 `require` 와 이
-- CHECK 가 같은 목록을 각자 들고 있고, 한쪽만 열면 통과한 값이 INSERT 에서 죽는다. 어긋나면
-- `QaLogTypeGateParityTest` 가 실패한다.
--------------------------------------------------------------------------------
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
        'TOOL_RESULT',
        'SCREEN_SELECTOR_PROPOSAL',
        'SCREEN_SELECTOR_VERDICT',
        'SCREEN_SELECTOR_RULE',
        'SCREEN_SELECTOR_RESULT',
        'SCREEN_SETTLED',
        'SCREEN_NAME_REQUEST',
        'SCREEN_NAME'
    )
);
