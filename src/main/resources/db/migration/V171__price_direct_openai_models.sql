-- OpenAI 직접 호출은 청구 금액 대신 토큰 사용량을 반환하므로 catalog 단가로 추정한다.
-- agent의 input_includes_cache_write 정규화를 먼저 반영해야 한다. 반대 순서면
-- input_tokens에 포함된 cache write가 일반 입력과 쓰기로 중복 계산된다.
-- Standard / 기본 30m cache 기준. 12h, Fast, Flex, Batch, 지역 할증은 포함하지 않는다.
-- 출처: https://developers.openai.com/api/docs/pricing 및 각 모델 페이지 (2026-11-03).
-- 272K 초과 시 요청 전체 입력·캐시 단가는 2배, 출력은 1.5배다.
-- 기존 단가가 있으면 보존하며 과거 llm_usage는 재계산하지 않는다.
UPDATE llm_model AS model
SET pricing = jsonb_build_object(
        'inputPerMtok', rates.input_rate,
        'outputPerMtok', rates.output_rate,
        'cacheReadPerMtok', rates.cache_read_rate,
        'cacheWritePerMtok', rates.input_rate * 1.25,
        'asOf', '2026-11-03',
        'source', 'https://developers.openai.com/api/docs/models/' || substring(rates.id FROM 5),
        'longContextThresholdTokens', 272000,
        'longContextInputPerMtok', rates.input_rate * 2,
        'longContextOutputPerMtok', rates.output_rate * 1.5,
        'longContextCacheReadPerMtok', rates.cache_read_rate * 2,
        'longContextCacheWritePerMtok', rates.input_rate * 2.5
    ),
    updated_at = NOW()
FROM (VALUES
    ('gpt/gpt-6.1-sol', 2.00, 10.00, 0.10),
    ('gpt/gpt-6-astra', 10.00, 50.00, 1.00),
    ('gpt/gpt-6-luna', 0.10, 0.50, 0.01),
    ('gpt/gpt-5.6-terra', 2.00, 12.00, 0.20)
) AS rates(id, input_rate, output_rate, cache_read_rate)
WHERE model.id = rates.id AND model.pricing IS NULL;
