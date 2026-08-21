-- V47__carry_question_payload_on_run_message.sql
--
-- 저작이 사용자에게 **되묻는** 자리를 만든다(ARTEL-487).
--
-- 지금도 에이전트는 묻는다 — 어시스턴트 메시지 189건 중 28건에 물음표가 있었고, 실제로
-- "진입 방법을 알려 주세요" 같은 문장이 나온다. 그런데 산문 속에 묻혀 있어서 사용자는 그것을
-- 설명으로 읽고 지나가고, 답하려 해도 무엇을 답해야 하는지 알 수 없다.
--
-- 질문을 **선택지와 함께** 저장한다. 선택지를 SSE 로만 흘리면 새로고침 한 번에 사라지고, 그러면
-- 질문은 대화 기록에 남았는데 답할 방법만 없어진다.
--
-- 별도 표가 아니라 컬럼인 이유: 질문은 그 메시지 **자체**이지 메시지에 딸린 다른 것이 아니다.
-- 표를 나누면 조회마다 조인이 붙고, 어느 메시지가 질문인지가 두 곳에 적힌다.
--
-- 알림(notice)처럼 앞으로 생길 구조적 본문도 같은 칸을 쓴다. 그래서 이름이 `question` 이 아니라
-- `payload` 다 — 종류는 본문 안의 `kind` 가 말한다.
ALTER TABLE test_run_message
    ADD COLUMN IF NOT EXISTS payload JSONB;

-- 답을 아직 안 한 질문을 찾는 경로. 세션이 끊겨도 화면이 그 질문을 다시 띄울 수 있어야 한다.
CREATE INDEX IF NOT EXISTS idx_test_run_message_question
    ON test_run_message (test_run_id, created_at DESC)
    WHERE payload IS NOT NULL;
