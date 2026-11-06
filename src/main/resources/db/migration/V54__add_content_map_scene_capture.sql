-- 씬 대표 이미지. 근거 문서 등록에 실려 온 캡처 결과를 문서 단위로 담는다.
--
-- scene 에 바로 쓰지 않고 표를 따로 두는 이유: 등록과 적재가 따로 돌아 등록 시점에는 scene 행이
-- 아직 없을 수 있다. 문서에 매달아 두면 적재가 끝난 뒤 그 문서의 캡처를 다시 찾아 옮길 수 있다.
--
-- CHECK 이 성공과 실패를 가른다. 이미지가 있는데 실패 이유도 있는 행은 화면이 무엇을 믿을지
-- 정할 수 없으므로 DB 가 아예 앉히지 않는다.
CREATE TABLE IF NOT EXISTS content_map_scene_capture (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES content_map_document (id) ON DELETE CASCADE,
    scene_name VARCHAR(255) NOT NULL,
    object_key VARCHAR(512),
    content_type VARCHAR(64),
    width INT,
    height INT,
    failure_code VARCHAR(64),
    captured_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_content_map_scene_capture UNIQUE (document_id, scene_name),
    CONSTRAINT ck_content_map_scene_capture_result CHECK (
        (object_key IS NOT NULL AND content_type IS NOT NULL AND width > 0 AND height > 0 AND failure_code IS NULL)
        OR
        (object_key IS NULL AND content_type IS NULL AND width IS NULL AND height IS NULL AND failure_code IS NOT NULL)
    )
);

ALTER TABLE scene
    ADD COLUMN IF NOT EXISTS image_width INT,
    ADD COLUMN IF NOT EXISTS image_height INT,
    ADD COLUMN IF NOT EXISTS image_captured_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS image_failure_code VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_content_map_scene_capture_document
    ON content_map_scene_capture (document_id);
