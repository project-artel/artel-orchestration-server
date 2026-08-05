-- knowledge의 버전 이력과 검색 사용 로그를 남기고, 둘을 항목별 사실로 접는 view를 만든다.
-- (2026-08-05, ARTEL-255)
--
-- 목적은 V25의 짝이다. V25는 QA 런을 **무엇으로** 돌렸는지(model / prompt_version / agent_arch /
-- reasoning_effort)를 남겼고, 여기는 그 축에 붙일 **결과**를 남긴다. 지금은 "이 런이 만든 지식이
-- 쓸모 있었나"를 잴 근거가 어디에도 없다 — knowledge 행은 마지막 수정자만 들고 있어(V19) 이력이
-- 남지 않고, 검색으로 무엇이 나갔는지는 기록되지 않는다.
--
-- 착상은 **후속 런이 공짜 심판**이라는 것이다. 어떤 런이 만든 지식을 나중 런이 지우면 그것이 부정
-- 신호다. 심판 LLM도 정답지도 필요 없고 운영 트래픽만으로 지표가 나온다. 대신 **로그는 소급이
-- 안 된다** — 지금 켜지 않으면 그 사이 실행된 런의 이력은 영구히 없다. 그래서 소비처(대시보드,
-- 판정 저장, 실험 엔티티)보다 기록이 먼저다.
--
-- 세 덩어리를 한 파일에 둔다. view가 두 테이블 모두에 기대므로 쪼개면 순서 의존만 생긴다.
--
-- ⚠️ V13/V15/V18/V19는 이미 적용된 마이그레이션이라 수정 금지(체크섬). 전부 새 버전으로 더한다.

--------------------------------------------------------------------------------
-- 1. knowledge에 버전 표식
--------------------------------------------------------------------------------
-- `version`은 **content가 바뀔 때만** 오른다(CREATE/UPDATE). DELETE/RESTORE는 올리지 않는다.
-- 소프트삭제는 본문을 건드리지 않고, 되살리기가 `deleted_at = NULL`만으로 되어야 한다는 V18의
-- 결정을 그대로 지킨다 — 삭제가 version을 올리면 복원이 "어느 버전으로 되돌릴까"를 묻게 된다.
--
-- 여기서 content는 `(tag, summary, description)` 셋 전부다. 아래 knowledge_event.after가 셋을
-- 통째로 스냅샷하므로, tag만 바뀐 변경에 version을 안 올리면 버전 N의 after가 이 행과 어긋난다.
-- (임베딩 무효화 판정은 이와 별개로 summary/description만 본다 — 임베딩 입력이 그 둘뿐이라
-- tag 변경으로 벡터를 다시 청구할 이유가 없다. 두 판정이 다른 것이 의도다.)
--
-- DEFAULT 1은 기존 행을 위한 것이다. 이 마이그레이션 이전의 행은 이벤트 이력이 없고, 백필하지
-- 않는다 — 없는 이력을 지어내는 것보다 "그 이전"으로 비는 편이 낫다. 아래 view가 그 행들을
-- 버전 1로 합성해 담되 만든 런은 NULL로 둔다.
--
-- `replaces_id`는 이번에 쓰지 않는다. 컬럼만 만들어 둔다 — V18이 ARTEL-188의 deleted_at을 미리
-- 만들어 둔 것과 같은 판단이다. 채우는 것은 후속 작업이다.
ALTER TABLE knowledge
    ADD COLUMN IF NOT EXISTS version     INT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS replaces_id BIGINT;

COMMENT ON COLUMN knowledge.version IS
    '현재 content의 버전. CREATE/UPDATE에서만 오르고 DELETE/RESTORE는 올리지 않는다. knowledge_event의 최대 content 버전과 같아야 한다.';
COMMENT ON COLUMN knowledge.replaces_id IS
    '이 항목이 대체한 항목(knowledge.id). ARTEL-255 범위에서는 채우지 않는다 — 자리만 만들어 둔 컬럼이다.';

