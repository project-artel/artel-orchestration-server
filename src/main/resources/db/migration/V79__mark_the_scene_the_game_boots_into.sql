-- 게임을 켜면 열리는 씬을 지도에 적는다 (ARTEL-659).
--
-- 저작이 흐름을 계산할 때 **어디서 시작하는지**를 몰라 아무 자리에서나 출발했다. 씬 그래프는
-- 순환이라(모든 씬이 서로 닿는다) 입구를 구조로는 알 수 없고, 그래서 계산이 "요구가 가장 적은
-- 케이스"에서 시작했다 — 게임의 진실이 아니라 계산의 편의다.
--
-- 대가가 실측(런 233)에 나왔다. 한 시나리오가 `진행도 == 5, 위치 == 0` 에서 시작하라고 적었다.
-- 엔딩까지 다 본 상태로 지도 맨 앞에 서 있으라는 말인데, 아무도 그렇게 게임을 시작하지 않는다.
--
-- **SDK 는 이미 보내고 있다.** `game_build.scene_scan.scenesInBuild` 가 유니티 빌드 설정 순서
-- 그대로이고 0번이 부팅 씬이다. 그것을 지도에 옮겨 적을 뿐이다 — 저작은 지도에게만 묻는다는
-- 규칙(ARTEL-466)이 그대로 산다.
ALTER TABLE scene ADD COLUMN is_entry BOOLEAN NOT NULL DEFAULT FALSE;

-- 한 지도에 입구는 하나다. 둘이면 계산이 어느 쪽에서 시작할지 고르게 되고, 그 고르는 규칙은
-- 아무 데도 적혀 있지 않다.
CREATE UNIQUE INDEX ux_scene_entry_per_content_map
    ON scene (content_map_id) WHERE is_entry;

COMMENT ON COLUMN scene.is_entry IS
    '게임을 켜면 열리는 씬. 유니티 빌드 인덱스 0 에서 왔다 (ARTEL-659).';
