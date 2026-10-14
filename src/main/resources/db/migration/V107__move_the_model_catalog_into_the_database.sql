-- model 카탈로그를 artel-agent-server 의 app/llm/models.py(LLMModel, MODEL_SPECS)에서 이 테이블로 옮긴다.
-- 이제 카탈로그의 원본은 이 테이블이고, agent-server 는 GET /internal/llm-models 로 읽는다.
--
-- model 을 추가하는 일은 이 테이블에 행을 넣는 새 migration 이다. 관리 화면이 바꾸는 것은 enabled 하나뿐이다.

CREATE TABLE IF NOT EXISTS llm_model (
    -- 카탈로그 값. `bedrock/` 로 시작하면 AWS Bedrock 경로이고, 나머지는 OpenRouter slug 다.
    id VARCHAR(200) PRIMARY KEY,
    label VARCHAR(200) NOT NULL,
    -- OpenRouter slug 의 author 부분: openai, anthropic, google, x-ai, moonshotai, z-ai, qwen.
    provider VARCHAR(32) NOT NULL,
    supports_strict_json BOOLEAN NOT NULL,
    -- 보낼 수 있는 입력의 상한이다. 광고하는 context 전체가 아니라 거기서 최대 출력을 뺀 값이다.
    max_input_tokens INTEGER NOT NULL,
    -- 문자열 배열. "image" 가 있으면 vision 을 지원한다.
    input_modalities JSONB NOT NULL,
    -- effort, max_tokens, 또는 NULL(추론 parameter 를 받지 않는 model).
    reasoning_kind VARCHAR(16),
    reasoning_efforts JSONB,
    reasoning_min_tokens INTEGER,
    reasoning_max_tokens INTEGER,
    reasoning_step INTEGER,
    -- 백만 token 당 달러. provider 가 청구액을 응답에 싣지 않는 model 에만 채우고, 없으면 NULL 이다.
    -- 키는 camelCase: inputPerMtok, outputPerMtok, cacheWritePerMtok, cacheReadPerMtok, asOf, source,
    -- longContextThresholdTokens, longContextInputPerMtok, longContextOutputPerMtok,
    -- longContextCacheWritePerMtok, longContextCacheReadPerMtok.
    pricing JSONB,
    -- false 면 model 선택 목록에서 빠지고, 이 model 을 고른 요청은 agent-server 가 422 로 거절한다.
    -- 내부 호출(router, compaction, 기본 model)과 지난 사용량의 단가 계산은 그대로 이 행을 읽는다.
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL,
    updated_by BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_llm_model_sort_order ON llm_model (sort_order);

INSERT INTO llm_model (
    id, label, provider, supports_strict_json, max_input_tokens, input_modalities,
    reasoning_kind, reasoning_efforts, pricing, enabled, sort_order
) VALUES
    ('openai/gpt-5.6-luna', 'GPT-5.6 Luna', 'openai', TRUE, 922000,
     '["text","image","file"]'::jsonb,
     'effort', '["max","xhigh","high","medium","low"]'::jsonb, NULL, TRUE, 10),
    ('openai/gpt-5.6-sol', 'GPT-5.6 Sol', 'openai', TRUE, 922000,
     '["text","image","file"]'::jsonb,
     'effort', '["max","xhigh","high","medium","low"]'::jsonb, NULL, TRUE, 20),
    -- 추론하지 않는 유일한 항목이다. slug 가 ChatGPT 가 지금 내보내는 model 을 따라가므로 창 크기를 가장 자주 다시 봐야 한다.
    ('openai/gpt-chat-latest', 'GPT Chat Latest', 'openai', TRUE, 272000,
     '["text","image","file"]'::jsonb,
     NULL, NULL, NULL, TRUE, 30),
    ('anthropic/claude-haiku-5.5', 'Claude Haiku 5.5', 'anthropic', TRUE, 872000,
     '["text","image","file"]'::jsonb,
     'effort', '["max","xhigh","high","medium","low"]'::jsonb, NULL, TRUE, 40),
    ('anthropic/claude-sonnet-5', 'Claude Sonnet 5', 'anthropic', TRUE, 872000,
     '["text","image","file"]'::jsonb,
     'effort', '["max","xhigh","high","medium","low"]'::jsonb, NULL, TRUE, 50),
    ('anthropic/claude-opus-5', 'Claude Opus 5', 'anthropic', TRUE, 872000,
     '["text","image","file"]'::jsonb,
     'effort', '["max","xhigh","high","medium","low"]'::jsonb, NULL, TRUE, 60),
    ('google/gemini-3.8-flash', 'Gemini 3.8 Flash', 'google', TRUE, 983040,
     '["text","image","file","audio","video"]'::jsonb,
     'effort', '["high","medium","low"]'::jsonb, NULL, TRUE, 70),
    ('google/gemini-3.7-flash', 'Gemini 3.7 Flash', 'google', TRUE, 983040,
     '["text","image","file","audio","video"]'::jsonb,
     'effort', '["high","medium","low"]'::jsonb, NULL, TRUE, 80),
    -- json_object 만 지원한다(structured_outputs 없음). strict 가 아닐 때의 대체 경로를 쓰게 하려고 둔다.
    ('google/gemma-4-31b-it:free', 'Gemma 4 (free)', 'google', FALSE, 229376,
     '["text","image","video"]'::jsonb,
     NULL, NULL, NULL, TRUE, 90),
    ('x-ai/grok-4.6', 'Grok 4.6', 'x-ai', TRUE, 50000,
     '["text","image","file"]'::jsonb,
     'effort', '["xhigh","high","medium","low"]'::jsonb, NULL, TRUE, 100),
    ('moonshotai/kimi-k3', 'Kimi K3', 'moonshotai', TRUE, 104858,
     '["text","image","video"]'::jsonb,
     'effort', '["max","high","low"]'::jsonb, NULL, TRUE, 110),
    ('z-ai/glm-5.3-flash', 'GLM-5.3 Flash', 'z-ai', TRUE, 1179648,
     '["text","image","video"]'::jsonb,
     'effort', '["max","high","low"]'::jsonb, NULL, TRUE, 120),
    ('qwen/qwen3.8-max', 'Qwen3.8 Max', 'qwen', TRUE, 868928,
     '["text","image","video"]'::jsonb,
     'effort', '["xhigh","high","medium","low"]'::jsonb, NULL, TRUE, 130),
    -- AWS 계정이 아직 이 model 에 접근할 수 없어(Converse 호출이 "not available for this account") 꺼 둔 채 넣는다.
    -- AWS 가 접근을 열어 주면 관리자가 관리 화면에서 켠다. 청구액을 응답에 싣지 않아 단가를 여기 적는다.
    -- 캐시 쓰기 단가는 5 분 TTL 기준이다(1 시간은 $0.20).
    ('bedrock/us.anthropic.claude-haiku-5-5', 'Claude Haiku 5.5 (AWS Bedrock)', 'anthropic', FALSE, 872000,
     '["text","image"]'::jsonb,
     'effort', '["max","xhigh","high","medium","low"]'::jsonb,
     '{"inputPerMtok":0.10,"outputPerMtok":0.50,"cacheWritePerMtok":0.125,"cacheReadPerMtok":0.01,"asOf":"2026-10-13","source":"Anthropic Claude Haiku 5.5 overview","longContextThresholdTokens":100000,"longContextInputPerMtok":0.50,"longContextOutputPerMtok":2.50,"longContextCacheWritePerMtok":0.625,"longContextCacheReadPerMtok":0.05}'::jsonb,
     FALSE, 140),
    -- 이 model 은 Converse 로 호출해 보지 못했다. effort 목록은 OpenRouter 의 openai/gpt-6.1-sol 항목을 따랐고,
    -- structured outputs 는 확인하지 못해 false 다. 단가를 확인하지 못해 pricing 은 NULL 이다.
    -- max_input_tokens 는 context 1,050,000 에서 최대 출력 128,000 을 뺀 값이다.
    ('bedrock/us.openai.gpt-6.1-sol', 'GPT-6.1 Sol (AWS Bedrock)', 'openai', FALSE, 922000,
     '["text","image","file"]'::jsonb,
     'effort', '["max","xhigh","high","medium","low"]'::jsonb, NULL, FALSE, 150)
ON CONFLICT (id) DO NOTHING;
