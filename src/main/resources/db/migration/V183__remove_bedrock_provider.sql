--------------------------------------------------------------------------------
-- Bedrock 공급자를 걷는다(ARTEL-1439).
--
-- Bedrock 은 더 부르지 않는다. agent-server 는 `bedrock/` model 을 거절하고(ARTEL-1438), 관리 화면의 Bedrock key
-- 칸도 사라진다. 그래서 카탈로그의 `bedrock/` 행과 저장된 Bedrock key 를 지우고, 그 model 을 가리키던 선택은
-- 비워 기본값(관리자 설정이 없으면 고르지 않은 상태)으로 돌린다.
--
-- 지난 사용량(llm_usage)과 QA 실행 기록의 model 칸은 그대로 둔다. 무엇으로 돌았는지는 남아야 한다.
--------------------------------------------------------------------------------

DELETE FROM platform_setting WHERE setting_key IN ('bedrock.api_key', 'bedrock.last_check');

-- 기능별 model(시나리오 저작 · 결정 · 프로젝트 Agent)이 Bedrock 행이었으면 지운다.
DELETE FROM platform_setting WHERE setting_key LIKE 'llm.model.%' AND setting_value LIKE 'bedrock/%';

-- 프로젝트 Agent 의 사용자 · 프로젝트 선택(V136). 둘 다 외래 키가 없어 따로 비운다.
UPDATE app_user SET project_agent_model = NULL, project_agent_key_source = NULL
WHERE project_agent_model LIKE 'bedrock/%';
UPDATE project SET project_agent_model = NULL WHERE project_agent_model LIKE 'bedrock/%';

DELETE FROM llm_model WHERE id LIKE 'bedrock/%';
