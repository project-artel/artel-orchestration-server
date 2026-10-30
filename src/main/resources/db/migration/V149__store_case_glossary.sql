-- 자연어 TC 의 용어집과 새 게임 시작 상태. 프로젝트마다 하나.
-- TC 계약(필요 · 남김)은 test_case.condition 과 metadata.source 에 둔다 — 지도 조건과 같은 읽기 경로를 탄다.
CREATE TABLE case_glossary (
    project_id  BIGINT PRIMARY KEY REFERENCES project(id) ON DELETE CASCADE,
    terms       JSONB NOT NULL DEFAULT '[]'::jsonb,
    start_state JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
