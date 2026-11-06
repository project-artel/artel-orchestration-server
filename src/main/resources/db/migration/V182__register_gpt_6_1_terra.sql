INSERT INTO llm_model (
    id, label, provider, supports_strict_json, max_input_tokens, input_modalities,
    reasoning_kind, reasoning_efforts, pricing, enabled, sort_order
) VALUES (
    'gpt/gpt-6.1-terra', 'GPT-6.1 Terra (OpenAI API)', 'openai', TRUE, 922000,
    '["text","image","file"]'::jsonb, 'effort',
    '["max","xhigh","high","medium","low"]'::jsonb,
    '{"inputPerMtok":1,"outputPerMtok":5,"cacheReadPerMtok":0.05,"cacheWritePerMtok":0,"asOf":"2026-11-06","source":"https://developers.openai.com/api/docs/pricing"}'::jsonb,
    TRUE, 225
)
ON CONFLICT (id) DO NOTHING;
