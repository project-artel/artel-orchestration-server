-- 지식창고를 QA 런 단위 스코프로 격리한다. (2026-08-05, ARTEL-256)
--
-- 번호 이야기가 길다. develop에서 ARTEL-245(issue resolution)와 ARTEL-255(knowledge 이력)가 V26을
-- **둘 다** 가져가, Flyway가 "Found more than one migration with version 26"으로 부팅을 거부하는
-- 상태였다. 나중에 머지된 ARTEL-255 쪽을 V27로 밀고(먼저 머지된 V26__add_issue_resolution은 이미
-- 적용된 환경이 있을 수 있어 건드리지 않는다) 이 마이그레이션이 V28을 쓴다.
--
-- 순서가 중요하다: 아래 3절이 ARTEL-255의 knowledge_entry_facts view를 CREATE OR REPLACE 하므로
-- 그 view를 만드는 V27 뒤에 와야 한다.
--
-- V25가 ARTEL-233에 V24를 내준 것과 같은 종류의 사고다.
--
-- V25가 비교 축(model / prompt_version / agent_arch / reasoning_effort)을 qa_try에 남겼지만, 그것만으로는
-- 비교가 성립하지 않는다. 지식창고가 런 사이를 넘나들기 때문이다: 설정 A로 돈 런이 지식을 쌓고
-- 설정 B로 돈 런이 그것을 읽으므로 **나중에 돈 쪽이 앞선 런들의 지식을 물려받아 유리해진다.**
-- 이 순서 효과가 있는 한 축별 점수 차이가 설정 때문인지 실행 순서 때문인지 갈리지 않는다.
-- 동시에, 실험 런이 쓴 지식이 운영 지식창고에 섞이면 되돌릴 방법이 없다.
--
-- 스냅샷 복사를 하지 않는 이유
-- ---------------------------
-- 런 시작 시 baseline을 통째로 복사하면 knowledge_embedding까지 복사해야 하는데, 벡터 생성은
-- 비동기 백필이라(V18) 복사한 직후에는 검색에 쓸 수가 없다. arm 수만큼 지식창고도 불어난다.
-- 읽기에서 baseline을 그대로 보고 쓰기만 스코프로 가르는 copy-on-write가 같은 격리를 복사 없이 준다.
--
--   읽기: scope_id IS NULL OR scope_id = :scope   (스코프 런은 baseline + 자기 것을 본다)
--   쓰기: 항상 :scope                              (운영 런이면 NULL이라 이 마이그레이션 전과 같다)
--
-- ⚠️ 재현성의 한계 — 이 설계가 못 막는 것
-- --------------------------------------
-- copy-on-write라 baseline은 실험 중에도 운영 런이 계속 바꾼다. 그 변경이 실험 런에 그대로 보인다.
-- 완전한 재현성은 시점 고정(freeze)이 필요한데 그것은 이 작업 범위가 아니다. 나중에 같은 설정의
-- 두 실험이 다른 점수를 낼 때 **여기가 첫 번째 원인 후보다.**

--------------------------------------------------------------------------------
-- 1. 스코프 컬럼
--------------------------------------------------------------------------------
-- NULL이 운영 공용(baseline)이다. 기존 행은 전부 NULL이 되고 그게 맞다 — 지금까지의 모든 지식은
-- 운영 지식창고의 것이다. 값을 채우는 UPDATE가 없는 것이 이 마이그레이션의 의도다.
ALTER TABLE knowledge ADD COLUMN IF NOT EXISTS scope_id BIGINT;

-- 스코프 행 하나가 baseline 행 하나를 가린다.
--   내용이 있는 그림자(deleted_at IS NULL) = 그 스코프 안에서의 수정
--   deleted_at이 찍힌 그림자                = 그 스코프 안에서의 삭제(툼스톤)
--
-- 그림자가 필요한 이유는 스코프 런이 baseline을 직접 건드리면 안 되기 때문이다. 직접 UPDATE/삭제하면
-- 운영 지식창고가 실험 때문에 깎여나가고, 그 손실은 실험이 끝나도 되돌아오지 않는다.
--
-- FK를 걸지 않는 것은 knowledge.source_id(V13)·updated_by_qa_try_id(V19)와 같은 판단이다. knowledge는
-- 프로젝트·문서·런·이제 다른 knowledge까지 전부 논리참조로 들고 있고, 여기에만 하드 FK를 걸면
-- baseline 정리가 스코프 행에 막힌다.
ALTER TABLE knowledge ADD COLUMN IF NOT EXISTS shadows_id BIGINT;

COMMENT ON COLUMN knowledge.scope_id IS
    '이 항목이 속한 지식 스코프. NULL이면 운영 공용(baseline)이고, 값이 있으면 그 스코프의 런에만 보인다.';
COMMENT ON COLUMN knowledge.shadows_id IS
    '이 스코프 행이 가리는 baseline knowledge.id. 내용이 있으면 그 스코프 안에서의 수정, deleted_at이 찍혔으면 삭제(툼스톤).';

