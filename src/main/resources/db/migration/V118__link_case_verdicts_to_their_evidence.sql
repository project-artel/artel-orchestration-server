-- TC 판정이 어느 판정 기록에서 왔는지 남긴다(ARTEL-1134).
--
-- 판정 근거(사유 · 기대 항목별 근거 · 판독 기록과 대조한 결과)는 agent 가 스텝 STATUS 로 보내고 qa_log 에
-- 그대로 남는다. 지금까지 TC 판정에는 성공/실패와 시각만 있어서, TC 화면에서 "왜 이 판정인가" 로 갈 길이
-- 없었다. 그 STATUS 행을 가리킨다.
--
-- qa_log 가 지워지면(qa_try 삭제) 판정은 남기고 연결만 끊는다 — 판정 자체는 TC 의 상태다.
ALTER TABLE test_case_version
    ADD COLUMN verdict_qa_log_id BIGINT REFERENCES qa_log (id) ON DELETE SET NULL;

-- qa_log 를 지울 때 위 ON DELETE 가 이 칸으로 찾는다.
CREATE INDEX idx_test_case_version_verdict_qa_log ON test_case_version (verdict_qa_log_id);
