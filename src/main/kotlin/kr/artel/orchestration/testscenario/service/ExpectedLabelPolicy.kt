package kr.artel.orchestration.testscenario.service

import kr.artel.orchestration.testscenario.dto.ScenarioStep

/**
 * 기대 판정 라벨(`expected_passed`)을 **누가 쓸 수 있는지**에 대한 단일 규칙(ARTEL-301).
 *
 * ## 왜 이 파일이 있나
 *
 * 라벨은 모델 비교용 **정답지**이고, 시나리오 본문과 수명도 소유자도 다르다. 본문은 저작자와 작성
 * 챗봇이 계속 고치지만, 라벨은 사람이 한 번 판단한 값이라 그 판단이 여전히 유효할 때만 살아야 한다.
 *
 * 그런데 시나리오를 저장하는 경로가 셋이다 — 작성 챗봇 반영, FE 자동저장, 승인. **그중 어느 것도
 * 라벨을 모른다.** 라벨을 모르는 클라이언트가 보낸 steps를 그대로 저장하면 그 시나리오의 정답지가
 * 통째로 사라지고, 그것도 스텝을 한 글자 고쳤을 뿐인데 사라진다.
 *
 * 그래서 규칙을 뒤집는다: **일반 쓰기 경로는 라벨을 쓸 수 없다.** 들어온 값이 무엇이든 무시하고
 * 기존 라벨을 얹는다. 라벨을 바꾸는 것은 전용 경로 하나뿐이다. 이러면 "정답지는 내부 도구만
 * 만진다"가 UI 관례가 아니라 서버가 강제하는 규칙이 된다 — 새 클라이언트가 붙어도 그대로 유지된다.
 *
 * ## 왜 같은 자리에 그대로 남은 스텝에만 얹나
 *
 * 라벨은 "이 스텝이 통과해야 하는가"에 대한 사람의 판단이다. 스텝의 행위나 검증 대상 TC가 바뀌었다면
 * 그 판단은 더 이상 그 스텝에 대한 것이 아니다.
 *
 * **위치만 보고 옮기면 안 되는 이유**가 이것이다. 챗봇도 저작자도 스텝을 끼워 넣고 지우고 순서를
 * 바꾸므로, 인덱스만으로 이으면 라벨이 한 칸씩 밀려 엉뚱한 스텝에 달라붙는다. **잘못 달린 라벨은
 * 없는 라벨보다 나쁘다** — 기계가 지어낸 정답지가 되고, 채점은 그것을 사람의 판단으로 믿는다.
 * 살릴 수 있는 것만 살리고 나머지는 미지정으로 돌려 사람이 다시 달게 한다.
 */
object ExpectedLabelPolicy {

    /**
     * [incoming]에 실려 온 라벨을 **버리고** [previous]의 라벨을 같은 자리에 그대로 남은 스텝에만
     * 얹는다. 라벨을 모르는 쓰기 경로 전부가 이것을 통과한다.
     *
     * @param incoming 클라이언트/에이전트가 보낸 새 스텝들. 여기 실린 `expectedPassed`는 신뢰하지
     *   않는다 — 보낸 쪽이 라벨을 볼 수 없었으므로 그 값은 "모른다"의 다른 표현일 뿐이다.
     * @param previous 교체되기 전 저장돼 있던 스텝들.
     */
    fun carryOver(incoming: List<ScenarioStep>, previous: List<ScenarioStep>): List<ScenarioStep> =
        incoming.mapIndexed { index, step ->
            val old = previous.getOrNull(index)
            val carried = if (old != null && old.action == step.action && old.caseId == step.caseId) {
                old.expectedPassed
            } else {
                null
            }
            // copy()로 덮어써서, 들어온 값이 무엇이었든(누가 손으로 넣었든) 여기서 끊긴다.
            step.copy(expectedPassed = carried)
        }

    /**
     * 라벨 **전용** 쓰기. 스텝 번호(1부터)로 지목한 라벨만 갈아끼우고 본문은 건드리지 않는다.
     *
     * 본문과 라벨을 한 요청으로 받지 않는 것이 요점이다. 함께 받으면 라벨링 도구가 본문까지 덮어쓸
     * 수 있고, 그러면 저작자가 방금 고친 스텝이 라벨 저장 한 번에 되돌아간다.
     *
     * 범위를 벗어난 스텝 번호는 조용히 버린다 — 라벨링 화면이 읽은 뒤 저작자가 스텝을 지웠을 수
     * 있고, 그 경합으로 요청 전체를 거절하면 나머지 라벨까지 못 들어간다.
     */
    fun apply(steps: List<ScenarioStep>, labels: Map<Int, Boolean?>): List<ScenarioStep> =
        steps.mapIndexed { index, step ->
            val number = index + 1
            if (labels.containsKey(number)) step.copy(expectedPassed = labels[number]) else step
        }
}
