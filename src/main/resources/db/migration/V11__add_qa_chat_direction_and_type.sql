-- QA 진행 중 사용자가 Agent에게 말을 걸 수 있게 되면서, 그 대화도 다른 프레임과
-- 같은 감사 로그에 남는다. 대화는 실행에 개입하므로 증거의 일부다.
--
-- USER_TO_ORCHE: 사용자가 보낸 발화. 기존 다섯 방향은 모두 서버 간 통신이라
-- 사용자를 출발점으로 하는 방향이 없었다. Agent의 답변은 AGENT_TO_ORCHE를
-- 그대로 쓰고, 중계 기록은 ORCHE_TO_AGENT를 쓴다.
--
-- CHAT: 양방향 공용 타입. 방향으로 발화자가 구분되므로 타입을 나누지 않는다.

ALTER TABLE qa_log DROP CONSTRAINT IF EXISTS qa_log_direction_check;

ALTER TABLE qa_log ADD CONSTRAINT qa_log_direction_check CHECK (
    direction IN (
        'AGENT_TO_ORCHE',
        'ORCHE_TO_AGENT',
        'ORCHE_TO_SDK',
        'SDK_TO_ORCHE',
        'ORCHE_INTERNAL',
        'USER_TO_ORCHE'
    )
);

ALTER TABLE qa_log DROP CONSTRAINT IF EXISTS qa_log_type_check;

ALTER TABLE qa_log ADD CONSTRAINT qa_log_type_check CHECK (
    type IN (
        'LOG',
        'ACTION',
        'ACTION_RESULT',
        'GAME_STATE',
        'STATUS',
        'ERROR',
        'CHAT'
    )
);
