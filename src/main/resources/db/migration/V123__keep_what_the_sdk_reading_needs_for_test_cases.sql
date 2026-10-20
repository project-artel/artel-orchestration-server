-- TC 생성기가 SDK 판독(`pulse`)에 맞춰 케이스를 내는 데 필요한 것을 남긴다.
--
-- ## 1. 직렬화 참조의 순서 · 주인 · 타입
--
-- 근거 문서의 `objects[].components[].refs[]` 는 이 셋을 이미 들고 있는데, 적재기가 `scene_object_ref`
-- 로 옮기며 `owner_type · field · target_name` 만 남기고 버렸다. 그래서 생성기가 두 가지를 못 했다.
--
-- - `SlimeAnimator.sprites[4]` 가 어느 그림인지 못 찾는다. 같은 필드가 그림 8개를 가리키고, 몇 번째인지는
--   목록 순서에만 있다. 주인 오브젝트마다 목록이 다르다(실측 지도 2: 110행).
-- - 기대결과의 대상이 그림(`UnityEngine.Sprite`)인지 오브젝트(`UnityEngine.GameObject`)인지 모른다.
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

-- ## 2. 기대 항목마다 QA 가 판독에서 무엇으로 확인하나
--
-- `expected_items` 는 사람이 읽는 문장이라 씬에서 찾을 수 있는 이름을 쓴다. 판정하는 쪽은 문장이 아니라
-- 판독을 보고, 판독은 값을 코드 이름으로 싣는다
-- (`ChatWindowController.chatName = {path: "Canvas/ChatWindow/ChatName", label: "나레이터"}`).
-- 문장만 보내면 판정이 문장 속 이름을 판독에서 찾다가 못 찾는다 — 실측 지도 2 에서 18 건.
--
-- 항목과 같은 순서의 배열이다: `[{kind, look_at, field, value, name}, …]`. 지도를 못 되짚는 행은 null.
-- 케이스 내용은 버전마다 따로 있으므로(V106) 버전 행에도 같은 칸을 둔다. 문장만 버전을 따르고 구조가
-- 최신이면 판정이 다른 문장의 항목을 본다.
ALTER TABLE test_case ADD COLUMN expected_checks JSONB;
ALTER TABLE test_case_version ADD COLUMN expected_checks JSONB;

COMMENT ON COLUMN test_case.expected_checks IS
    'expected_items 와 같은 순서로, QA 가 판독에서 확인할 코드 이름(look_at)·칸(field)·값(value). 사람용 문장과 따로 둔다.';
COMMENT ON COLUMN test_case_version.expected_checks IS
    'test_case.expected_checks 와 같은 뜻의 그 버전 값.';
