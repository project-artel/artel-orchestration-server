-- TestScenario 단일 구조를 3계층(TestCase → TestScenario → TestRun)으로 확장한다. (2026-07-29)
--
-- - TestCase: 기능 1개 검증(재사용 라이브러리). Agent 산출은 대분류/제목/사전조건/기대효과, 전부 자연어.
-- - TestScenario(기존 test_scenario 유지): 케이스를 의미 순서로 엮은 여정.
-- - TestRun: 여러 시나리오를 묶은 실행 세트. (TestSuite 개념은 안 씀; qa_try는 무관하게 유지)
--
-- 관계는 junction 테이블로 둔다(역방향 질의 "케이스 X 쓰는 시나리오 다 찾기" + 무결성/순서 제약).
-- ⚠️ 기존 test_scenario.payload·챗봇·QA·FE는 이 마이그레이션에서 건드리지 않는다(무손상). 소비자 연결은 후속.
-- FK는 두지 않는다(레포 관례: 프로젝트/시나리오/케이스는 논리 참조). 제약은 약하게.

CREATE TABLE IF NOT EXISTS test_case (
    id                     BIGSERIAL PRIMARY KEY,
    project_id             BIGINT NOT NULL,
    category               VARCHAR(50)  NOT NULL,       -- 대분류
    title                  VARCHAR(255) NOT NULL,       -- 제목
    precondition           TEXT,                        -- 사전조건(자연어, nullable)
    expected               TEXT NOT NULL,               -- 기대효과(자연어)
    verification_status    VARCHAR(20)  NOT NULL DEFAULT 'DRAFT'
        CHECK (verification_status IN ('DRAFT', 'VERIFIED', 'BROKEN')),
    last_verified_build_id BIGINT,                      -- nullable
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_test_case_project ON test_case (project_id);

-- 시나리오 ↔ 케이스 (순서형 조합). position = 시나리오 내 의미적 순서.
CREATE TABLE IF NOT EXISTS test_scenario_case (
    id               BIGSERIAL PRIMARY KEY,
    test_scenario_id BIGINT NOT NULL,
    test_case_id     BIGINT NOT NULL,
    position         INT NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_test_scenario_case_position UNIQUE (test_scenario_id, position)
);
CREATE INDEX IF NOT EXISTS idx_test_scenario_case_scenario ON test_scenario_case (test_scenario_id, position);
-- 역방향 질의(케이스 X를 쓰는 시나리오)를 위한 인덱스.
CREATE INDEX IF NOT EXISTS idx_test_scenario_case_case ON test_scenario_case (test_case_id);

-- 시나리오 묶음(실행 세트 정의).
CREATE TABLE IF NOT EXISTS test_run (
    id          BIGSERIAL PRIMARY KEY,
    project_id  BIGINT NOT NULL,
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_test_run_project ON test_run (project_id);

-- 런 ↔ 시나리오 (순서형).
CREATE TABLE IF NOT EXISTS test_run_scenario (
    id               BIGSERIAL PRIMARY KEY,
    test_run_id      BIGINT NOT NULL,
    test_scenario_id BIGINT NOT NULL,
    position         INT NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_test_run_scenario_position UNIQUE (test_run_id, position)
);
CREATE INDEX IF NOT EXISTS idx_test_run_scenario_run ON test_run_scenario (test_run_id, position);
CREATE INDEX IF NOT EXISTS idx_test_run_scenario_scenario ON test_run_scenario (test_scenario_id);
