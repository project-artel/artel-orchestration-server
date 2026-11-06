--------------------------------------------------------------------------------
-- TR 과 QA 실행에 게임 버전을 남기고, QA 판정을 TC 상태로 옮긴다 (ARTEL-994)
--
-- #293(ARTEL-971) 이후 TC 는 build 마다 다른 행이다. 같은 케이스는 build 를 넘어
-- `metadata.case_key` 로만 이어진다. 그래서 **검증 이력은 따로 표를 두지 않는다** — 앞 build 의
-- 행이 그 버전의 판정을 그대로 들고 남는다. 이 마이그레이션이 하는 것은 넷이다.
--
--   1. test_run.game_build_id — TR 을 만든 게임 버전. 가져오기 트리가 버전으로 묶는 축이다.
--   2. qa_run.game_build_id   — 판정이 어느 버전 것인가. 시작할 때 인스턴스의 build 를 고정한다.
--      인스턴스의 `last_game_build_id` 는 다음 등록에서 바뀌므로 나중에 읽으면 틀린다.
--   3. verification_status 에 FAILED · REGRESSION.
--      FAILED     — QA 검증 스텝이 실패했다. BROKEN 은 지도 재생성으로 깨진 행에만 남긴다.
--      REGRESSION — 앞 버전에서 판정이 났고 이번 버전에서 다시 검증해야 한다.
--   4. qa_log.type 에 CASE_VERDICT — 판정이 TC 상태에 반영됐다는 기록. SSE 는 qa_log 를 읽으므로
--      화면이 실시간으로 받으려면 여기 적혀야 한다.
--
-- 게이트가 둘이다(V96 주석). `QaLogService.TYPES` 와 아래 CHECK 가 같은 목록을 들고 있어야 한다.
--------------------------------------------------------------------------------

ALTER TABLE test_run
    ADD COLUMN IF NOT EXISTS game_build_id BIGINT REFERENCES game_build (id) ON DELETE SET NULL;

ALTER TABLE qa_run
    ADD COLUMN IF NOT EXISTS game_build_id BIGINT REFERENCES game_build (id) ON DELETE SET NULL;

-- qa_run 백필: 성능 요약이 실행 시점의 build 를 이미 적어 두었다(V38). 없으면 인스턴스의 지금
-- build 로 채운다 — 정확하지 않을 수 있지만 비워 두는 것보다 트리에서 덜 놀랍다.
UPDATE qa_run qr
   SET game_build_id = COALESCE(
           (SELECT s.game_build_id FROM sdk_performance_run_summary s
             WHERE s.qa_run_id = qr.id AND s.game_build_id IS NOT NULL
             LIMIT 1),
           (SELECT gi.last_game_build_id FROM game_instance gi WHERE gi.id = qr.game_instance_id)
       )
 WHERE qr.game_build_id IS NULL;

-- test_run 백필: 그 TR 의 마지막 QA 실행의 build. 한 번도 안 돌린 TR 은 NULL("버전 없음")이다.
UPDATE test_run tr
   SET game_build_id = (
           SELECT qr.game_build_id FROM qa_run qr
            WHERE qr.test_run_id = tr.id AND qr.game_build_id IS NOT NULL
            ORDER BY qr.started_at DESC, qr.id DESC
            LIMIT 1
       )
 WHERE tr.game_build_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_test_run_game_build ON test_run (game_build_id);

-- V17 이 이름 없이 건 CHECK 라 Postgres 가 붙인 이름이다.
ALTER TABLE test_case DROP CONSTRAINT IF EXISTS test_case_verification_status_check;
ALTER TABLE test_case ADD CONSTRAINT test_case_verification_status_check CHECK (
    verification_status IN ('DRAFT', 'VERIFIED', 'FAILED', 'REGRESSION', 'BROKEN')
);

-- build 를 가로질러 같은 케이스를 찾는 조회(가져오기의 case_id 바꿔 끼우기, 이력, REGRESSION)가
-- 모두 이 두 칸으로 찾는다.
CREATE INDEX IF NOT EXISTS idx_test_case_case_key
    ON test_case (project_id, (metadata ->> 'case_key'))
    WHERE (metadata ->> 'case_key') IS NOT NULL;

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
        'CASE_VERDICT'
    )
);
