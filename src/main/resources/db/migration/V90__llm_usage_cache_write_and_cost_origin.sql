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

-- 이 마이그레이션 전에 금액이 있던 행은 전부 provider 가 청구액을 준 것들이다. 아래 Bedrock
-- 소급 계산보다 **먼저** 돌아야 한다 — 순서가 바뀌면 계산해 넣은 값이 청구액으로 도장 찍힌다.
UPDATE llm_usage SET cost_estimated = FALSE WHERE cost_usd IS NOT NULL AND cost_estimated IS NULL;

-- Bedrock 이 도는 동안 쌓인 행에 금액을 되짚는다.
--
-- **Bedrock 은 청구액을 영영 안 준다.** 그래서 이 provider 의 금액은 언제 채우든 추정치이고,
-- 앞으로 들어올 행도 agent 가 같은 단가로 계산해 넣는다(ARTEL-793). 지난 행만 빈 칸으로 두면
-- 그 구간의 지출을 화면에서 아예 못 본다 — 계정 단위 CloudWatch 는 런에 안 붙는다.
--
-- **cache write 몫이 빠져 있어 실제보다 낮다.** 그 칸이 이 마이그레이션에서 처음 생기므로 지난
-- 행에는 값이 없고, 되살릴 방법도 없다. 3 일 실측으로 그 몫이 전체의 약 8% 였다. 낮게 나오는
-- 것을 알고 넣는 값이고, `cost_estimated` 가 그것을 청구액과 갈라 준다.
--
-- 단가는 `ModelSpec.pricing` 과 같은 값이다(AWS 콘솔, us-west-2, on-demand, 2026-09-03).
-- 백만 토큰당 input $1.00 · output $5.00 · cache read $0.10. `cached_input_tokens` 는
-- `input_tokens` 에 **포함된** 값이라 정가에서 빼고 캐시 단가로 다시 센다 — 안 빼면 캐시로 아낀
-- 만큼이 두 번 청구된 것으로 나온다.
--
-- 모델을 이름으로 좁힌다. 단가를 아는 것이 이 모델 하나뿐이고, 다른 Bedrock 모델이 이 값을
-- 물려받으면 그 행은 틀렸는데 그럴듯한 숫자를 갖게 된다.
UPDATE llm_usage
   SET cost_usd = (
           (input_tokens - cached_input_tokens) * 1.00
           + cached_input_tokens * 0.10
           + output_tokens * 5.00
       ) / 1000000.0,
       cost_estimated = TRUE
 WHERE cost_usd IS NULL
   AND model = 'bedrock/us.anthropic.claude-haiku-4-5-20251001-v1:0';

-- 금액이 있으면 출처가 있어야 하고, 없으면 출처도 없어야 한다. 이것이 없으면 계산해 넣은 값이
-- 출처 없이 앉을 수 있고, 그 행은 영영 청구액과 구분되지 않는다.
ALTER TABLE llm_usage
    ADD CONSTRAINT ck_llm_usage_cost_origin
    CHECK ((cost_usd IS NULL) = (cost_estimated IS NULL));
