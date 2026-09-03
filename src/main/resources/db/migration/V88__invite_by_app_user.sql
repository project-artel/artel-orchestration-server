-- 초대가 대상을 계정으로도 가리킨다(ARTEL-774).
--
-- V73 은 초대가 email 만 가리키게 했다. 아직 가입하지 않은 사람을 부를 수 있어야 했기 때문이고,
-- 그 이유는 지금도 유효하다. 하지만 반대쪽 끝이 막혀 있었다 — GitHub 이 공개 이메일을 주지 않은
-- 계정은 app_user.email 이 NULL 이라 어떤 초대의 수신자도 될 수 없었고, 초대 대상 후보 검색에도
-- 나오지 않았다.
--
-- 초대는 이미 웹 초대함으로 배달된다. 이메일은 배달 수단이 아니라 대상을 가리키는 이름일 뿐이고,
-- 그 이름이 없다는 것이 협업에서 빠질 이유는 못 된다.
--
-- 대상이 사라지면 초대도 사라지므로 ON DELETE CASCADE 다. invited_by 가 SET NULL 인 것과 다른
-- 선택인데, 그쪽은 보낸 사람이 지워져도 초대가 프로젝트의 기록으로 남기 때문이다. 아무도
-- 가리키지 않는 초대는 남길 뜻이 없다.
ALTER TABLE project_invitation
    ADD COLUMN app_user_id BIGINT REFERENCES app_user (id) ON DELETE CASCADE;

-- email 은 이제 아직 계정이 없는 사람을 부르는 자리다. 계정을 가리키는 초대는 이 칸을 비운다.
ALTER TABLE project_invitation
    ALTER COLUMN email DROP NOT NULL;

-- 대상은 정확히 하나다. 둘 다 차 있으면 어느 쪽이 대상인지 정할 근거가 없고 — 두 값이 서로 다른
-- 사람을 가리킬 수도 있다 — 둘 다 비면 아무도 가리키지 않는 초대가 된다.
ALTER TABLE project_invitation
    ADD CONSTRAINT ck_project_invitation_target
    CHECK ((email IS NULL) <> (app_user_id IS NULL));

-- 계정 초대의 중복 방어선. uk_project_invitation_pending 과 같은 자리에 서 있고, 같은 이유로
-- 조회가 아니라 제약이 막는다 — 조회로 먼저 확인하면 두 요청 사이에 경합이 난다.
--
-- email 쪽 index 는 건드리지 않는다. 계정 초대는 email 이 NULL 이고, Postgres 는 unique index
-- 에서 NULL 을 서로 다른 값으로 보므로 그 index 에 걸리지 않는다.
--
-- 두 index 는 서로를 모른다. 같은 사람을 확인된 주소로 한 번, 계정으로 한 번 부르면 PENDING
-- 초대가 둘 남는다. 그것으로 멤버십이 두 번 생기지는 않는다 — 실제 방어선은 accept 가 멤버 행을
-- 두 번 넣지 않는 것이고, 그 논거는 V73 이 uk_project_invitation_pending 에 적은 것과 같다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_project_invitation_pending_app_user
    ON project_invitation (project_id, app_user_id)
    WHERE status = 'PENDING';

-- 받은 초대함이 매 요청 이 순서로 읽는다 — 내 계정으로 온 것 중 아직 PENDING 인 것.
-- idx_project_invitation_email 의 계정 쪽 짝이다.
CREATE INDEX IF NOT EXISTS idx_project_invitation_app_user
    ON project_invitation (app_user_id, status);
