-- macro 에 검색용 벡터 자리를 만든다. (ARTEL-986)
--
-- test_case_embedding(V23)과 같은 구조, 같은 대기 행 / 완성 행 큐 모델이고 owner 가 macro 다.
-- MACRO_SEARCH 가 질의 문장을 임베딩해 이 벡터와 코사인 거리로 비교한다.
--
-- test_case_embedding 과 다른 점:
--   - macro 하나에 벡터 하나(이름 + parameter + docstring 을 합친 문장)뿐이다. 공용
--     EmbeddingQueueRepository 가 kind 컬럼을 쓰므로 컬럼은 두되, CHECK 로 'CONTENT' 하나만 허용한다.
--   - macro 본문이 바뀌면 벡터가 낡는다. source_text 에 임베딩한 문장을 그대로 저장해 두고, 시딩이
--     "지금 macro 가 만드는 문장" 과 비교해 다르면 그 행을 지우고 대기 행으로 되돌린다
--     (MacroEmbeddingRepository.seedPending). macro.updated_at 으로 판정하지 않는 이유는 docstring 을
--     SQL 로 직접 고치는 운영 스크립트가 updated_at 을 건드리지 않기 때문이다.
--   - source(Python 원문)는 임베딩하지 않는다. 이름, parameter 이름, docstring 만 쓴다.

CREATE TABLE IF NOT EXISTS macro_embedding (
    id            BIGSERIAL PRIMARY KEY,
    -- macro 가 지워지면 벡터도 함께 지워져야 한다. 남으면 검색이 없는 macro 를 가리킨다.
    macro_id      BIGINT NOT NULL REFERENCES macro (id) ON DELETE CASCADE,
    kind          VARCHAR(20) NOT NULL CHECK (kind = 'CONTENT'),
    -- 어느 모델이 만든 벡터인지. 모델 교체 판단과 재색인의 기준이라 NOT NULL 이다.
    model         VARCHAR(100) NOT NULL,
    -- 임베딩의 입력이 된 문자열 그대로. 시딩이 현재 문장과 비교해 낡은 벡터를 가려낸다.
    source_text   TEXT,
    embedding     vector(1024),
    attempts      INT NOT NULL DEFAULT 0,
    last_error    TEXT,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- 두 상태만 존재한다(중간 상태 없음). V18, V23 과 같은 큐 모델.
    --   대기 행: source_text IS NULL AND embedding IS NULL  -> 백필 큐의 원소.
    --   완성 행: 둘 다 NOT NULL                              -> 검색이 쓰는 실제 벡터.
    CONSTRAINT ck_macro_embedding_state CHECK (
        (source_text IS NULL AND embedding IS NULL)
            OR (source_text IS NOT NULL AND embedding IS NOT NULL)
    )
);

-- (macro_id, kind, model) 조회: 시딩의 "이미 처리했나" 판정과 검색의 모델별 조회가 탄다.
CREATE INDEX IF NOT EXISTS idx_macro_embedding_lookup
    ON macro_embedding (macro_id, kind, model);

-- 대기 행은 macro, kind, model 당 정확히 하나. 시딩 INSERT 의 ON CONFLICT DO NOTHING 이 이 인덱스에 기댄다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_macro_embedding_pending
    ON macro_embedding (macro_id, kind, model) WHERE source_text IS NULL;

-- 워커가 매 tick 도는 큐 조회: "대기 중이고 시도 상한을 넘지 않은 행".
CREATE INDEX IF NOT EXISTS idx_macro_embedding_queue
    ON macro_embedding (model, kind, attempts, id) WHERE source_text IS NULL;

-- HNSW, IVFFlat 은 일부러 만들지 않는다(V18, V23 과 같은 판단). 검색은 content_map 하나의 macro 만
-- 읽고 그 수는 많아야 수십~수백 개라 순차 스캔이 충분하다.
