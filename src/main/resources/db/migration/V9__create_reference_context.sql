-- V7__create_reference_context.sql
-- 참고자료(기획서 등)에서 Agent가 추출한 "게임 이해 요약본(reference_context)".
--
-- 한 문서(project_document)로부터 game_context 8개 타입(overview/screens/mechanics/entities/
-- progression/flows/glossary/misc)으로 분해하여 타입당 1행으로 저장한다. Agent는 타입별로 조회한다.
-- source_document_id 로 원본 문서(project_document → object_key = S3)를 추적한다(파일명 저장 없이).
--
-- (project_id, source_document_id, type)은 유일 → 같은 문서 재추출 시 그 문서의 타입 행만 교체(멱등).
-- 다른 문서는 별개 행으로 공존한다(문서별 INSERT, 기존 데이터 증분/병합 아님).
-- project/project_document 는 논리 참조(FK 없음) — 타 도메인 확정 시 FK 추가(test_scenario 선례 동일).

CREATE TABLE IF NOT EXISTS reference_context (
    id                 BIGSERIAL PRIMARY KEY,
    project_id         BIGINT NOT NULL,
    source_document_id BIGINT NOT NULL,
    type               VARCHAR(20) NOT NULL,
    content            JSONB NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_reference_context_document_type UNIQUE (project_id, source_document_id, type)
);

CREATE INDEX IF NOT EXISTS idx_reference_context_project_type
    ON reference_context (project_id, type);
