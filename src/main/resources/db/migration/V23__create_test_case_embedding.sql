-- test_case에 벡터 색인 자리를 만든다. (ARTEL-216, ARTEL-206)
--
-- knowledge_embedding(V18)과 같은 구조·같은 큐 상태 모델을 쓰되 owner가 test_case다. 케이스 검색
-- 챗봇(ARTEL-206)이 자연어 의도로 이 벡터를 검색해 시나리오에 연결한다.
--
-- knowledge와 다른 점:
--   - test_case는 소프트삭제가 없다(하드 삭제만). 그래서 seedPending의 alive 조건이 없고,
--     행 정리는 아래 FK ON DELETE CASCADE가 맡는다.
--   - 이번 단계가 채우는 벡터는 CONTENT(케이스 본문 합성) 1건이다. 검색쿼리 생성(QUERY)은 쓰지 않는다.
--     kind CHECK는 대칭을 위해 두 값을 모두 허용한다.

CREATE TABLE IF NOT EXISTS test_case_embedding (
    id            BIGSERIAL PRIMARY KEY,
    -- test_case가 지워지면 벡터도 함께 지워져야 한다. 남으면 검색이 없는 케이스를 가리킨다.
    test_case_id  BIGINT NOT NULL REFERENCES test_case (id) ON DELETE CASCADE,
    kind          VARCHAR(20) NOT NULL CHECK (kind IN ('QUERY', 'CONTENT')),
    -- 어느 모델이 만든 벡터인지. 모델 교체 판단과 재색인의 기준이라 NOT NULL이다.
    model         VARCHAR(100) NOT NULL,
    -- 임베딩의 입력이 된 문자열(CONTENT면 케이스 본문 합성 한 건).
    source_text   TEXT,
    embedding     vector(1024),
    attempts      INT NOT NULL DEFAULT 0,
    last_error    TEXT,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- 두 상태만 존재한다(중간 상태 없음). V18과 동일한 큐 모델.
    --   대기 행: source_text IS NULL AND embedding IS NULL  → 백필 큐의 원소.
    --   완성 행: 둘 다 NOT NULL                              → 검색이 쓰는 실제 벡터.
    CONSTRAINT ck_test_case_embedding_state CHECK (
        (source_text IS NULL AND embedding IS NULL)
            OR (source_text IS NOT NULL AND embedding IS NOT NULL)
    )
);

-- (test_case_id, kind, model) 조회: 백필의 "이미 처리했나" 판정과 후속 검색의 모델별 조회가 탄다.
CREATE INDEX IF NOT EXISTS idx_test_case_embedding_lookup
    ON test_case_embedding (test_case_id, kind, model);

-- 대기 행은 case·kind·model당 정확히 하나. 워커의 시딩 INSERT가 ON CONFLICT DO NOTHING으로 이 인덱스에 기댄다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_test_case_embedding_pending
    ON test_case_embedding (test_case_id, kind, model) WHERE source_text IS NULL;

-- 워커가 매 tick 도는 큐 조회: "대기 중이고 시도 상한을 넘지 않은 행".
CREATE INDEX IF NOT EXISTS idx_test_case_embedding_queue
    ON test_case_embedding (model, kind, attempts, id) WHERE source_text IS NULL;

-- HNSW 인덱스는 일부러 만들지 않는다(V18과 같은 판단). 다만 실서비스에서 프로젝트당 케이스가
-- 몇만 건까지 커질 수 있어(ARTEL-206), project 필터 뒤에도 순차 스캔이 느려지는 임계에 닿으면
-- 그때 CREATE INDEX CONCURRENTLY로 온라인 생성한다. 차원 1024라 HNSW 2000차원 상한에 안 걸린다.
