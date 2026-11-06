-- ARTEL-668: 관측이 확정한 화면을 agent 에게 알린다.
--
-- ## 왜 프레임이 하나 더 필요한가
--
-- ARTEL-657 이 QA agent 에게 화면 판정 목록을 고칠 tool 두 개를 줬다. 그 tool 을 부르는 계기는
-- "화면이 눈에 띄게 달라졌는데 지도가 같은 화면이라고 한다" 이므로, agent 는 지도가 지금 뭐라고
-- 하는지를 볼 수 있어야 한다.
--
-- 그것을 실어 나르던 프레임이 V61 의 `SCREEN_SELECTOR_PROPOSAL` 하나였고, 그 프레임은
-- `uk_screen_selector_proposal` 이 보장하는 대로 `(scene, selector)` 마다 **평생 한 번**만 나간다.
-- 그래서 이미 한 번 플레이한 빌드에서는 제안이 한 장도 안 나가고, agent 는 런 내내 화면을 못 본다.
-- tool 둘이 실질적으로 닿지 않는 상태였다.
--
-- 질문과 사실은 나가는 조건이 다르다. 질문은 물어볼 것이 새로 생겼을 때 한 번, 사실은 그 사실이
-- 달라질 때마다다. 한 타입에 둘을 실으면 둘 중 드문 쪽의 조건이 이긴다 — 그것이 위 상태다.
--
--   SCREEN_SETTLED  관측이 확정한 화면을 알린다 (ORCHE_TO_AGENT). 답이 없다

--------------------------------------------------------------------------------
-- qa_log 타입 — 새 프레임 하나
--------------------------------------------------------------------------------
-- 게이트가 둘이라는 것을 잊지 말 것(V57 · V61 주석). `QaLogService.TYPES` 의 `require` 와 이 CHECK 가
-- 같은 목록을 각자 들고 있고, 한쪽만 열면 통과한 값이 INSERT 에서 죽는다. 어긋나면
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
        'TOOL_RESULT',
        'SCREEN_SELECTOR_PROPOSAL',
        'SCREEN_SELECTOR_VERDICT',
        'SCREEN_SELECTOR_RULE',
        'SCREEN_SELECTOR_RESULT',
        'SCREEN_SETTLED'
    )
);
