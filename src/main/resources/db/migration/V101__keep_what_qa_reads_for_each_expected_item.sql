-- 기대 항목마다 **QA 가 판독에서 무엇으로 확인하나**를 남긴다.
--
-- `expected_items` 는 사람이 읽는 문장이라 씬에서 찾을 수 있는 이름을 쓴다. 판정하는 쪽은 문장이 아니라
-- SDK 판독(`pulse`)을 보고, 판독은 값을 코드 이름으로 싣는다
-- (`ChatWindowController.chatName = {path: "Canvas/ChatWindow/ChatName", label: "나레이터"}`).
-- 문장만 보내면 판정이 문장 속 이름을 판독에서 찾다가 못 찾는다 — 실측 지도 2 에서 18 건.
--
-- 항목과 같은 순서의 배열이다: `[{kind, look_at, field, value, name}, …]`. 지도를 못 되짚는 행은 null.
ALTER TABLE test_case ADD COLUMN expected_checks JSONB;

COMMENT ON COLUMN test_case.expected_checks IS
    'expected_items 와 같은 순서로, QA 가 판독에서 확인할 코드 이름(look_at)·칸(field)·값(value). 사람용 문장과 따로 둔다.';
