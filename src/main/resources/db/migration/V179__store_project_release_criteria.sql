--------------------------------------------------------------------------------
-- 프로젝트마다 출시 기준의 숫자를 둔다 (ARTEL-1386)
--
-- 대시보드의 출시 판단은 기준 다섯으로 정한다: 판정 전 TC · 회귀 · 통과율 · 안 닫힌 BLOCKER · CRITICAL 이슈 ·
-- 판정 못함 비율. 항목은 고정이고 숫자만 프로젝트가 정한다. 행이 없으면 서버의 기본값을 쓴다.
--------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS project_release_criteria (
    project_id            BIGINT PRIMARY KEY REFERENCES project (id) ON DELETE CASCADE,
    max_pending           INTEGER     NOT NULL CHECK (max_pending >= 0),
    max_regression        INTEGER     NOT NULL CHECK (max_regression >= 0),
    min_pass_rate         INTEGER     NOT NULL CHECK (min_pass_rate BETWEEN 0 AND 100),
    max_open_blockers     INTEGER     NOT NULL CHECK (max_open_blockers >= 0),
    max_inconclusive_rate INTEGER     NOT NULL CHECK (max_inconclusive_rate BETWEEN 0 AND 100),
    updated_by            BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
