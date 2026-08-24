-- 근거 문서 적재의 재시도 장부 (ARTEL-502).
--
-- V41 은 큐를 `ingested_at IS NULL` 하나로만 표현했다. 집어 가는 쪽이 없던 동안에는 그것으로
-- 충분했다 — 깨진 문서가 있어도 아무 일도 일어나지 않았다. 스케줄러가 붙으면서 달라진다:
-- 파싱에서 죽는 문서 하나가 매 tick 마다 스토리지에서 1.4 MB 를 다시 읽고, `received_at ASC`
-- 라 언제나 큐의 앞자리를 차지한다. 영영 성공하지 못하면서 영영 재시도된다.
--
-- 시도 횟수는 실패할 때가 아니라 **집을 때** 오른다(`claimPending`). 프로세스가 파싱 도중
-- 죽어도 시도가 계산되어야, 적재기를 죽이는 문서가 상한에 닿을 수 있다. 실패 시점에만 올리면
-- 장부를 달아 놓고도 그 문서만은 무한 재시도가 남는다.
-- `embedding_pending.attempts` 가 같은 이유로 같은 자리에서 오른다.
ALTER TABLE content_map_document
    ADD COLUMN IF NOT EXISTS ingest_attempts INT NOT NULL DEFAULT 0,
    -- 마지막 실패 사유. 로그는 돌지만 행은 남는다 — 횟수만으로는 왜 실패했는지 알 수 없다.
    -- 적재기가 잘라서 싣는다(파싱 오류는 문서 원문을 인용해 수 KB 가 된다).
    ADD COLUMN IF NOT EXISTS last_error TEXT;

-- idx_content_map_document_pending 은 그대로 둔다.
--
-- 새 조건 `ingest_attempts < :max` 를 선행 칸으로 넣으면 범위 조건이라 뒤따르는
-- `ORDER BY received_at` 의 정렬을 오히려 깨뜨린다. 그리고 걸러 낼 행은 몇 개 되지 않는다 —
-- 상한을 넘긴 문서가 많다는 것은 인덱스로 덮을 문제가 아니라 가서 봐야 할 문제다.
