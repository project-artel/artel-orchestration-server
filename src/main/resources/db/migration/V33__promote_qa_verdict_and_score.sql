-- QA 런의 **판정**을 축별 집계가 읽을 수 있는 자리에 올리고, 나중 채점이 쌓일 자리를 만든다.
-- (2026-08-11, ARTEL-299)
--
-- V32는 미머지 브랜치 ARTEL-291이 이미 잡았다(V32__promote_test_scenario_payload_columns.sql).
-- check-flyway-migrations.sh의 peer 스캔으로 확인하고 번호만 밀었으며, 내용은 그쪽과 무관하다.
--
-- V25는 런을 **무엇으로** 돌렸는지를 남겼고 V27은 그 축에 붙일 지식창고 쪽 결과를 남겼다.
-- 그런데 "그래서 QA를 잘했나"는 아직 못 잰다. 지금 대시보드가 qa_try.status로 세는 것은 런
-- 생명주기이지 품질이 아니다 — COMPLETED는 "끝까지 돌았다"이지 "제대로 했다"가 아니다.
--
-- 판정 데이터 자체는 이미 있다. Agent가 종단 STATUS에 2단 요약을 싣고(build_summary),
-- QaAgentInboundRouter.routeStatus가 그것을 payload 통째로 qa_log에 넣는다. **영속화는 이미
-- 되어 있고 빠진 것은 꺼내 쓰는 길이다.** qa_log는 이 시스템에서 제일 큰 테이블이고(모든
-- LOG/ACTION/ACTION_RESULT가 쌓인다) type 인덱스도 없어서, 여러 런을 축으로 접는 집계가
-- 그 테이블 전체 스캔 + JSONB 경로 필터가 된다.

--------------------------------------------------------------------------------
-- 1. qa_try에 판정 승격
--------------------------------------------------------------------------------
-- **전부 nullable이고 기본값이 없다.** 요약이 없는 종료 경로가 있다 — 소켓 사망, 운영자 취소,
-- state 없이 끝나는 경로. 그런 런은 값을 **모르는** 것이지 0점이 아니다. NOT NULL DEFAULT 0으로
-- 두면 잘 죽는 모델이 전부 0점으로 보이고 그 오류는 조용히 지나간다. V27의 knowledge_usage.cited가
-- nullable인 것과 같은 규율이고, V25의 축 컬럼이 nullable인 것과도 같다.
--
-- 이 마이그레이션 이전의 행은 전부 NULL이 된다. 백필하지 않는다 — 없는 판정을 지어내는 것보다
-- "그 이전"으로 비는 편이 낫다.
--
-- **steps와 cases를 둘 다 승격한다.** case_id가 없는 스텝이 존재하므로(저작 Step 모델에서
-- case_id는 nullable) cases가 steps에서 유도되지 않는다. 한쪽만 두면 케이스 없는 시나리오와
-- 케이스 있는 시나리오를 같은 지표로 못 본다.
--
-- **failed는 승격하지 않는다.** build_summary가 내는 failed는 정의상 total - passed이고,
-- 파생값을 컬럼으로 들고 있으면 세 값이 어긋날 수 있는 상태가 생긴다.
ALTER TABLE qa_try
    ADD COLUMN IF NOT EXISTS steps_total  INT,
    ADD COLUMN IF NOT EXISTS steps_passed INT,
    ADD COLUMN IF NOT EXISTS cases_total  INT,
    ADD COLUMN IF NOT EXISTS cases_passed INT;

-- 컬럼은 GROUP BY용 사본이고 진실은 qa_log의 payload다 — V25의 run_config 컬럼들과 같은 관계다.
-- 둘이 어긋나면 payload가 옳다. 여기 값은 언제나 거기서 파생된 것이다.
COMMENT ON COLUMN qa_try.steps_total IS
    '종단 STATUS payload.summary.steps.total의 사본. NULL은 "요약을 못 받았다"이고 0과 다르다. 진실은 qa_log의 payload이며 이 컬럼은 GROUP BY용 사본이다.';
