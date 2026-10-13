-- 직렬화 참조의 **순서 · 주인 · 타입**을 남긴다.
--
-- 근거 문서의 `objects[].components[].refs[]` 는 이 셋을 이미 들고 있는데, 적재기가 `scene_object_ref`
-- 로 옮기며 `owner_type · field · target_name` 만 남기고 버렸다. 그래서 생성기가 두 가지를 못 한다.
--
-- - `SlimeAnimator.sprites[4]` 가 어느 그림인지 못 찾는다. 같은 필드가 그림 8개를 가리키고, 몇 번째인지는
--   목록 순서에만 있다. 주인 오브젝트마다 목록이 다르다(실측 지도 2: 110행).
-- - 기대결과의 대상이 그림(`UnityEngine.Sprite`)인지 오브젝트(`UnityEngine.GameObject`)인지 모른다.
--   SDK `pulse` 는 SpriteRenderer 필드의 그림 이름만 주고 GameObject 필드가 가리키는 오브젝트의 그림은
--   안 준다. 이것을 가르지 못해 `Background` 표시가 `Stage2BG` 로 갱신된다는 기대가 늘 실패했다.
--
-- 이미 적재된 지도는 이 칸이 빈다. `POST /internal/content-maps/{id}/reingest` 로 다시 적재하면 찬다.
ALTER TABLE scene_object_ref
    ADD COLUMN position    INT,
    ADD COLUMN owner_path  VARCHAR(512),
    ADD COLUMN target_type VARCHAR(255),
    ADD COLUMN carries     JSONB NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN scene_object_ref.position IS
    '같은 컴포넌트의 같은 필드 안에서 몇 번째인가(0부터). 목록 필드의 색인과 맞춘다.';
COMMENT ON COLUMN scene_object_ref.owner_path IS
    '이 컴포넌트가 붙은 오브젝트의 하이어라키 경로. 같은 타입이 여럿 놓이면 목록이 주인마다 다르다.';
COMMENT ON COLUMN scene_object_ref.target_type IS
    '가리키는 것의 타입(`UnityEngine.Sprite` · `UnityEngine.GameObject` 등). 판독이 그 값을 주는지 가른다.';
COMMENT ON COLUMN scene_object_ref.carries IS
    '가리키는 프리팹에 실린 컴포넌트 타입들(문서의 refs[].carries).';
