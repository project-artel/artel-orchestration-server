-- 게임/제품 단위의 상위 묶음. QA 대상 빌드와 세션이 모두 이 아래에 붙는다.
-- deleted_at은 soft delete 표시다. 접근 가능 여부 조회는 항상 deleted_at IS NULL을
-- 함께 걸어, 삭제된 프로젝트가 처음부터 없던 것과 구분되지 않게 한다.
--
-- 소유자 컬럼을 따로 두지 않는다. 누가 접근할 수 있는지는 project_member 한 곳에서만
-- 답이 나와야 하며, owner_id를 함께 두면 두 곳이 어긋났을 때 어느 쪽이 진짜인지 알 수 없다.
CREATE TABLE IF NOT EXISTS project (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(2000),
    genre VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP WITH TIME ZONE
);

-- 사용자와 프로젝트의 M:N 참여 관계. 한 사용자가 여러 프로젝트에 속하고,
-- 한 프로젝트에 여러 사용자가 속한다.
--
-- role은 OWNER 또는 MEMBER다. 프로젝트를 만든 사람이 첫 OWNER가 되며,
-- 삭제처럼 되돌릴 수 없는 동작은 OWNER만 할 수 있다.
CREATE TABLE IF NOT EXISTS project_member (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    app_user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_project_member_project_user UNIQUE (project_id, app_user_id)
);

CREATE INDEX IF NOT EXISTS idx_project_member_user
    ON project_member (app_user_id);

CREATE INDEX IF NOT EXISTS idx_project_member_project
    ON project_member (project_id);

-- 프로젝트 기획서 원본. 새로 올리면 교체하지 않고 version을 올려 쌓는다.
-- 원본 바이트는 S3에 있고 여기에는 메타데이터만 둔다. object_key는 외부에
-- 노출하지 않으며, 다운로드는 매번 presigned URL을 새로 발급해 처리한다.
--
-- (project_id, version) 유니크 제약이 버전 채번의 실제 방어선이다.
-- MAX(version) + 1은 읽고 쓰는 사이에 경합이 나므로, 제약으로 충돌을 만들고
-- 재시도해 두 행이 같은 버전을 갖는 상태를 막는다.
--
-- parse_status는 이후 기획서 파싱 파이프라인이 쓸 자리다. 지금은 PENDING으로만
-- 기록되고 진행되지 않는다.
CREATE TABLE IF NOT EXISTS project_document (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    uploaded_by BIGINT NOT NULL REFERENCES app_user (id),
    uploaded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    parse_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    CONSTRAINT uk_project_document_version UNIQUE (project_id, version)
);

CREATE INDEX IF NOT EXISTS idx_project_document_project
    ON project_document (project_id);
