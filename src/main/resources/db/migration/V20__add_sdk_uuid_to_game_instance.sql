-- 인스턴스의 정체성을 instance_key(대시보드가 발급하던 자격증명)에서 sdk_uuid(런타임이
-- 스스로 만들어 보관하는 설치 식별자)로 옮긴다.
--
-- 인증은 이제 로그인한 사용자의 SDK 토큰이 맡는다. 키는 인증을 겸하느라 인스턴스 식별까지
-- 하고 있었는데, 인증이 분리되면 남는 일은 "어느 설치본인가" 하나뿐이고 그 값은 SDK가
-- 이미 갖고 있다(last_sdk_uuid로 보고해 오던 값).
--
-- (project_id, sdk_uuid) 유니크는 등록이 조회-후-생성이라서 필요하다. 경합이 나면 제약이
-- 충돌을 예외로 만들고, 서비스가 다시 읽어 기존 행을 쓴다.
--
-- 기존 행은 backfill하지 않는다. last_sdk_uuid는 한 프로젝트 안에서 중복될 수 있어
-- 유니크 제약과 부딪히고, 그 충돌을 자동으로 풀 근거가 없다. sdk_uuid가 비어 있는 인스턴스는
-- 다음 등록에서 새 행으로 나타나므로, 남은 행은 대시보드에서 지운다.
ALTER TABLE game_instance
    ADD COLUMN IF NOT EXISTS sdk_uuid VARCHAR(64);

ALTER TABLE game_instance
    DROP COLUMN IF EXISTS last_sdk_uuid;

CREATE UNIQUE INDEX IF NOT EXISTS uk_game_instance_project_sdk_uuid
    ON game_instance (project_id, sdk_uuid);
