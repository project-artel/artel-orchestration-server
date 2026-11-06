-- 프로젝트에 사람을 이메일로 초대한 기록(ARTEL-685).
--
-- 초대는 app_user 가 아니라 이메일을 가리킨다. 아직 가입하지 않은 사람을 초대할 수 있어야 하고,
-- 그 사람이 나중에 같은 이메일로 로그인했을 때 기다리던 초대가 보여야 하기 때문이다. app_user_id
-- 를 여기 두면 초대 시점에 계정이 있는 사람만 부를 수 있다.
--
-- 수락은 이 행의 email 과 로그인한 사용자의 app_user.email 을 맞춰 판정한다. 반대 방향(이메일로
-- app_user 를 찾아 그 사람의 초대로 삼는 것)은 쓰지 않는다 — app_user.email 에 unique 제약이 없어
-- 같은 이메일을 가진 행이 여럿일 수 있고, 그러면 남의 초대를 가져가는 길이 열린다.
CREATE TABLE IF NOT EXISTS project_invitation (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    -- 저장할 때 소문자로 정규화한다. 길이는 app_user.email 과 같게 맞춘다.
    email VARCHAR(320) NOT NULL,
    -- 수락했을 때 갖게 될 project_member.role.
    role VARCHAR(16) NOT NULL CHECK (role IN ('OWNER', 'MEMBER')),
    -- EXPIRED 가 없는 것은 빠뜨린 것이 아니다. 만료는 expires_at 과 현재 시각을 비교해 조회할 때
    -- 정한다. 여기에 EXPIRED 를 담으면 때가 된 행을 뒤집어 줄 배치가 필요해지고, 그 배치가 멈춘
    -- 동안 status 가 거짓을 말한다.
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'REVOKED')),
    -- 초대한 사람. 그 사람이 지워져도 초대 기록은 프로젝트의 것이므로 NULL 로 남긴다.
    invited_by BIGINT REFERENCES app_user (id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- PENDING 을 벗어난 시각. 감사 기록이고 어떤 판단에도 쓰지 않는다.
    responded_at TIMESTAMP WITH TIME ZONE
);

-- 중복 초대의 실제 방어선. 조회로 먼저 확인하면 두 요청 사이에 경합이 나므로, 제약으로 충돌을
-- 만들고 서비스가 DataIntegrityViolationException 을 잡아 409 로 옮긴다.
--
-- lower(email) 위에 있어 User@x.com 과 user@x.com 이 같은 키로 충돌한다. WHERE 로 PENDING 만
-- 거는 것은, 거절되거나 취소된 뒤에는 같은 사람을 다시 부를 수 있어야 하기 때문이다.
--
-- 이 index 는 "이미 멤버인 사람"은 막지 못한다. 그쪽은 ACCEPTED 행이라 조건에 걸리지 않는다.
-- 초대 생성이 멤버 여부를 따로 확인하고, 수락이 멤버 행을 두 번 넣지 않는 것으로 막는다.
CREATE UNIQUE INDEX IF NOT EXISTS uk_project_invitation_pending
    ON project_invitation (project_id, lower(email))
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_project_invitation_project
    ON project_invitation (project_id);

-- 받은 초대함이 매 요청 이 순서로 읽는다 — 내 이메일로 온 것 중 아직 PENDING 인 것.
CREATE INDEX IF NOT EXISTS idx_project_invitation_email
    ON project_invitation (lower(email), status);
