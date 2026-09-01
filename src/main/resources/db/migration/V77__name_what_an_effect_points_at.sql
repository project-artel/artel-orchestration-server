-- 효과가 가리키는 것을 **사람이 찾을 수 있는 이름**으로 (ARTEL-615).
--
-- 기대결과가 코드 표현식을 그대로 부른다 — `ChatWindowController.anyKeyPrompt 의 표시 상태가 바뀐다`.
-- QA 담당자가 게임에서 그 이름을 찾을 수 없다. 하이어라키에 있는 것은 `Canvas/ChatWindow/AnyKeyPrompt`
-- 이고, 문서가 그 대응을 **직렬화 참조**로 이미 말하고 있다(실측 70건).
--
-- 구버전 `specs_v2` 도 같은 답을 쓴다 — 문자열에서 이름을 뽑지 않고(`Find("X")` 파싱은 한 게임에만
-- 맞는 방식이다), 캡처된 씬 계층 경로로 치환한다. 못 찾으면 코드 표현식을 그대로 두고 등급을 낮춘다.
--
-- **효과 행을 덮어쓰지 않는다.** `capability_effect.target` 은 코드가 말한 것이고 이 표는 씬이 말한
-- 것이라, 둘은 서로를 대신하지 못한다 — 덮어쓰면 어느 코드가 그것을 건드렸는지 되짚을 수 없다.
CREATE TABLE IF NOT EXISTS scene_object_ref (
    id             BIGSERIAL PRIMARY KEY,
    content_map_id BIGINT       NOT NULL REFERENCES content_map (id) ON DELETE CASCADE,
    scene_id       BIGINT       NOT NULL REFERENCES scene (id) ON DELETE CASCADE,
    owner_type     VARCHAR(255) NOT NULL,
    field          VARCHAR(255) NOT NULL,
    -- 씬 오브젝트면 하이어라키 경로, 에셋이면 그 이름. 둘 다 사람이 찾을 수 있는 이름이다.
    target_name    VARCHAR(512) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE scene_object_ref IS
    '컴포넌트의 직렬화 필드가 무엇을 가리키나(문서의 objects[].components[].refs[]). 효과 대상을 사람이 찾을 수 있는 이름으로 옮기는 데 쓴다.';
COMMENT ON COLUMN scene_object_ref.owner_type IS
    '타입 이름의 마지막 마디. 문서가 같은 타입을 네임스페이스까지 적기도 하고 안 적기도 해서, 맞추는 쪽이 꼬리로 견준다.';

-- 한 필드가 여럿을 가리키는 일이 실제로 있다(`StoryController.backgorunds` 가 셋). 그때는 하나를
-- 골라 적으면 거짓이므로 유일 제약을 걸지 않고, 읽는 쪽이 "여럿이라 모호하다"로 다룬다.
CREATE INDEX IF NOT EXISTS idx_scene_object_ref_lookup
    ON scene_object_ref (content_map_id, owner_type, field);
