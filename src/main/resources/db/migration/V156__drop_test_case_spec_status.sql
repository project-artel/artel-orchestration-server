-- TC의 명세 등급을 제거한다. QA 실행 결과 verification_status는 유지한다.
ALTER TABLE test_case DROP COLUMN status;
ALTER TABLE test_case_version DROP COLUMN status;
