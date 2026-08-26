-- V53__carry_call_edges_on_capability_evidence.sql
--
-- 근거가 **무엇을 부르는지**를 함께 싣는다(ARTEL-554).
--
-- ## 없어서 못 하던 것
--
-- TC 생성기가 조작과 결과를 잇지 못한다. 코루틴·상태 머신에서는 **입력을 받는 갈래와 결과를 내는
-- 갈래가 다른 행**이기 때문이다. 실측(word-venture, StoryScene · EndingScene):
--
--   StoryController.IsAdvanceKeyDown()      입력 있음 · 효과 0     ← 조작 갈래
--   ChatWindowController.UpdateChatStream() 효과 있음 · 입력 0     ← 결과 갈래
--   StoryController.LoadMapScene()          효과 있음 · 입력 0
--
-- 둘을 잇는 것은 **공통 호출자**다. `StoryController.StoryTelling()` 코루틴이 셋을 다 부른다.
-- 그 호출 관계가 근거 문서에는 있는데(`records[].calls[].targetId`) 지도에는 없었다.
--
-- 그래서 두 씬은 TC 가 **0건**이었다. 저작은 케이스가 없으니 무엇을 담을지 모르고, 실측(런 155·158)
-- 에서 실행이 막히던 자리가 하필 그 두 씬이다.
--
-- ## 왜 진입점으로는 안 되나
--
-- `entry_id` 공유로 이어 보았더니 틀린 케이스가 나왔다 — `Map_scene` 에서 `RightArrow` 를 누르면
-- 배경이 바뀐다고 적혔다. 배경은 씬 진입 때 `StageManager.SetBackground()` 가 정하는 것이고,
-- 같은 진입점 아래 있을 뿐 그 조작이 부른 것이 아니다. 진입점은 **갈래의 출처**이지 인과가 아니다.
--
-- 호출 엣지는 실제로 부른 것만 잇는다.
--
-- ## 왜 표가 아니라 칸인가
--
-- 근거 한 줄에 딸린 목록이라 `call_path` · `gaps` 와 성질이 같다. 그 둘이 이미 JSONB 칸이고,
-- 표를 나누면 근거를 읽을 때마다 조인이 하나 더 붙는다. 읽는 쪽은 "이 근거가 무엇을 부르나"를
-- 통째로 원하지 호출 하나를 따로 찾지 않는다.
--
-- 기본값이 빈 배열인 것은 되읽는 쪽이 `null` 을 다루지 않게 하려는 것이다. 아직 적재되지 않은
-- 지도는 "부르는 것이 없다"로 읽히고, 그러면 TC 생성기가 예전처럼 자기 효과만 본다.

ALTER TABLE capability_evidence
    ADD COLUMN calls JSONB NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN capability_evidence.calls IS
    '이 근거가 부르는 메서드들(문서의 records[].calls). 조작 갈래와 결과 갈래를 잇는 유일한 인과다.';
