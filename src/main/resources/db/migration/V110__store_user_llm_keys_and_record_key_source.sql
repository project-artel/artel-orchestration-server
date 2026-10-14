--------------------------------------------------------------------------------
-- V110. 사용자 LLM API key 를 저장하고, run 과 사용량이 어느 key 로 돌았는지 남긴다
--------------------------------------------------------------------------------
-- 계약은 `.plan/general/2026-10-14-user-llm-keys.md` 다(ARTEL-1037).
--
-- 담는 것 다섯:
--   1. user_llm_key           — 사용자가 넣은 key. 서비스마다 하나다
--   2. qa_run · qa_try 의 key_source — 그 run 이 공용 key(`platform`)와 사용자 key(`user`) 중 무엇으로 돌았는지
--   3. llm_usage.key_source   — 호출 한 건이 어느 key 로 나갔는지. agent-server 가 보낸다
--   4. llm_model 의 `gpt/` 행 — OpenAI API 로 직접 부르는 경로. 사용자 key 로만 고를 수 있다
--   5. `claude/` 행을 켠다     — 공용 Anthropic key 가 없어도 사용자 key 로는 고를 수 있다

--------------------------------------------------------------------------------
-- 1. user_llm_key
--------------------------------------------------------------------------------
-- key 원문은 `SecretCipher` 로 암호화해 둔다. associated data 는 `user.{app_user_id}.llm.{service}.api_key`
-- 라서 한 사용자의 암호문을 다른 사용자나 다른 서비스 자리로 옮겨도 열리지 않는다.
--
-- 저장은 `PUT /api/auth/me/llm-keys/{service}` 가 서비스에 한 번 물어 key 가 통한 뒤에만 한다.
-- `verified_at` 이 그 시각이다.
CREATE TABLE IF NOT EXISTS user_llm_key (
    app_user_id   BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    -- anthropic, openai, openrouter.
    service       VARCHAR(20)  NOT NULL CHECK (service IN ('anthropic', 'openai', 'openrouter')),
    encrypted_key TEXT         NOT NULL,
    -- 앞 6 글자 + `…` + 끝 4 글자. 사용자가 어느 key 를 넣었는지 알아보는 데 쓴다.
    key_hint      VARCHAR(40)  NOT NULL,
    verified_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (app_user_id, service)
);

--------------------------------------------------------------------------------
-- 2. qa_run · qa_try 의 key_source
--------------------------------------------------------------------------------
-- 지출 화면이 공용 key 로 낸 돈과 사용자가 낸 돈을 가르는 근거다. 단일 시나리오 경로(`POST /api/qa-tries`)는
-- qa_run 없이 qa_try 만 만들므로 두 표에 다 둔다. run 경로는 run 과 그 아래 try 에 같은 값을 쓴다.
--
-- 기존 행은 모두 공용 key 로 돌았다.
ALTER TABLE qa_run
    ADD COLUMN IF NOT EXISTS key_source VARCHAR(16) NOT NULL DEFAULT 'platform'
        CHECK (key_source IN ('platform', 'user'));

ALTER TABLE qa_try
    ADD COLUMN IF NOT EXISTS key_source VARCHAR(16) NOT NULL DEFAULT 'platform'
        CHECK (key_source IN ('platform', 'user'));

--------------------------------------------------------------------------------
-- 3. llm_usage.key_source
--------------------------------------------------------------------------------
-- agent-server 가 `keySource` 를 보내지 않으면 `platform` 이다. 이 열이 생기기 전의 agent-server 는
-- 공용 key 만 쓴다.
ALTER TABLE llm_usage
    ADD COLUMN IF NOT EXISTS key_source VARCHAR(16) NOT NULL DEFAULT 'platform'
        CHECK (key_source IN ('platform', 'user'));

--------------------------------------------------------------------------------
-- 4. llm_model 의 `gpt/` 행
--------------------------------------------------------------------------------
-- `openai/` 네 행을 OpenAI API 경로로 복사한다. `gpt/` 뒤는 OpenAI model id 이고, `claude/` 뒤가 Anthropic
-- model id 인 것과 같은 규칙이다. 이 네 id 가 OpenAI API 에 그대로 있는지는 확인하지 못했다.
--
-- 공용 OpenAI key 가 없어 `/api/qa-models` 는 이 행을 사용자 key 항목으로만 낸다. 그래서 켠 채로 넣는다.
-- 창, modality, effort, pricing 은 OpenRouter 행과 같다. 그 행의 pricing 이 NULL 이라 여기도 NULL 이다.
INSERT INTO llm_model (
    id, label, provider, supports_strict_json, max_input_tokens, input_modalities,
    reasoning_kind, reasoning_efforts, reasoning_min_tokens, reasoning_max_tokens, reasoning_default_tokens,
    pricing, enabled, sort_order
) VALUES
    ('gpt/gpt-6.1-sol', 'GPT-6.1 Sol (OpenAI API)', 'openai', TRUE, 922000,
     '["text","image","file"]'::jsonb,
     'effort', '["max","xhigh","high","medium","low"]'::jsonb, NULL, NULL, NULL, NULL, TRUE, 200),
    ('gpt/gpt-6-astra', 'GPT-6 Astra (OpenAI API)', 'openai', TRUE, 922000,
     '["text","image","file"]'::jsonb,
     'effort', '["max","xhigh","high","medium","low"]'::jsonb, NULL, NULL, NULL, NULL, TRUE, 210),
    ('gpt/gpt-6-luna', 'GPT-6 Luna (OpenAI API)', 'openai', TRUE, 922000,
     '["text","image","file"]'::jsonb,
     'effort', '["max","xhigh","high","medium","low"]'::jsonb, NULL, NULL, NULL, NULL, TRUE, 220),
    ('gpt/gpt-5.6-terra', 'GPT-5.6 Terra (OpenAI API)', 'openai', TRUE, 922000,
     '["text","image","file"]'::jsonb,
     'effort', '["max","xhigh","high","medium","low"]'::jsonb, NULL, NULL, NULL, NULL, TRUE, 230)
ON CONFLICT (id) DO NOTHING;

--------------------------------------------------------------------------------
-- 5. `claude/` 행을 켠다
--------------------------------------------------------------------------------
-- V109 는 공용 Anthropic key 가 생길 때까지 이 넷을 꺼 두었다. `enabled` 는 행 전체를 끄고 켜므로, 꺼 두면
-- 사용자 key 로도 고를 수 없다. 이제 공용 key 가 없는 것은 `/api/qa-models` 와 실행 요청이 따로 본다 — 관리
-- 화면의 Claude API key 가 없으면 공용 항목을 내지 않고, `keySource: platform` 요청은 400 `llm_key_missing` 이다.
UPDATE llm_model
   SET enabled = TRUE, updated_at = CURRENT_TIMESTAMP
 WHERE id IN ('claude/claude-opus-5-5', 'claude/claude-sonnet-5-5', 'claude/claude-haiku-5-5', 'claude/claude-fable-5-1');
