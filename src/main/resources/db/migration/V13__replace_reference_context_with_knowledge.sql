-- reference_context를 통합 지식창고 knowledge로 갈아엎는다. (팀 결정 2026-07-26)
--
-- 문서/QA를 별도 테이블로 나누지 않는다(입력부가 전부 Agent). 신뢰도(문서 사실 vs Agent 추론)
-- 구분은 스키마 분리 대신 source/tag로 코드 레이어에서 다룬다.
--   source: DOCS(업로드 문서 추출) | QA(QA 실행 중 Agent 관측/추론)
--   source_id: DOCS→project_document.id, QA→qa_try.id (nullable — 있을 때만)
--   content_hash: DOCS 파일 hash (nullable, 멱등키 아님 — 저장만; 파일 중복은 업로드 레이어에서)
--   tag: 정보 성질의 1차 이정표. Phase 1 최소 집합(CONTROL/INFO/MISC), 이후 확장.
-- 강한 유니크 제약은 두지 않는다(DB 제약 약하게). 하이브리드 검색(벡터/BM25)은 Phase 2.
--
-- ⚠️ V10(reference_context)은 이미 적용된 마이그레이션이라 수정 금지(체크섬). 새 버전으로 교체한다.

DROP TABLE IF EXISTS reference_context;

CREATE TABLE IF NOT EXISTS knowledge (
    id           BIGSERIAL PRIMARY KEY,
    project_id   BIGINT NOT NULL,
    source       VARCHAR(20) NOT NULL CHECK (source IN ('DOCS', 'QA')),
    source_id    BIGINT,
    content_hash VARCHAR(64),
    tag          VARCHAR(20) NOT NULL CHECK (tag IN ('CONTROL', 'INFO', 'MISC')),
    summary      TEXT NOT NULL,
    description  TEXT NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_knowledge_project_tag ON knowledge (project_id, tag);
CREATE INDEX IF NOT EXISTS idx_knowledge_project_source ON knowledge (project_id, source);
