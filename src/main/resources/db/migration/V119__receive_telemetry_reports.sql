--------------------------------------------------------------------------------
-- V119. 직접 설치한 서버가 보낸 telemetry report 를 받아 둔다
--------------------------------------------------------------------------------
-- 계획은 `.plan/general/2026-10-20-opt-in-telemetry-from-self-hosted-installs.md` 다.
--
-- consent 한 설치가 UTC 하루에 한 번 `POST /api/telemetry/reports` 로 report 하나를 보낸다. 이 표는 그것을
-- 받는 artel.kr 서버에서만 행이 생긴다. 직접 설치한 서버에도 같은 마이그레이션이 돌지만 그쪽 표는 비어 있다.
--
-- 보낸 쪽 IP 주소는 저장하지 않는다. 설치를 가르는 값은 `install_id` 하나이고, 그것은 사람이 아니라 설치에
-- 붙은 무작위 UUID 다.
--
-- 같은 날을 다시 보내면 덮어쓴다(`uk_telemetry_report_install_date`). 보내는 쪽은 응답을 못 받으면 다음 시간에
-- 같은 날을 다시 보내므로, 덮어쓰지 않으면 하루가 두 줄이 된다.

CREATE TABLE IF NOT EXISTS telemetry_report (
    id BIGSERIAL PRIMARY KEY,
    install_id UUID NOT NULL,
    -- report 가 다루는 UTC 날짜. 받은 날짜가 아니다.
    report_date DATE NOT NULL,
    schema_version INT NOT NULL,
    -- 검증한 뒤 다시 직렬화한 report 전체. 버전 1 은 SQL 로 직접 조회한다.
    report JSONB NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_telemetry_report_install_date UNIQUE (install_id, report_date)
);

-- 날짜 구간으로 읽는 조회("지난 30일 동안 쓰인 agent_arch")를 받친다. install_id 로 지우는 요청은 위 unique
-- 제약의 index 가 받는다.
CREATE INDEX IF NOT EXISTS idx_telemetry_report_date ON telemetry_report (report_date);