--------------------------------------------------------------------------------
-- 2. knowledge_event — 버전 이력
--------------------------------------------------------------------------------
-- FK를 걸지 않는다. V13(knowledge.source_id)과 V19(updated_by_qa_try_id)의 논리참조 관례 그대로다 —
-- 여기에만 하드 FK를 걸면 QA 런 정리가 지식창고에 막힌다. 이력은 원본이 사라져도 남아야 한다.
--
-- **`after`만 저장하고 `before`는 저장하지 않는다.** 최신 content가 knowledge 행과 한 번
-- 중복되지만, 그 대가로 **모든 버전이 (knowledge_id, version) 단건 조회로 통일된다.**
-- before 방식은 "최신은 행에서, 나머지는 다음 이벤트에서"로 조회가 갈리고, 최신을 읽는 사이
-- mutation이 끼어들면 엉뚱한 버전을 조용히 읽는 경합이 생긴다. 이벤트 행은 불변이라 그 경합이
-- 없다. 덤으로 `knowledge.version = max(event.version)` 불변식이 생겨 쓰기 버그가 검출된다.
--
-- `after`는 content 이벤트(CREATE/UPDATE)에만 있다. DELETE/RESTORE는 본문을 바꾸지 않으므로
-- NULL이고, 그것이 곧 "이 이벤트는 버전을 만들지 않았다"는 표식이다.
--
-- `RESTORE`는 CHECK에 넣되 **이번 범위에서 쓰는 곳이 없다.** KnowledgeService에 복원 경로 자체가
-- 없고(생성/수정/소프트삭제뿐) 복원 기능 추가는 범위 밖이다. 나중에 붙을 때 CHECK를 갈지 않도록
-- 값만 열어 둔다.
CREATE TABLE IF NOT EXISTS knowledge_event (
    id           BIGSERIAL PRIMARY KEY,
    knowledge_id BIGINT NOT NULL,
    -- 이벤트만 읽고도 프로젝트 스코프를 걸 수 있어야 한다. knowledge를 조인해 얻을 수 있지만,
    -- 원본이 지워진 뒤에도 이력은 남으므로 그때 스코프를 잃지 않으려면 여기 복사해 둬야 한다.
    project_id   BIGINT NOT NULL,
    -- 이 변경을 일으킨 QA 런. 사람/문서 경로는 NULL이다. 지표의 귀속이 전부 이 컬럼에서 나온다.
    qa_try_id    BIGINT,
    event        VARCHAR(20) NOT NULL
                 CHECK (event IN ('CREATE', 'UPDATE', 'DELETE', 'RESTORE')),
    version      INT NOT NULL,
    -- content 스냅샷 {tag, summary, description}. DELETE/RESTORE는 NULL.
    after        JSONB,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- **부분 유니크인 이유.** DELETE/RESTORE는 version을 올리지 않으므로 (knowledge_id, version)이
-- 테이블 전체에서는 유일하지 않다. 유일해야 하는 것은 content 이벤트뿐이다.
--
-- 이 인덱스의 목적은 성능이 아니라 **재시도나 동시 쓰기로 같은 버전이 두 번 기록되는 것을 막는
-- 것**이다. 항목당 이벤트는 몇 개뿐이라 조회 성능만 보면 knowledge_id 단독 인덱스로 충분하다.
-- 위반은 저장 트랜잭션을 통째로 되돌린다 — 행과 이벤트가 어긋난 채 굳지 않는다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_event_version
    ON knowledge_event (knowledge_id, version) WHERE after IS NOT NULL;

-- "이 런이 지식창고에 무엇을 했나"의 조회 축. 축별 집계가 이 방향으로 판다.
CREATE INDEX IF NOT EXISTS idx_knowledge_event_try ON knowledge_event (qa_try_id);

--------------------------------------------------------------------------------
-- 3. knowledge_usage — 검색 사용 로그
--------------------------------------------------------------------------------
-- 검색을 Orchestration이 직접 수행하고 qa_try_id도 이미 알고 있으므로(QaAgentInboundRouter),
-- 기록에 Agent를 건드릴 필요가 없다. 검색이 돌려주는 히트 전부를 rank와 score와 함께 남긴다.
--
-- 부피는 런당 (검색 횟수 상한 × 결과 상한)으로 유계라 지금은 정리 잡이 필요 없다.
CREATE TABLE IF NOT EXISTS knowledge_usage (
    id                BIGSERIAL PRIMARY KEY,
    qa_try_id         BIGINT NOT NULL,
    knowledge_id      BIGINT NOT NULL,
    -- 검색이 내보낸 시점의 버전. 나중에 그 항목이 고쳐져도 "그때 이 런이 읽은 것"은 이 값이다.
    knowledge_version INT NOT NULL,
    -- 런의 몇 번째 스텝이었나. **이번 범위에서는 항상 NULL** — KnowledgeSearchPayload가 step을
    -- 싣지 않는다. 후속에서 Agent가 실으면 채워진다. 지금 만드는 이유는 replaces_id와 같다.
    step              INT,
    -- 이 검색 안에서의 순위(1부터)와 코사인 유사도.
    rank              INT,
    score             REAL,
    -- **nullable이고 기본값이 없다. 뜻이 셋이다.**
    --   NULL  = 이 런은 인용을 보고할 수단이 없었다
    --   false = 보고 가능했는데 인용하지 않았다
    --   true  = 인용했다
    -- NOT NULL DEFAULT false로 두면 인용 기능이 붙기 전 런 전부가 "아무도 안 쓴 지식"으로 보이고,
    -- 그 오류는 조용히 지나간다. qa_try의 축 컬럼이 nullable인 것과 같은 이유다(V25).
    -- 이번 범위에서 이 컬럼은 항상 NULL이다. 채우는 것은 후속 작업이다.
    cited             BOOLEAN,
    retrieved_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_knowledge_usage_knowledge ON knowledge_usage (knowledge_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_usage_try ON knowledge_usage (qa_try_id);

--------------------------------------------------------------------------------
-- 4. knowledge_entry_facts — (knowledge_id, version)당 한 줄
--------------------------------------------------------------------------------
-- **롤업은 이 view에 넣지 않는다.** 기간 필터가 파라미터인데 view는 파라미터를 못 받고, 축을
-- view에 접어 넣으면 축을 추가할 때 view를 갈아야 한다. view는 파라미터 없는 "항목별 사실"까지만
-- 맡고, 축과 기간은 KnowledgeStatsRepository의 질의가 진다.
--
-- **컬럼 순서를 고정한다.** Flyway에서 view 변경은 CREATE OR REPLACE인데 컬럼 타입·순서가 바뀌면
-- 거부된다. 나중에 컬럼을 더할 것을 감안해 순서를 안정적으로 잡았고, **추가는 끝에만 붙인다.**
--
-- **"수리(repaired) vs 폐기(repudiated)" 구분 컬럼은 아직 만들지 않는다.** 그것은 replaces_id가
-- 채워져야 계산되고, 지금 넣으면 전부 false가 나와 "수리가 한 번도 없었다"로 읽힌다. cited를
-- DEFAULT false로 두면 안 되는 것과 같은 오류다. 후속 작업에서 추가한다.
CREATE OR REPLACE VIEW knowledge_entry_facts AS
WITH content_event AS (
    -- 버전 목록의 원천. after가 있는 이벤트만이 버전을 만든다.
    SELECT e.knowledge_id,
           e.version,
           e.qa_try_id,
           e.created_at
      FROM knowledge_event e
     WHERE e.after IS NOT NULL
),
entry_version AS (
    SELECT ce.knowledge_id,
           ce.version,
           ce.qa_try_id AS created_by_qa_try_id,
           ce.created_at
      FROM content_event ce
    UNION ALL
    -- 이 마이그레이션 이전에 만들어진 행에는 이벤트가 없다. 백필하지 않는 대신 버전 1을 여기서
    -- 합성한다. created_by_qa_try_id는 NULL — 만든 런을 **모른다**는 뜻이고, 지어내지 않는다.
    -- created_at은 knowledge 행의 실제 값이다.
    --
    -- 합성하지 않고 그냥 빼면 그 항목들에 대한 knowledge_usage가 어디에도 집계되지 않아
    -- "검색된 적 없는 지식"으로 보인다. 축별 롤업은 qa_try INNER JOIN이라 이 합성 행이 자동으로
    -- 빠지므로, 축을 모르는 행이 축 통계를 오염시키지도 않는다.
    SELECT k.id,
           1,
           NULL::BIGINT,
           k.created_at
      FROM knowledge k
     WHERE NOT EXISTS (
               SELECT 1
                 FROM content_event ce
                WHERE ce.knowledge_id = k.id
                  AND ce.version = 1
           )
),
usage_rollup AS (
    SELECT u.knowledge_id,
           u.knowledge_version,
           COUNT(*)                                  AS retrieval_count,
           COUNT(*) FILTER (WHERE u.cited)           AS citation_count,
           COUNT(*) FILTER (WHERE u.cited IS NOT NULL) AS citation_known_count
      FROM knowledge_usage u
     GROUP BY u.knowledge_id, u.knowledge_version
)
SELECT k.project_id,
       ev.knowledge_id,
       ev.version,
       (ev.version = k.version)                      AS is_current,
       ev.created_by_qa_try_id,
       ev.created_at,
       -- 삭제는 **현재 버전 줄에만, 그리고 실제로 삭제 상태일 때만** 실린다.
       --   모든 버전에 달면 삭제 한 번이 버전 수만큼 세어진다.
       --   deleted_at을 안 보고 deleted_by만 실으면, V19가 감사용으로 남겨 둔 값 때문에
       --   되살아난 항목이 계속 "지워진 것"으로 읽힌다.
       CASE WHEN ev.version = k.version THEN k.deleted_at END AS deleted_at,
       CASE
           WHEN ev.version = k.version AND k.deleted_at IS NOT NULL
               THEN k.deleted_by_qa_try_id
       END                                           AS deleted_by_qa_try_id,
       -- 검색된 적 없는 버전도 줄은 나와야 한다(0과 "행 없음"은 다르다).
       COALESCE(ur.retrieval_count, 0)               AS retrieval_count,
       COALESCE(ur.citation_count, 0)                AS citation_count,
       COALESCE(ur.citation_known_count, 0)          AS citation_known_count
  FROM entry_version ev
  JOIN knowledge k ON k.id = ev.knowledge_id
  LEFT JOIN usage_rollup ur
         ON ur.knowledge_id = ev.knowledge_id
        AND ur.knowledge_version = ev.version;

COMMENT ON VIEW knowledge_entry_facts IS
    'knowledge 항목의 (id, version)당 한 줄. 파라미터 없는 사실만 담고 기간·축 롤업은 KnowledgeStatsRepository가 한다.';