COMMENT ON COLUMN qa_try.steps_passed IS
    '종단 STATUS payload.summary.steps.passed의 사본. failed는 total - passed라 승격하지 않는다.';
COMMENT ON COLUMN qa_try.cases_total IS
    '종단 STATUS payload.summary.cases.total의 사본. case_id 없이 저작된 시나리오는 0이며, 이는 "측정된 0"이라 NULL(미측정)과 다르다.';
COMMENT ON COLUMN qa_try.cases_passed IS
    '종단 STATUS payload.summary.cases.passed의 사본. TC 판정은 그 구간의 검증 스텝 판정이다(2단 판정).';

--------------------------------------------------------------------------------
-- 2. qa_try_score — 채점 이력
--------------------------------------------------------------------------------
-- **이번 범위에서 쓰는 곳이 없다. 자리만 만든다** — V18이 ARTEL-188의 deleted_at을, V27이
-- replaces_id를 미리 만들어 둔 것과 같은 판단이다. 후속 작업이 첫 채점자를 넣는다.
--
-- qa_try에 점수 컬럼으로 박지 않는 이유: 채점 기준이 바뀌면 **재채점**해야 하고, 컬럼이면
-- 덮어써서 이력이 죽는다. (grader, grader_version)으로 새 판정을 옆에 쌓고 옛 판정을 보존한다.
-- 채점자가 여러 종류라 한 런에 여러 줄이 붙는 것이 이 키의 목적이다 — 서로 대조할 수 있어야 한다.
--
-- **어떤 지표 컬럼을 승격할지는 지금 정하지 않는다.** 첫 채점자가 무엇을 내는지 보고 필요한 것만
-- 올린다. 지금은 detail(JSONB)만 둔다.
--
-- **하드 FK + ON DELETE CASCADE다**(qa_log·issue와 같은 쪽, knowledge 쪽 논리참조가 아니라).
-- 점수는 특정 런에 **대한** 파생 판정이라 런 없이는 주어가 없다. grader/grader_version/detail만
-- 남은 행은 무엇에 대한 점수인지 말하지 못하고, 재도출도 불가능하다 — 근거인 qa_log가 같은
-- CASCADE로 이미 사라졌기 때문이다. 고아 점수는 조인에서 조용히 빠져 축 평균만 낮춘다.
-- 지식창고 쪽이 논리참조인 이유는 반대다: 거기는 이력이 원본보다 오래 남는 것이 목적이라
-- 하드 FK가 그 목적을 깬다. qa_try는 하드삭제되고(deleteByTestScenarioId — 시나리오 강제 삭제),
-- CASCADE는 그 정리를 막지 않는다.
CREATE TABLE IF NOT EXISTS qa_try_score (
    id             BIGSERIAL PRIMARY KEY,
    qa_try_id      BIGINT NOT NULL REFERENCES qa_try (id) ON DELETE CASCADE,
    -- 채점자 이름과 그 채점자의 버전. 같은 런을 같은 채점자의 새 버전으로 다시 매기면 옛 줄
    -- 옆에 새 줄이 선다.
    grader         VARCHAR(50) NOT NULL,
    grader_version VARCHAR(50) NOT NULL,
    detail         JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 재시도나 동시 쓰기로 같은 (런, 채점자, 버전)이 두 번 기록되는 것을 막는다. 조회 축이기도
    -- 해서 qa_try_id 단독 인덱스는 따로 만들지 않는다 — 선두 컬럼이 이미 그것이다.
    CONSTRAINT uq_qa_try_score UNIQUE (qa_try_id, grader, grader_version)
);

COMMENT ON TABLE qa_try_score IS
    'qa_try 하나에 대한 채점 결과. (grader, grader_version)당 한 줄이라 재채점이 옛 판정을 덮지 않는다. ARTEL-299 범위에서는 채우지 않는다 — 자리만 만든 테이블이다.';
COMMENT ON COLUMN qa_try_score.detail IS
    '채점자가 낸 값 전체. 어떤 지표를 컬럼으로 승격할지는 첫 채점자가 무엇을 내는지 본 뒤에 정한다.';
