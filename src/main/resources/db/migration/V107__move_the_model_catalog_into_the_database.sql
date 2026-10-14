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
    -- 요청이 추론을 고르지 않았을 때 켤 token 예산. provider 기본값이 없는 model 에만 채운다.
    -- Bedrock 의 Anthropic `budget_tokens` 경로는 이 값이 없으면 추론을 아예 하지 않는다.
    reasoning_default_tokens INTEGER,
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
    reasoning_kind, reasoning_efforts, reasoning_min_tokens, reasoning_max_tokens, reasoning_default_tokens,
    pricing, enabled, sort_order
) VALUES
    -- Bedrock 경로이고 `effort` 가 아니라 token 예산으로 추론한다. 창 200,000 에서 출력 예약 16,384
    -- (agent-server 의 예산 4,096 x 2 + 8,192)를 뺀 값이 max_input_tokens 다.
    -- Bedrock 은 응답에 청구액을 싣지 않아 단가를 적는다. 캐시 쓰기 단가는 5 분 TTL 기준이다.
    ('bedrock/us.anthropic.claude-haiku-4-5-20251001-v1:0', 'Claude Haiku 4.5 (AWS Bedrock)', 'anthropic', TRUE, 183616,
     '["text","image"]'::jsonb,
     'max_tokens', NULL, 1024, 32000, 4096,
     '{"inputPerMtok":1.00,"outputPerMtok":5.00,"cacheWritePerMtok":1.25,"cacheReadPerMtok":0.10,"asOf":"2026-09-03","source":"AWS 콘솔, Claude Haiku 4.5 / us-west-2 / on-demand"}'::jsonb,
     TRUE, 10),
    -- 아래 넷은 창(1,050,000 에서 출력 128,000 을 뺀 값), modality, effort 다섯 개가 같다.
    ('openai/gpt-6.1-sol', 'GPT-6.1 Sol', 'openai', TRUE, 922000,
     '["text","image","file"]'::jsonb,
     'effort', '["max","xhigh","high","medium","low"]'::jsonb, NULL, NULL, NULL, NULL, TRUE, 20),
    ('openai/gpt-6-astra', 'GPT-6 Astra', 'openai', TRUE, 922000,
     '["text","image","file"]'::jsonb,
     'effort', '["max","xhigh","high","medium","low"]'::jsonb, NULL, NULL, NULL, NULL, TRUE, 30),
    -- agent-server 의 DEFAULT_MODEL 이다.
    ('openai/gpt-6-luna', 'GPT-6 Luna', 'openai', TRUE, 922000,
     '["text","image","file"]'::jsonb,
     'effort', '["max","xhigh","high","medium","low"]'::jsonb, NULL, NULL, NULL, NULL, TRUE, 40),
    ('openai/gpt-5.6-terra', 'GPT-5.6 Terra', 'openai', TRUE, 922000,
     '["text","image","file"]'::jsonb,
     'effort', '["max","xhigh","high","medium","low"]'::jsonb, NULL, NULL, NULL, NULL, TRUE, 50),
    ('anthropic/claude-opus-5.5', 'Claude Opus 5.5', 'anthropic', TRUE, 872000,
     '["text","image","file"]'::jsonb,
     'effort', '["max","xhigh","high","medium","low"]'::jsonb, NULL, NULL, NULL, NULL, TRUE, 60),
    ('anthropic/claude-sonnet-5.5', 'Claude Sonnet 5.5', 'anthropic', TRUE, 872000,
     '["text","image","file"]'::jsonb,
     'effort', '["max","xhigh","high","medium","low"]'::jsonb, NULL, NULL, NULL, NULL, TRUE, 70),
    -- effort 를 셋만 받는다. `max` 나 `xhigh` 를 보내면 provider 가 400 을 돌려준다.
    ('google/gemini-3.8-flash', 'Gemini 3.8 Flash', 'google', TRUE, 983040,
     '["text","image","file","audio","video"]'::jsonb,
     'effort', '["high","medium","low"]'::jsonb, NULL, NULL, NULL, NULL, TRUE, 80),
    -- json_object 만 지원한다(structured_outputs 없음). strict 가 아닐 때의 대체 경로를 쓰게 하려고 둔다.
    ('google/gemma-4-31b-it:free', 'Gemma 4 (free)', 'google', FALSE, 229376,
     '["text","image","video"]'::jsonb,
     NULL, NULL, NULL, NULL, NULL, NULL, TRUE, 90),
    -- 창 500,000 중 450,000 을 provider 가 출력에 잡아 둔다. 카탈로그에서 입력 상한이 가장 작다.
    ('x-ai/grok-4.7', 'Grok 4.7', 'x-ai', TRUE, 50000,
     '["text","image","file"]'::jsonb,
     'effort', '["xhigh","high","medium","low"]'::jsonb, NULL, NULL, NULL, NULL, TRUE, 100),
    ('moonshotai/kimi-k3', 'Kimi K3', 'moonshotai', TRUE, 104858,
     '["text","image","video"]'::jsonb,
     'effort', '["max","high","low"]'::jsonb, NULL, NULL, NULL, NULL, TRUE, 110),
    ('z-ai/glm-5.3-flash', 'GLM-5.3 Flash', 'z-ai', TRUE, 104859,
     '["text","image","video"]'::jsonb,
     'effort', '["max","high","low"]'::jsonb, NULL, NULL, NULL, NULL, TRUE, 120),
    -- 카탈로그에서 유일하게 이미지를 받지 않는다. effort 도 받지 않아 reasoning_kind 가 NULL 이다.
    ('qwen/qwen3.7-max', 'Qwen3.7 Max', 'qwen', TRUE, 868928,
     '["text"]'::jsonb,
     NULL, NULL, NULL, NULL, NULL, NULL, TRUE, 130),
    -- AWS 계정이 아직 이 model 에 접근할 수 없어(Converse 호출이 "not available for this account") 꺼 둔 채 넣는다.
    -- AWS 가 접근을 열어 주면 관리자가 관리 화면에서 켠다. 청구액을 응답에 싣지 않아 단가를 여기 적는다.
    -- 캐시 쓰기 단가는 5 분 TTL 기준이다(1 시간은 $0.20).
    ('bedrock/us.anthropic.claude-haiku-5-5', 'Claude Haiku 5.5 (AWS Bedrock)', 'anthropic', FALSE, 872000,
     '["text","image"]'::jsonb,
     'effort', '["max","xhigh","high","medium","low"]'::jsonb, NULL, NULL, NULL,
     '{"inputPerMtok":0.10,"outputPerMtok":0.50,"cacheWritePerMtok":0.125,"cacheReadPerMtok":0.01,"asOf":"2026-10-13","source":"Anthropic Claude Haiku 5.5 overview","longContextThresholdTokens":100000,"longContextInputPerMtok":0.50,"longContextOutputPerMtok":2.50,"longContextCacheWritePerMtok":0.625,"longContextCacheReadPerMtok":0.05}'::jsonb,
     FALSE, 140),
    -- 이 model 은 Converse 로 호출해 보지 못했다. effort 목록은 OpenRouter 의 openai/gpt-6.1-sol 항목을 따랐고,
    -- structured outputs 는 확인하지 못해 false 다. 단가를 확인하지 못해 pricing 은 NULL 이다.
    -- max_input_tokens 는 context 1,050,000 에서 최대 출력 128,000 을 뺀 값이다.
    ('bedrock/us.openai.gpt-6.1-sol', 'GPT-6.1 Sol (AWS Bedrock)', 'openai', FALSE, 922000,
     '["text","image","file"]'::jsonb,
     'effort', '["max","xhigh","high","medium","low"]'::jsonb, NULL, NULL, NULL, NULL, FALSE, 150)
ON CONFLICT (id) DO NOTHING;
