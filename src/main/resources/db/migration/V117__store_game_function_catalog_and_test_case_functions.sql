--------------------------------------------------------------------------------
-- V113. 게임이 등록한 cheat · behavior 목록을 빌드에 담고, test case 의 사용 허용 범위를 담는다
--------------------------------------------------------------------------------
-- 계약은 `.plan/general/2026-10-14-game-functions-wire-contract.md` 다(ARTEL-1051, ARTEL-1053).
--
--   1. game_build.game_function_catalog* — SDK 가 `GAME_FUNCTION_CATALOG` 프레임으로 보낸 목록
--   2. test_case.functions               — 이 케이스를 돌릴 때 agent 에게 허용할 cheat · behavior
--   3. qa_log.type 'BEHAVIOR_ENDED'      — behavior 가 끝났다는 SDK 프레임의 타임라인 행
--
-- V111 은 열린 pull request #313 이, V112 는 #312(ARTEL-1028)가 가져갔다. 둘 다 건드리지 않으려고 V113 을 쓴다.

--------------------------------------------------------------------------------
-- 1. game_build
--------------------------------------------------------------------------------
-- `scene_scan` 과 같이 빌드당 최신본 하나만 의미가 있어 프레임이 올 때마다 덮어쓴다. 이력을 쌓지 않는다.
--
-- game_function_catalog             — `{"cheats":[...],"behaviors":[...]}`. 항목 모양은 계약 1절이다
-- game_function_catalog_revision    — SDK 가 프로세스마다 1 부터 올리는 번호. 프로세스가 바뀌면 다시 1 이다
-- game_function_catalog_received_at — 서버가 프레임을 받은 시각
--
-- 셋 다 NULL 이면 이 빌드는 목록을 보낸 적이 없다. 응답은 그 경우 revision 0 과 빈 목록 둘로 답한다.
ALTER TABLE game_build
    ADD COLUMN IF NOT EXISTS game_function_catalog JSONB,
    ADD COLUMN IF NOT EXISTS game_function_catalog_revision INTEGER,
    ADD COLUMN IF NOT EXISTS game_function_catalog_received_at TIMESTAMPTZ;

--------------------------------------------------------------------------------
-- 2. test_case
--------------------------------------------------------------------------------
-- `{"cheats": "all" | "none" | ["name", ...], "behaviors": "all" | "none" | ["name", ...]}`.
-- 키가 없거나 컬럼이 NULL 이면 그 쪽은 "all" 이다. run 에서 허용한 목록을 좁히기만 하고 넓히지 않는다.
ALTER TABLE test_case
    ADD COLUMN IF NOT EXISTS functions JSONB;

--------------------------------------------------------------------------------
-- 3. qa_log.type
--------------------------------------------------------------------------------
-- behavior 가 끝날 때 SDK 가 보내는 `BEHAVIOR_ENDED` 를 타임라인에 한 줄로 남긴다. 이유(`reason`)와 걸린 시간이
-- 그 행의 payload 에 있어, "왜 behavior 가 멈췄나" 를 되짚을 자리가 여기뿐이다.
--
-- 게이트가 둘이라는 것을 잊지 말 것(V57 · V61 · V68 주석). `QaLogService.TYPES` 의 `require` 와 이 CHECK 가 같은
-- 목록을 각자 들고 있고, 어긋나면 `QaLogTypeGateParityTest` 가 실패한다.
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
        'SCREEN_NAME',
        'CASE_VERDICT',
        'BEHAVIOR_ENDED'
    )
);
