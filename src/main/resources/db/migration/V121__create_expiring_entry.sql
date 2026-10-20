--------------------------------------------------------------------------------
-- V118. ARTEL_STORE=postgres 일 때 Redis 대신 쓰는 만료 키-값 표
--------------------------------------------------------------------------------
-- SDK 로그인 일회용 코드(`SdkLoginCodeStore`)와 GitHub installation 목록
-- (`TrackerInstallationChoiceStore`)가 Redis 에 두던 값을 같은 표 하나에 둔다. 두 저장소는 모두
-- "키 하나에 문자열 하나, TTL, 어떤 것은 한 번만 소비" 라는 같은 모양이라 표를 나누지 않는다.
-- 키 공간은 Redis 와 같은 접두사로 갈라진다.
--
-- 만료 판정은 읽는 쪽이 `expires_at > now()` 로 한다. 지우는 일은 `PostgresExpiringKeyValueStore` 의
-- 주기 작업이 `expires_at` index 로 하며, 지우기 전이라도 만료된 행은 읽히지 않는다.

CREATE TABLE IF NOT EXISTS expiring_entry (
    entry_key VARCHAR(512) PRIMARY KEY,
    entry_value TEXT NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_expiring_entry_expires_at
    ON expiring_entry (expires_at);