-- 런이 어느 스코프로 도는지. NULL이면 운영 런이라 지금까지와 완전히 같게 동작한다.
-- 세션 개설 시점에 정해지고 런 도중 바뀌지 않는다 — 런 중간에 스코프가 바뀌면 그 런이 무엇을 봤는지
-- 사후에 재구성할 수 없다.
--
-- 실험 엔티티(qa_experiment / qa_experiment_arm)는 아직 없다. 그래서 이 컬럼은 지금 QA 런 생성 API로
-- 채워진다. 후속 실험 기능은 이 컬럼을 채우는 주체가 바뀌는 것일 뿐, 스키마는 그대로다.
ALTER TABLE qa_try ADD COLUMN IF NOT EXISTS knowledge_scope_id BIGINT;

COMMENT ON COLUMN qa_try.knowledge_scope_id IS
    '이 런이 읽고 쓰는 지식 스코프. NULL이면 운영 런(운영 지식창고를 그대로 읽고 쓴다).';

--------------------------------------------------------------------------------
-- 2. 인덱스 — 기존 인덱스가 전부 scope를 모른다
--------------------------------------------------------------------------------
-- 세 인덱스 모두 project_id 바로 뒤에 scope_id를 끼운다. 읽기 술어가 항상
-- `project_id = ? AND (scope_id IS NULL OR scope_id = ?)` 모양이라 이 순서가 그대로 탐색 경로가 된다.
-- Postgres btree는 NULL도 색인하므로 운영 런(scope_id IS NULL)도 같은 인덱스를 쓴다.
--
-- 새로 만들고 옛것을 지우는 순서다. 반대로 하면 그 사이 조회가 순차 스캔으로 떨어진다.

CREATE INDEX IF NOT EXISTS idx_knowledge_project_scope_alive
    ON knowledge (project_id, scope_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_knowledge_project_scope_tag
    ON knowledge (project_id, scope_id, tag);
CREATE INDEX IF NOT EXISTS idx_knowledge_project_scope_source
    ON knowledge (project_id, scope_id, source);

DROP INDEX IF EXISTS idx_knowledge_project_alive;
DROP INDEX IF EXISTS idx_knowledge_project_tag;
DROP INDEX IF EXISTS idx_knowledge_project_source;

-- 그림자 조회용. 읽기 질의는 baseline 행마다 "이 스코프에 나를 가리는 그림자가 있나"를 묻는데,
-- 그 NOT EXISTS가 이 인덱스를 탄다.
--
-- **UNIQUE인 것이 핵심이다.** 한 스코프가 같은 baseline에 그림자를 둘 만들면 읽기 질의가 수정본을
-- 두 번 돌려준다. 서비스도 그림자가 이미 있으면 새로 만들지 않고 그것을 고치지만, 인스턴스 두 대가
-- 같은 프레임을 동시에 처리하면 그 검사는 경합에 진다. V13의 "DB 제약 약하게"에서 벗어나는 자리지만,
-- V18의 uq_knowledge_embedding_pending과 같은 이유다 — 이 유일성이 깨지면 결과가 조용히 틀린다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_knowledge_scope_shadow
    ON knowledge (scope_id, shadows_id) WHERE shadows_id IS NOT NULL;

--------------------------------------------------------------------------------
-- 3. knowledge_entry_facts — 실험 스코프 행을 뺀다
--------------------------------------------------------------------------------
-- V27(ARTEL-255)이 만든 view는 scope를 모른다. 그대로 두면 실험 arm이 만든 지식과 그림자·툼스톤이
-- 운영 지표에 섞이는데, 이 view를 읽는 KnowledgeStatsRepository는 축별 롤업의 유일한 소스다.
--
-- **빼는 것이 맞는 이유.** ARTEL-255의 착상은 "후속 런이 공짜 심판"이다 — 어떤 런이 만든 지식을
-- 나중 런이 지우면 그것이 부정 신호다. 그런데 실험 스코프에는 심판이 될 후속 런이 없다(그 스코프를
-- 쓰는 런은 그 arm뿐이고, 끝나면 아무도 그 지식을 다시 안 본다). 넣으면 심판받지 않은 항목이
-- 분모만 키우고, 스코프 런이 baseline을 가리려고 만든 툼스톤은 "누군가 지운 지식" 한 줄이 되어
-- 폐기율을 실험 횟수만큼 부풀린다. 격리의 목적이 운영 오염 방지인데 지표 레이어에서 그 오염을
-- 허용하면 막은 것이 아니다.
--
-- 실험 arm별 점수는 이 view가 아니라 후속 실험 기능이 `shadows_id`와 `qa_try.knowledge_scope_id`로
-- 따로 낸다. 그때 이 필터를 파라미터로 바꿀지, 스코프용 view를 따로 둘지 정한다.
--
-- CREATE OR REPLACE라 **컬럼 이름·타입·순서를 하나도 바꾸지 않는다**(V27 주석의 제약). 바뀐 것은
-- 맨 끝의 WHERE 한 줄뿐이고, 나머지는 V27 정의를 그대로 옮겨 왔다.
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
        AND ur.knowledge_version = ev.version
 -- ARTEL-256: 실험 스코프 행은 이 view에 담지 않는다. 아래 "왜"를 참조.
 WHERE k.scope_id IS NULL;

COMMENT ON VIEW knowledge_entry_facts IS
    'knowledge 항목의 (id, version)당 한 줄. 운영 스코프(scope_id IS NULL)만 담는다. 기간·축 롤업은 KnowledgeStatsRepository가 한다.';
