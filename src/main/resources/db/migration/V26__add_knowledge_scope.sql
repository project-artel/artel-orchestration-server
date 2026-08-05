-- 지식창고를 QA 런 단위 스코프로 격리한다. (2026-08-05, ARTEL-256)
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
