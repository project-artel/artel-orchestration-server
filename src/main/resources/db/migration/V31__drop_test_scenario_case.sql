-- 폐기된 시나리오↔케이스 조합 테이블(test_scenario_case) 제거 (ARTEL-283)
--
-- Step 모델 재설계(#97)로 시나리오는 test_scenario.payload(JSONB)의 steps로 저장되고, 각 스텝이
-- caseId로 검증 대상 TC를 옵션 참조한다. N:M 조합 테이블은 코드에서 완전히 폐기됐다
-- (TestScenarioCase Controller/Entity/Repository 삭제). V17에서 만든 테이블만 DB에 남아
-- 스키마와 실제 사용이 불일치하므로 정리한다.
--
-- 안전: 이 테이블을 참조하는 FK 없음(레포 관례상 프로젝트/시나리오/케이스는 논리 참조). 인덱스와
-- UNIQUE 제약(uk_test_scenario_case_position)은 테이블과 함께 삭제된다. test_case/test_scenario
-- 본체와 test_run_scenario는 유지 — 조합 링크 테이블만 제거한다.

DROP TABLE IF EXISTS test_scenario_case;
