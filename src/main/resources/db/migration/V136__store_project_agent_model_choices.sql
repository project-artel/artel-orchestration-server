--------------------------------------------------------------------------------
-- project agent 가 쓸 model 을 사용자와 프로젝트 단위로 저장한다.
--
-- 우선순위는 사용자 > 프로젝트 > 관리자(platform_setting 의 llm.model.project_agent) 다.
--
-- app_user 의 두 칸은 그 사용자의 모든 프로젝트와 대화에 걸린다. key_source 는 QA run 과 같은 값
-- ('platform' | 'user')이고, model 이 null 이면 둘 다 null 이다 — 관리자 기본값으로 돌아간다.
--
-- project 의 칸은 공용 key 로 부를 수 있는 model 만 받는다. 프로젝트 설정은 멤버 모두에게 걸리므로
-- 누군가의 개인 key 로 돌게 둘 수 없어 key_source 칸을 두지 않는다.
--
-- 셋 다 llm_model.id 를 담지만 외래 키를 걸지 않는다. platform_setting 의 model 행과 같이, 카탈로그에서
-- 빠진 model 은 읽을 때 건너뛰거나 원인을 알린다.
--------------------------------------------------------------------------------

ALTER TABLE app_user ADD COLUMN project_agent_model TEXT;
ALTER TABLE app_user ADD COLUMN project_agent_key_source VARCHAR(16);
ALTER TABLE app_user ADD CONSTRAINT ck_app_user_project_agent_key_source
    CHECK (project_agent_key_source IS NULL OR project_agent_key_source IN ('platform', 'user'));

ALTER TABLE project ADD COLUMN project_agent_model TEXT;
