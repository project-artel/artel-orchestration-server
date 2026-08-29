-- 프로젝트를 외부 이슈 tracker 에 잇고, 내보낸 결함마다 그쪽 이슈를 기록한다(ARTEL-671).
--
-- `provider` 가 값인 이유: GitHub 이 첫 구현체일 뿐이고 Jira 가 다음 후보다. tracker 이름을 테이블
-- 이름이나 컬럼 이름에 박으면 두 번째 tracker 에서 스키마가 통째로 갈라진다. 그래서 컬럼 이름은
-- 전부 `provider` 중립이고(external_workspace 는 GitHub 에서 owner, Jira 라면 site 가 된다), 어느
-- tracker 인지는 `provider` 컬럼 하나가 말한다. CHECK 에 값을 하나 더하는 것이 Jira 를 붙이는 비용이다.

CREATE TABLE IF NOT EXISTS project_tracker_link (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    provider VARCHAR(32) NOT NULL CHECK (provider IN ('GITHUB')),
    -- GitHub 에서는 owner(사용자 또는 조직)와 저장소 이름이 짝을 이룬다.
    external_workspace VARCHAR(255),
    external_repository VARCHAR(255),
    -- GitHub App 의 installation id. nullable 인 이유가 둘이다: `installation` callback 이 저장소
    -- 선택보다 먼저 오므로 그 사이의 행에는 저장소가 없고, GitHub 밖의 tracker 에는 `installation`
    -- 이라는 개념 자체가 없다.
    -- ⚠️ 여기에 비밀은 없다. installation access token 은 수명이 한 시간이라 메모리에만 cache 한다.
    installation_ref VARCHAR(255),
    -- 자동 `sync` 를 거는 severity 기준. 쉼표 구분 문자열로 두는 이유는 질의에서 배열 연산을 하지
    -- 않기 때문이다 — 읽는 쪽이 행을 통째로 가져와 IssueSeverity 로 파싱해 판정한다. text[] 를
    -- 도입하면 R2DBC 배열 매핑이라는 축이 하나 늘어나는데, 그것이 사 오는 것이 아직 없다.
    auto_sync_severities VARCHAR(255) NOT NULL DEFAULT 'BLOCKER,CRITICAL',
    -- `link` 를 만든 사람. 사용자가 지워져도 `link` 자체는 프로젝트의 것이므로 NULL 로 남긴다.
    connected_by BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 프로젝트당 `provider` 하나. 같은 프로젝트를 GitHub 저장소 둘에 잇는 것은 이 스토리의 범위가
-- 아니고, tracker 가 늘면 `provider` 가 다른 행이 나란히 선다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_project_tracker_link_provider
    ON project_tracker_link (project_id, provider);

-- 결함 하나가 외부 tracker 에 남긴 흔적.
--
-- issue 테이블에 컬럼을 더하지 않는다. tracker 가 둘이 되는 순간 external_key · external_url ·
-- sync_state · sync_error · synced_at 가 통째로 배로 늘고, 그 컬럼들은 tracker 를 붙이지 않은
-- 대다수 프로젝트의 모든 행에서 영원히 NULL 이다.
CREATE TABLE IF NOT EXISTS issue_tracker_link (
    id BIGSERIAL PRIMARY KEY,
    issue_id BIGINT NOT NULL REFERENCES issue (id) ON DELETE CASCADE,
    provider VARCHAR(32) NOT NULL CHECK (provider IN ('GITHUB')),
    -- 저쪽에서의 식별자. GitHub 은 이슈 번호다.
    external_key VARCHAR(255),
    external_url TEXT,
    -- PENDING 은 "누군가 이 행을 `claim` 해 내보내는 중"이라는 뜻이다. 이 행의 존재 자체가 lock 을
    -- 대신하므로(조건부 INSERT ... ON CONFLICT), 이 값이 곧 동시성 제어의 근거다.
    sync_state VARCHAR(16) NOT NULL
        CHECK (sync_state IN ('PENDING', 'SYNCED', 'FAILED')),
    -- 화면으로 나가는 값이라 외부 응답 원문이 아니라 우리가 쓴 요약만 담는다.
    sync_error TEXT,
    synced_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 결함 하나에서 같은 tracker 의 이슈가 둘 생기지 않게 하는 제약. 멱등 `claim` 이 ON CONFLICT 로
-- 기대는 대상이 바로 이 인덱스다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_issue_tracker_link_provider
    ON issue_tracker_link (issue_id, provider);

-- 목록 조립은 한 페이지의 issue id 들을 IN 으로 한 번에 읽는다. 위 unique index 의 선두 컬럼이
-- issue_id 라 그 질의를 이미 받치므로 별도 index 를 더하지 않는다.
