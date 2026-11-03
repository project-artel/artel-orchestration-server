-- 기존 선언을 보존하고 build가 선택하는 revision을 분리한다. 과거 내용은 추측하지 않는다.
CREATE TABLE game_identity_revision (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    kind VARCHAR(20) NOT NULL CHECK (kind IN ('ordinal', 'enum', 'id', 'flag', 'text')),
    base INT CHECK (base IN (0, 1)),
    meaning VARCHAR(500) NOT NULL,
    changes_when VARCHAR(500) NOT NULL,
    identifies VARCHAR(60) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('current', 'progress', 'other')),
    game_build_id BIGINT NOT NULL REFERENCES game_build(id),
    version INT NOT NULL CHECK (version > 0),
    source VARCHAR(10) NOT NULL CHECK (source IN ('SDK', 'MCP', 'WEB', 'LEGACY')),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_by BIGINT REFERENCES app_user(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, name, version),
    UNIQUE(project_id, name, id)
);
CREATE TABLE game_identity_build_binding (
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    game_build_id BIGINT NOT NULL REFERENCES game_build(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    revision_id BIGINT NOT NULL,
    PRIMARY KEY(project_id, game_build_id, name),
    FOREIGN KEY(project_id, name, revision_id) REFERENCES game_identity_revision(project_id, name, id)
);
INSERT INTO game_identity_revision
    (project_id, name, kind, base, meaning, changes_when, identifies, role, game_build_id, version, source, created_by, created_at)
SELECT project_id, name, kind, base, meaning, changes_when, identifies, role, game_build_id, version, 'LEGACY', updated_by, updated_at
FROM game_identity;
INSERT INTO game_identity_build_binding(project_id, game_build_id, name, revision_id)
SELECT project_id, game_build_id, name, id FROM game_identity_revision;
