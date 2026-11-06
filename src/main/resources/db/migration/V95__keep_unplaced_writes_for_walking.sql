-- 씬 귀속에 실패한 unplaced 타입의 상태 변경(write/active-state)을 지도 수준으로 남긴다.
--
-- 왜: 걷기(나누기 완화·ARTEL-625)는 "이 값이 게임 안에서 움직이나"를 capability 의 효과에서
-- 읽는데, capability 는 씬이 필수라 귀속 실패한 타입(실측: Tutorial.TutorialController)의
-- write 가 통째로 사라진다. 그 값(waitingForAcknowledge)은 토글인데 얼어붙은 값으로 읽혀,
-- 한 흐름에서 번갈아 참이 되는 케이스들이 모순으로 오판되어 나뉘었다(run 60 실측).
-- 이 표는 그 지식의 소극적 용도(덜 나눔) 전용이다 — 씬이 없어 조작 근거로는 쓰지 않는다.
CREATE TABLE content_map_stray_effect (
    id BIGSERIAL PRIMARY KEY,
    content_map_id BIGINT NOT NULL REFERENCES content_map (id) ON DELETE CASCADE,
    kind VARCHAR(32) NOT NULL,
    target TEXT NOT NULL,
    detail TEXT,
    source_type TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_stray_effect_map ON content_map_stray_effect (content_map_id);
