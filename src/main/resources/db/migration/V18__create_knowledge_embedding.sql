-- knowledge에 벡터 색인 자리와 소프트삭제 표식을 만든다. (ARTEL-185, ARTEL-188)
--
-- V13 주석이 "하이브리드 검색(벡터/BM25)은 Phase 2"로 비워 둔 자리가 여기다. 이 마이그레이션은
-- 벡터를 담을 곳까지만 만든다. 벡터 생성은 Agent 서버(ARTEL-184), 검색 질의는 후속 이슈가 맡는다.

CREATE EXTENSION IF NOT EXISTS vector;

--------------------------------------------------------------------------------
-- 1. 소프트삭제 표식 (ARTEL-188이 쓴다)
--------------------------------------------------------------------------------
-- 삭제 API 자체는 ARTEL-188 범위지만 컬럼은 여기서 먼저 만든다. 나중에 붙이면 그 사이에 머지되는
-- 검색 이슈가 삭제된 항목을 계속 돌려주게 되고, 그 결함은 조용히 지나간다. 스키마를 먼저 세운다.
-- 되살리기는 이 값을 NULL로 되돌리는 것만으로 되어야 하므로 이력을 별도 테이블로 빼지 않는다.

ALTER TABLE knowledge ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

-- 읽기 경로는 전부 살아있는 행만 본다. 부분 인덱스라 삭제된 행은 색인에 들어가지도 않는다.
CREATE INDEX IF NOT EXISTS idx_knowledge_project_alive
    ON knowledge (project_id) WHERE deleted_at IS NULL;

--------------------------------------------------------------------------------
-- 2. 벡터 테이블
--------------------------------------------------------------------------------
-- 벡터를 knowledge의 컬럼으로 붙이지 않고 별도 테이블로 뺀 이유:
--   - 항목당 검색쿼리를 3개 만든다(ARTEL-184). 컬럼 하나로는 표현할 수 없다.
--   - CONTENT 벡터를 나중에 추가할 때 마이그레이션이 아니라 행 추가가 된다.
--     되돌리기도 DELETE WHERE kind='CONTENT'다.
--   - 모델 교체 시 새 model 값으로 행을 채워 두고 다 차면 전환할 수 있다. 컬럼이면 무중단이 안 된다.
--
-- 차원이 1024인 이유 (ARTEL-184 실측)
-- ----------------------------------
-- 모델은 openai/text-embedding-3-large. 원본 차원은 3072지만 Matryoshka 표현이라 잘라 쓸 수 있고,
-- 1024로 잘라도 한국어 평가셋에서 성능이 원본과 같았다(34/34, MRR 1.0).
--
-- 자른 이유는 이쪽 사정이다: **pgvector의 HNSW/IVFFlat 인덱스는 2000차원이 상한이다.**
-- 3072짜리를 그대로 썼다면 지금은 인덱스를 안 만든다 해도 나중에 붙이려는 순간 halfvec 우회를
-- 떠안게 된다. 1024는 그 문을 열어 둔 채로 성능을 잃지 않는 선택이다.
--
-- kind는 QUERY | CONTENT. 이번 스프린트는 QUERY만 채우고 CONTENT는 자리만 남긴다.

CREATE TABLE IF NOT EXISTS knowledge_embedding (
    id           BIGSERIAL PRIMARY KEY,
    -- knowledge가 지워지면 벡터도 함께 지워져야 한다. 남으면 검색이 없는 항목을 가리킨다.
    knowledge_id BIGINT NOT NULL REFERENCES knowledge (id) ON DELETE CASCADE,
    kind         VARCHAR(20) NOT NULL CHECK (kind IN ('QUERY', 'CONTENT')),
    -- 어느 모델이 만든 벡터인지. 모델 교체 판단과 재색인의 기준이라 NOT NULL이다.
    model        VARCHAR(100) NOT NULL,
    -- 임베딩의 입력이 된 문자열(QUERY면 생성된 검색쿼리 한 건).
    source_text  TEXT,
    embedding    vector(1024),
    -- 백필 시도 횟수와 마지막 실패 사유. 상한을 넘긴 항목을 조회할 수 있어야 하므로 컬럼으로 둔다.
    attempts     INT NOT NULL DEFAULT 0,
    last_error   TEXT,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- 이 테이블의 행은 두 상태 중 하나다. 중간 상태(텍스트는 있는데 벡터가 없는 등)는 없다.
    --   대기 행: source_text IS NULL AND embedding IS NULL  → 백필 큐의 원소. attempts/last_error를 진다.
    --   완성 행: 둘 다 NOT NULL                              → 검색이 쓰는 실제 벡터.
    -- 백필 워커는 대기 행을 잡아 Agent를 부르고, 성공하면 대기 행을 지우고 완성 행 N개를 넣는다.
    CONSTRAINT ck_knowledge_embedding_state CHECK (
        (source_text IS NULL AND embedding IS NULL)
            OR (source_text IS NOT NULL AND embedding IS NOT NULL)
    )
);

-- (knowledge_id, kind, model) 조회가 잦다. 백필 워커의 "이미 처리했나" 판정과
-- 후속 검색 이슈의 모델별 조회가 모두 이 축을 탄다.
CREATE INDEX IF NOT EXISTS idx_knowledge_embedding_lookup
    ON knowledge_embedding (knowledge_id, kind, model);

-- 대기 행은 knowledge·kind·model당 정확히 하나여야 한다. 이 인덱스가 없으면 인스턴스 두 대가
-- 같은 knowledge에 대기 행을 각각 넣어 임베딩을 두 번 청구한다. 워커의 시딩 INSERT는
-- ON CONFLICT DO NOTHING으로 이 인덱스에 기댄다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_embedding_pending
    ON knowledge_embedding (knowledge_id, kind, model) WHERE source_text IS NULL;

-- 워커가 매 tick 도는 큐 조회: "대기 중이고 시도 상한을 넘지 않은 행".
CREATE INDEX IF NOT EXISTS idx_knowledge_embedding_queue
    ON knowledge_embedding (model, kind, attempts, id) WHERE source_text IS NULL;

-- HNSW 인덱스는 일부러 만들지 않는다. 프로젝트당 knowledge가 몇백~몇천 행 규모라 순차 스캔이
-- 밀리초이고, project_id 필터가 먼저 걸려 후보가 더 줄어든다. 지금 붙이면 튜닝 파라미터(m,
-- ef_construction)만 늘고 얻는 것이 없다. 느려지면 그때 CONCURRENTLY로 온라인 생성한다.
-- (차원을 1024로 맞춰 뒀으므로 그때 2000차원 상한에 걸리지 않는다.)
