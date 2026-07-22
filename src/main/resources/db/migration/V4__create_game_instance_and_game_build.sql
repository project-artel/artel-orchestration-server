-- 프로젝트에 붙은 SDK 설치본 하나. 대시보드에서 만들고 instance_key를 발급받으면,
-- Unity에 심은 SDK가 그 키로 자신을 등록하고 웹소켓을 연다.
--
-- SDK를 별도 테이블로 두지 않는다. 사람이 이름을 붙이고 지우는 단위가 곧 설치본이라
-- 둘을 나누면 화면에서 관리할 수 없는 행만 늘어난다. 한 게임을 여러 사람이 돌리면
-- 각자 인스턴스를 만든다.
--
-- instance_key는 만료도 회전도 없는 영구 자격증명이고 평문으로 둔다. 보안 강화는
-- 별도 작업이며, 지금은 개발 환경에서만 쓰인다.
--
-- last_sdk_uuid는 마지막으로 등록한 런타임의 식별자다. 자격증명이 아니라 기록이다.
CREATE TABLE IF NOT EXISTS game_instance (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    name VARCHAR(80) NOT NULL,
    platform VARCHAR(16) NOT NULL,
    instance_key VARCHAR(32) NOT NULL,
    last_sdk_uuid VARCHAR(64),
    last_connected_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_game_instance_key UNIQUE (instance_key)
);

CREATE INDEX IF NOT EXISTS idx_game_instance_project
    ON game_instance (project_id);

-- SDK가 보고한 게임 버전. 등록 요청마다 (project_id, version)으로 찾아 없으면 만든다.
--
-- 인스턴스가 아니라 프로젝트에 붙인다. 같은 빌드를 여러 사람이 돌려도 빌드는 하나여야
-- 하고, 인스턴스를 지웠다고 그 빌드 기록까지 사라지면 안 되기 때문이다.
--
-- version은 Unity Player Settings에서 관찰한 값이라 수정할 수 없다. 사람이 붙이는
-- 설명은 label과 notes로 따로 받는다.
--
-- (project_id, version) 유니크 제약이 중복 생성의 실제 방어선이다. 조회 후 저장 사이에
-- 경합이 나므로, 제약으로 충돌을 만들고 재시도해 같은 버전이 두 행이 되는 것을 막는다.
CREATE TABLE IF NOT EXISTS game_build (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    version VARCHAR(64) NOT NULL,
    label VARCHAR(80),
    notes VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_game_build_project_version UNIQUE (project_id, version)
);

CREATE INDEX IF NOT EXISTS idx_game_build_project
    ON game_build (project_id);
