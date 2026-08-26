-- V52__point_test_case_at_its_capability.sql
--
-- 케이스가 **자기를 만든 기능**을 가리킨다(ARTEL-553).
--
-- 지금 저작은 케이스와 지도를 문자열로 잇는다. `metadata.source.evidence` 의
-- `Assembly-CSharp|Type|method|sig@offset` 에서 꼬리를 만들어 `capability_evidence.method_id` 에
-- LIKE 로 맞춘다. 그런데 그 꼬리는 **메서드 단위**라 기능 하나를 가리키지 않는다 — 실측(적재기가
-- 앉힌 word-venture 지도)에서 `Map.MapMove|CharacterMove|System.Void()` 하나가 기능 14개를 내고
-- 그 안에 `LeftArrow` 와 `RightArrow` 가 섞여 있다. 한쪽을 검증하는 케이스가 반대쪽 브리지를
-- "이미 하고 있다"며 지운다.
--
-- ## 왜 `capability.id` 가 아니라 `capability_key` 인가
--
-- `id` 는 재적재하면 바뀐다. 지도를 다시 구울 때마다 케이스의 참조가 통째로 끊긴다.
-- `capability_key` 는 `entry_id` 에서 유도되어 재적재를 넘어 같은 값이 나온다 —
-- `v_content_map_capability` 뷰가 그 자리에 이렇게 적어 두었다:
--
--   "재적재를 넘어 살아남는 참조 키. c.id 는 표시·조인용이고 이쪽이 기억해 둘 값이다"
--
-- 그래서 FK 제약을 걸지 않는다. 걸 대상(`capability.id`)이 사라졌다 다시 생기는 값이고, 케이스는
-- 지도보다 오래 살아야 한다. 지도가 없는 동안에도 케이스는 사람이 읽고 실행할 수 있다.
--
-- ## 조회
--
-- 찾을 때는 `(content_map_id, capability_key)` 로 간다. 키만으로는 어느 지도의 것인지 모르고,
-- 한 프로젝트에 capture 가 다른 지도가 여럿 앉는다.
--
-- ## 비어 있는 것이 정상인 경우
--
-- 사람이 손으로 만든 케이스, 엑셀로 적재된 케이스, 그리고 evidence 출신이 아닌 기능(키의 입력인
-- `entry_id` 가 없다). 그래서 NOT NULL 이 아니고, 저작은 **키가 있으면 키를, 없으면 예전 길을**
-- 쓴다(ARTEL-555).

ALTER TABLE test_case
    ADD COLUMN capability_key TEXT;

COMMENT ON COLUMN test_case.capability_key IS
    '이 케이스를 만든 지도 기능의 안정 참조 키(capability.capability_key). '
    '재적재를 넘어 유지된다. NULL 이면 지도에서 나온 케이스가 아니다.';

-- 부분 인덱스인 이유: 지도에서 나오지 않은 케이스가 한동안 다수다. 그 행까지 색인에 넣으면
-- 쓰지 않는 NULL 만 쌓인다.
CREATE INDEX idx_test_case_capability_key
    ON test_case (project_id, capability_key)
    WHERE capability_key IS NOT NULL;
