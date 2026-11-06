-- `scene` 마다 놓인 오브젝트와, 그 컴포넌트에서 코드가 읽고 쓰는 필드를 남긴다(ARTEL-1059).
--
-- 적재가 `evidence` 의 `objects[]` · `persistentObjects[]` 를 읽고도 `scene_object_ref`(직렬화 참조)만
-- 남기고 오브젝트 자체는 버렸다. planner 가 아직 가 보지 않은 `scene` 의 스크립트를 쓸 때 그 `scene` 에
-- 무엇이 놓였고 어떤 값을 볼 수 있는지 모르면, 오브젝트 이름과 필드 이름을 지어낸다. PoC 는 MinIO 의
-- 원본 문서를 직접 읽어 그렸다(`jev_poc/game_map.py`). 이 표가 그 자리를 `scene-context` 로 옮긴다.
--
-- **`scene` 행이 없는 오브젝트는 담지 않는다.** `scene_object_ref` 와 같다 — `DontDestroyOnLoad` 는
-- `scene` 이 아니라 행이 없고, 어느 `scene` 의 것인지 모르는 오브젝트는 그 `scene` 블록에 그릴 수 없다.
CREATE TABLE IF NOT EXISTS scene_object (
    id             BIGSERIAL PRIMARY KEY,
    content_map_id BIGINT       NOT NULL REFERENCES content_map (id) ON DELETE CASCADE,
    scene_id       BIGINT       NOT NULL REFERENCES scene (id) ON DELETE CASCADE,
    -- `Canvas[2]/continue[2]`. 문서에 selector 가 없으면(`selector-v1` 약속이 없는 문서) path 를 담는다.
    selector       VARCHAR(512) NOT NULL,
    -- 스캔 시점의 활성 상태. 실행 중에는 바뀔 수 있다.
    active         BOOLEAN      NOT NULL,
    components     JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE scene_object IS
    '문서의 objects[] · persistentObjects[] 중 scene 행이 있는 것. 재적재마다 지도 단위로 지우고 다시 넣는다.';
COMMENT ON COLUMN scene_object.components IS
    '[{"type": 타입 이름의 마지막 마디, "fields": [..]}]. fields 는 그 타입 이름으로 시작하는 표현식(Type.field) 중 문서 types 의 조건(condition.left)과 효과(effect.target)에 나온 필드, 이름 오름차순.';

-- `scene-context` 가 지도 하나를 통째로 읽고 `scene` 별로 나눈다.
CREATE INDEX IF NOT EXISTS idx_scene_object_map_scene
    ON scene_object (content_map_id, scene_id);
