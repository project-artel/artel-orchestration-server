-- V32__promote_test_scenario_payload_columns.sql
-- test_scenario의 시나리오 본문을 payload(JSONB) 한 덩어리에서 title/description/steps 컬럼으로 승격한다 (ARTEL-291).
--
-- V5 이래 시나리오의 제목·설명·스텝은 payload 하나에 뭉쳐 있었다. 그래서 스키마만 봐서는 이 테이블이
-- 무엇을 담는지 알 수 없고, 제목 하나를 바꿔도 스텝 전체를 다시 써야 했다(목록 조회도 행마다 JSON을
-- 역직렬화해 title만 뽑았다). Step 모델 재설계(#97)로 steps가 payload에 꽉 차면서 그 비용이 커졌다.
--
-- steps는 JSONB로 남긴다. 내부 구조가 Agent 계약(ScenarioStep{action, case_id, hint, input})의 거울이라
-- 컬럼으로 더 쪼개면 계약이 바뀔 때마다 마이그레이션이 따라붙는다.
--
-- ⚠️ payload DROP은 되돌릴 수 없다. 백필이 끝난 뒤 원본이 사라지므로 적용 전 백업을 전제한다.
--    백필은 구조 변환을 하지 않는다 — 재설계 이전 형태의 steps가 남아 있으면 그대로 옮겨진다(이미
--    지금도 읽을 때 빈 스텝으로 퇴화하는 행이며, 정리는 별건이다).

ALTER TABLE test_scenario
    ADD COLUMN title       TEXT  NOT NULL DEFAULT '',
    ADD COLUMN description TEXT  NOT NULL DEFAULT '',
    ADD COLUMN steps       JSONB NOT NULL DEFAULT '[]'::jsonb;

-- 기존 행 백필. payload에 키가 없거나 null이면 컬럼 기본값과 같은 값으로 떨어뜨린다.
UPDATE test_scenario
SET title       = COALESCE(payload ->> 'title', ''),
    description = COALESCE(payload ->> 'description', ''),
    steps       = COALESCE(payload -> 'steps', '[]'::jsonb);

ALTER TABLE test_scenario
    DROP COLUMN payload;
