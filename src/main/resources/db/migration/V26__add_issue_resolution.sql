-- 이슈에 "사람이 처리했다"는 사실을 더한다(ARTEL-245).
--
-- V12까지 issue는 Agent가 관측한 증거만 담았다. 사람이 더하는 것은 이 한 가지뿐이라 상태도
-- 둘뿐이다: OPEN / RESOLVED. WONTFIX·DUPLICATE 같은 세 번째 값을 미리 두지 않는다 — 지금 화면이
-- 묻는 질문은 "남았나 처리됐나" 하나이고, 늘리는 것은 값 추가로 언제든 된다.
--
-- severity와 같은 방식(VARCHAR + CHECK)으로 값을 강제한다. 기존 행은 DEFAULT로 전부 OPEN이
-- 되므로 이 마이그레이션은 되돌릴 수 있고, 앞선 배포와도 호환된다.
--
-- resolved_by는 app_user를 참조하되 ON DELETE SET NULL이다. 처리자가 탈퇴해도 "처리됐다"는
-- 사실 자체는 남아야 한다 — 그 사람이 누구였는지를 잃는 것이 이슈가 다시 열리는 것보다 낫다.
-- resolved_at/resolved_by는 status와 함께만 움직인다(OPEN이면 둘 다 NULL).

ALTER TABLE issue
    ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'OPEN'
        CHECK (status IN ('OPEN', 'RESOLVED')),
    ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS resolved_by BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    -- status와 resolved_at은 함께만 움직인다. 서비스가 두 컬럼을 한 UPDATE에서 같이 쓰지만,
    -- 그 규칙을 코드에만 두면 나중에 다른 쓰기 경로가 OPEN 행에 낡은 resolved_at을 남길 수 있고
    -- 화면은 그것을 "해결된 시각"으로 그린다. resolved_by는 묶지 않는다 — ON DELETE SET NULL로
    -- 혼자 NULL이 될 수 있어야 한다.
    ADD CONSTRAINT ck_issue_resolution_pairs
        CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL));

-- 한 실행의 미해결 이슈 목록(QA Try 화면의 패널)을 받친다. 해결된 행은 시간이 지날수록 다수가
-- 되므로 부분 인덱스가 그 다수를 인덱스 밖에 둔다. 프로젝트 단위 목록은 qa_try_id로 좁히지
-- 않으므로 이 인덱스를 타지 않는다 — 그쪽은 조인 뒤 정렬이며, 필요해지면 그때 따로 받친다.
CREATE INDEX IF NOT EXISTS idx_issue_open_qa_try_id_desc
    ON issue (qa_try_id, id DESC)
    WHERE status = 'OPEN';
