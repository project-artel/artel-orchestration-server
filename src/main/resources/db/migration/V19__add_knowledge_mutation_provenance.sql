-- knowledge 개별 수정·소프트삭제의 출처를 남긴다. (ARTEL-188)
--
-- 지우고 고치는 주체가 Agent라, 어느 QA 런이 어떤 항목을 건드렸는지 남기지 않으면 지식창고가
-- 잘못 깎여 나가도 알아챌 방법이 없다. 삭제 표식(`deleted_at`)만으로는 "언제"만 알 수 있다.
--
-- 이력 테이블로 빼지 않는 이유는 V18과 같다: 되살리기가 `deleted_at`을 NULL로 되돌리는 것만으로
-- 되어야 하고, 그러려면 상태가 행 하나에 모여 있어야 한다. 되살린 뒤에도 이 컬럼은 그대로 남아
-- "직전에 누가 지웠었나"의 기록으로 쓰인다.
--
-- qa_try FK를 걸지 않는 것은 knowledge.source_id와 같은 판단이다(V13). knowledge는 프로젝트·문서·런을
-- 전부 논리참조로 들고 있고, 여기에만 하드 FK를 걸면 QA 런 정리가 지식창고에 막힌다.

ALTER TABLE knowledge ADD COLUMN IF NOT EXISTS updated_by_qa_try_id BIGINT;
ALTER TABLE knowledge ADD COLUMN IF NOT EXISTS deleted_by_qa_try_id BIGINT;

COMMENT ON COLUMN knowledge.updated_by_qa_try_id IS '마지막으로 이 항목을 수정한 QA 런(qa_try.id). 사람/문서 경로 수정이면 NULL.';
COMMENT ON COLUMN knowledge.deleted_by_qa_try_id IS '이 항목을 소프트삭제한 QA 런(qa_try.id). 되살려도 지우지 않는다.';
