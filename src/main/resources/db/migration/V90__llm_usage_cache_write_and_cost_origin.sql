-- llm_usage 가 Bedrock 런의 비용을 말할 수 있게 두 칸을 더한다(ARTEL-792).
--
-- **cache_write_tokens.** 캐시에 처음 실을 때 쓴 토큰이고, provider 가 일반 input 보다 비싸게
-- 청구한다. 이 칸이 없으면 provider 가 청구액을 안 알려줄 때 토큰으로 비용을 되짚을 수 없다.
-- 3 일 실측으로 Bedrock 의 `CacheWriteInputTokenCount` 가 1,170,364 였다 — 무시할 크기가 아니다.
-- agent 는 이 값을 이미 응답에서 받고 있고(`input_token_details.cache_creation`) 실을 자리가
-- 없어서 버리고 있었다.
--
-- **cached_input_tokens 와 포함 관계가 다르다.** 그쪽은 input_tokens 에 포함된 값이라 더하면
-- 두 번 세지만, cache_write 는 별개로 청구되는 양이다. 집계에서 둘을 같이 다루면 안 된다.
--
-- **왜 NOT NULL DEFAULT 0 인가.** V24 가 cached_input_tokens/reasoning_tokens 에 대해 이미 편
-- 논증과 같다 — nullable 로 두면 SUM 앞에 COALESCE 가 계속 붙는다. "캐시를 안 썼다" 와
-- "provider 가 안 알려줬다" 가 0 으로 섞이는 것은 옆 칸이 이미 받아들인 손해이고, 여기만 다르게
-- 하면 같은 표의 토큰 칸을 두 가지 규칙으로 읽어야 한다.
--
-- **cost_estimated.** cost_usd 가 provider 가 청구한 값인지 우리가 토큰으로 계산한 값인지
-- 가른다. **어느 쪽인지 모르는 금액은 나중에 아무도 못 믿는다** — 청구액과 추정치가 한 칸에
-- 섞이면 "이 달 얼마 썼나" 의 답이 둘 중 무엇인지 말할 수 없게 된다.
--
-- nullable 이고 DEFAULT 가 없다. cost_usd 가 없는 행에는 가릴 것이 없기 때문이고, DEFAULT FALSE
-- 로 두면 그 행들이 "provider 가 청구한 값" 으로 읽힌다. 아래 CHECK 가 둘의 유무를 묶는다.

ALTER TABLE llm_usage
    ADD COLUMN IF NOT EXISTS cache_write_tokens INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cost_estimated BOOLEAN;

COMMENT ON COLUMN llm_usage.cache_write_tokens IS
    '캐시에 실을 때 쓴 토큰. input_tokens 에 포함되지 않는다 — cached_input_tokens 와 다르다.';
COMMENT ON COLUMN llm_usage.cost_estimated IS
    'cost_usd 가 우리가 계산한 값이면 true, provider 가 청구한 값이면 false. cost_usd 가 없으면 null.';

-- 이 마이그레이션 전의 행은 전부 provider 가 청구액을 준 것들이다. 안 준 provider(Bedrock)의
-- 행은 cost_usd 가 비어 있어 그대로 null 로 남는다.
UPDATE llm_usage SET cost_estimated = FALSE WHERE cost_usd IS NOT NULL AND cost_estimated IS NULL;

-- 금액이 있으면 출처가 있어야 하고, 없으면 출처도 없어야 한다. 이것이 없으면 계산해 넣은 값이
-- 출처 없이 앉을 수 있고, 그 행은 영영 청구액과 구분되지 않는다.
ALTER TABLE llm_usage
    ADD CONSTRAINT ck_llm_usage_cost_origin
    CHECK ((cost_usd IS NULL) = (cost_estimated IS NULL));
