-- 되돌아가는 갈래를 근거에 싣는다 (ARTEL-613).
--
-- 사전조건에 `i >= 총개수` 같은 루프 카운터가 온다. 실행하는 사람은 `i` 를 읽을 수 없으므로 그것은
-- 전제가 못 되지만, **만들 수는 있다** — 같은 조작을 끝까지 되풀이하면 반드시 그 자리에 닿는다.
-- 지우면 "아무 키나 한 번 누르면 타이틀로 간다"가 되어 거짓이고, 스텝으로 옮기면 할 수 있는 일이 된다.
--
-- 그 판정에 필요한 것이 `loopsBackTo` 다. 문서에 있고 파서도 읽는데 앉히는 곳이 없어서,
-- `capability.repeat_until_done` 이 실측 491행 내내 false 였다. `calls` 와 같은 모양의 누락이다.
--
-- **기능이 아니라 근거에 둔다.** `capability.repeat_until_done` 은 조작이 있는 행에만 설 수 있고
-- (`ck_capability_repeat_needs_interaction`), 정작 루프를 도는 것은 코루틴이라 `interaction='none'`
-- 이다. 조작은 그 코루틴을 부르는 입력 갈래에 있고, 둘을 잇는 것은 `calls` 다. 갈래의 사실이므로
-- 갈래에 앉힌다.
ALTER TABLE capability_evidence
    ADD COLUMN loops_back_to INTEGER;

COMMENT ON COLUMN capability_evidence.loops_back_to IS
    '되돌아가는 지점의 IL 위치(문서의 records[].loopsBackTo). 이 갈래의 가드를 뒤집으면 "다 돌고 나온 자리"이고, 그 조건은 지울 것이 아니라 반복 스텝으로 옮길 것이다.';
