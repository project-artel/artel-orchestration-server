-- test_scenario_case(조합 링크)에 position별 저작 Step을 담는다. (ARTEL-254)
--
-- Step = 그 자리(시나리오 S의 position N에 놓인 TC)의 가볍고 advisory한 가이드. 두 종류:
--   setup(사전조건 도달 경로, assert=false로 fast-forward·판정 안 함) / guide(TC 실행 단계).
--   setup은 "도착지 TC"에 붙는다(arrive-at-me) — from은 position 순서로 암묵. 한 행 = "이 TC 도착 + 실행".
--   재사용되지 않는 시퀀스·컨텍스트 전용 글루라 TC 본체가 아니라 이 조합 행에 둔다(TC 재사용성 보존).
--   흔한 컨트롤(예: Enter=진행)은 여기 넣지 않는다 — 런타임/게임지식으로 해소.
--
-- JSONB 배열로 담아 행 수 폭발을 피한다(정규화 test_step 테이블 대신). 한 자리에 여러 스텝 = 배열이며
--   항상 그 자리 뭉치로 읽고 쓴다:
--     [{ id, kind: "setup"|"guide"|"verify", assert: bool, intent, hint?, input?, observe? }]
--
-- ⚠️ ScenarioReconcileService·ScenarioCompositionService가 이 행을 delete+recreate 하므로, 그 경로들은
--   (test_case_id, position) 매칭으로 steps를 캐리포워드해야 한다(자리가 사라진 스텝은 폐기). 이 마이그레이션은
--   컬럼만 추가한다(무손상, additive; 실행부가 steps를 무시하면 기존 동작 그대로).
ALTER TABLE test_scenario_case
    ADD COLUMN IF NOT EXISTS steps JSONB NOT NULL DEFAULT '[]'::jsonb;
