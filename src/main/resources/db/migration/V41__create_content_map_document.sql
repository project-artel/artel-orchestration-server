-- ARTEL-441: 근거 문서(artel-affordances.json) 원본의 포인터와 해시.
--
-- 문서 자체는 DB 에 넣지 않는다. 실측이 1,413 KB 이고 상용 게임은 더 크며, 적재하고 나면
-- 조회할 일이 없다. 그런데 버려도 안 된다 — 적재기를 고치면 재적재해야 하고, 그때 게임을
-- 다시 돌릴 수는 없다. 그래서 스토리지에 두고 여기엔 가리키는 값만 남긴다.
--
-- project_document(V14) 가 content_hash 로 중복을 가리는 것과 같은 방식이다.
CREATE TABLE IF NOT EXISTS content_map_document (
    id BIGSERIAL PRIMARY KEY,
    content_map_id BIGINT NOT NULL REFERENCES content_map (id) ON DELETE CASCADE,
    object_key VARCHAR(512) NOT NULL,
    -- 원본 바이트의 SHA-256. 같은 문서를 두 번 올리면 적재를 건너뛴다.
    content_hash VARCHAR(64) NOT NULL,
    byte_size BIGINT NOT NULL,
    -- 어느 버전의 적재기가 이 문서를 처리했나. 적재기를 고치면 낡은 것부터 재적재한다.
    -- NULL 은 아직 적재되지 않았다는 뜻이고, ARTEL-442 가 채운다.
    ingested_by VARCHAR(32),
    ingested_at TIMESTAMP WITH TIME ZONE,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- 같은 지도에 같은 내용이 두 번 등록되지 않는다. 다른 내용이면 새 행이고, 그 이력이
    -- "이 빌드의 근거가 언제 어떻게 달라졌나"가 된다.
    CONSTRAINT uk_content_map_document_hash UNIQUE (content_map_id, content_hash)
);

CREATE INDEX IF NOT EXISTS idx_content_map_document_map_received
    ON content_map_document (content_map_id, received_at DESC);

-- 아직 적재되지 않은 문서. ARTEL-442 가 이 목록을 집어 간다.
CREATE INDEX IF NOT EXISTS idx_content_map_document_pending
    ON content_map_document (received_at) WHERE ingested_at IS NULL;
