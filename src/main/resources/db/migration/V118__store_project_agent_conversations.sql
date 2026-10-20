-- project agent 대화를 저장한다(ARTEL-1127).
--
-- 대화는 (프로젝트, 사용자) 단위의 개인 스레드다. 한 turn 의 model 호출은 agent-server 의
-- POST /internal/project-agent/step 이 상태 없이 한 번씩 처리하고, 이어 가는 loop 는 orchestration 이
-- 쥔다. 그래서 대화가 어디까지 왔는지(status)와 지금까지의 메시지가 전부 이 두 table 에 있어야 한다.
--
-- status:
--   IDLE           다음 입력을 받을 수 있다
--   RUNNING        step 호출이 나가 있다. 이 동안 새 입력은 409 다
--   AWAITING_TOOL  마지막 ASSISTANT 메시지의 tool call 에 browser 가 답하기를 기다린다
--   ERROR          step 호출이 실패했다. 다음 입력은 받는다
--
-- payload 는 role 마다 모양이 다르다(ASSISTANT 는 toolCalls, TOOL_RESULT 는 callId·status 등).
-- 서버는 이 안을 읽기만 하고 browser 의 PageSnapshot 같은 값은 그대로 보관한다.

CREATE TABLE project_agent_conversation (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    app_user_id     BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    title           VARCHAR(200),
    status          VARCHAR(20) NOT NULL DEFAULT 'IDLE'
                    CHECK (status IN ('IDLE', 'RUNNING', 'AWAITING_TOOL', 'ERROR')),
    page_catalog    JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_message_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_pac_project_user
    ON project_agent_conversation (project_id, app_user_id, last_message_at DESC);

CREATE TABLE project_agent_message (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES project_agent_conversation (id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL
                    CHECK (role IN ('USER', 'ASSISTANT', 'TOOL_RESULT', 'EVENT', 'NOTICE')),
    content         TEXT NOT NULL DEFAULT '',
    payload         JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_pam_conversation ON project_agent_message (conversation_id, id);

-- llm_usage.service 에 PROJECT_AGENT 를 더한다. reference_id 는 project_agent_conversation.id 다.
-- V24 가 이름 없이 만든 CHECK 라 PostgreSQL 이 llm_usage_service_check 로 이름 붙였다.
ALTER TABLE llm_usage DROP CONSTRAINT llm_usage_service_check;
ALTER TABLE llm_usage
    ADD CONSTRAINT llm_usage_service_check
    CHECK (service IN ('QA_RUN', 'SCENARIO', 'KNOWLEDGE_QUERY', 'GAME_CONTEXT', 'EMBEDDING', 'PROJECT_AGENT'));
