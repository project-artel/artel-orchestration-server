-- 기존 TC의 사용 가능 상태를 유지한다. 실행 판정은 verification_status에 그대로 남는다.
ALTER TABLE test_case ADD COLUMN review_status VARCHAR(20) NOT NULL DEFAULT 'READY'
    CHECK (review_status IN ('DRAFT', 'READY'));
