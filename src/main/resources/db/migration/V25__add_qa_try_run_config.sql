-- QA 런의 실행 설정을 qa_try에 남긴다. (2026-08-05, ARTEL-239)
--
-- V24는 develop에서 ARTEL-233(llm_usage)이 먼저 가져갔다. Flyway는 같은 번호를 두 번
-- 적용하지 않으므로 번호만 밀었고, 내용은 그 마이그레이션과 무관하다.
--
-- 목적은 QA 에이전트를 모델 / 에이전트 구조 / 프롬프트 버전 축으로 비교하는 것이다. 지금은
-- 한 런이 무엇으로 실행됐는지가 어디에도 남지 않아, 결과가 좋아졌을 때 모델 덕인지 프롬프트
-- 덕인지 구조 덕인지 판단할 근거가 없다.
--
-- 저장하는 값은 요청값이 아니라 Agent가 **해석한** 값이다. 요청의 prompt_version=null은 "최신"이라는
-- 별칭이라 v4가 추가되는 순간 과거 런의 소속이 바뀌고, arch.vision="auto"는 모델에 대한 질문이지
-- 답이 아니다. Agent가 세션 개설 응답으로 확정형을 돌려주고(POST /qa-sessions), 여기에는 그것만 들어온다.
--
-- 비교 축 4개만 컬럼으로 승격하고 나머지 해석값 전부는 run_config JSONB 스냅샷에 둔다. 집계
-- 쿼리는 승격 컬럼만 쓰므로 인덱스가 살고, 축을 늘리고 싶으면 JSONB에서 꺼내 승격하면 된다.
-- 컬럼은 GROUP BY용 사본이고 진실은 JSONB다.
--
-- 전부 nullable이다. 이 마이그레이션 이전의 행과, run_config를 아직 돌려주지 않는 구버전
-- Agent의 응답을 둘 다 받아야 한다 — 설정을 모르는 런은 기록에서 빠질 뿐, 실패해서는 안 된다.
ALTER TABLE qa_try
    ADD COLUMN IF NOT EXISTS model             VARCHAR(100),
    -- NULL이 "미지정"과 "모델이 지원 안 함" 두 뜻이다. 구분은 run_config.reasoning_supported가 한다.
    ADD COLUMN IF NOT EXISTS reasoning_effort  VARCHAR(20),
    ADD COLUMN IF NOT EXISTS prompt_version    VARCHAR(20),
    -- 사람이 붙이는 구조 라벨과, 툴 시그니처·미들웨어·루프 한도에서 파생된 지문.
    -- 라벨은 리포트에서 읽히고, 지문은 라벨 bump를 잊은 변경을 갈라낸다. 둘 다 필요하다.
    ADD COLUMN IF NOT EXISTS agent_arch        VARCHAR(50),
    ADD COLUMN IF NOT EXISTS agent_fingerprint VARCHAR(20),
    ADD COLUMN IF NOT EXISTS run_config        JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE INDEX IF NOT EXISTS idx_qa_try_config
    ON qa_try (model, prompt_version, agent_arch, reasoning_effort);
