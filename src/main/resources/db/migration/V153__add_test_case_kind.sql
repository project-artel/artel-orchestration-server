-- TC 종류(ARTEL-1306). 기능 TC 는 검증할 기능 하나, 예외 TC 는 조건이 안 맞을 때 막히는지 · 되풀이를 막는지 본다.
-- 있던 TC 는 모두 기능이다.
ALTER TABLE test_case
    ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'FUNCTION'
        CONSTRAINT test_case_kind_check CHECK (kind IN ('FUNCTION', 'EXCEPTION'));
