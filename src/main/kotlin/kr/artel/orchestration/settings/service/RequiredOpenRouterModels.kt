package kr.artel.orchestration.settings.service

/**
 * OpenRouter key 하나로 ARTEL 이 부르는 model 전부. key 확인이 이 목록을 기준으로 닿는 것과 닿지 않는
 * 것을 가른다.
 *
 * 앞의 12 개는 `artel-agent-server` 의 `app/llm/models.py` 카탈로그에 있는 chat model 이고, 마지막은
 * knowledge 와 test case 벡터를 만드는 embedding model 이다(`artel.knowledge.backfill.model`).
 * 카탈로그가 바뀌면 이 목록도 함께 바꾼다.
 */
val REQUIRED_OPENROUTER_MODELS: List<String> = listOf(
    "openai/gpt-5.6-luna",
    "openai/gpt-5.6-sol",
    "openai/gpt-chat-latest",
    "anthropic/claude-sonnet-5",
    "anthropic/claude-opus-5",
    "google/gemini-3.8-flash",
    "google/gemini-3.7-flash",
    "google/gemma-4-31b-it:free",
    "x-ai/grok-4.6",
    "moonshotai/kimi-k3",
    "z-ai/glm-5.3-flash",
    "qwen/qwen3.8-max",
    "openai/text-embedding-3-large"
)
