package kr.artel.orchestration.contentmap.dto

/**
 * 어떤 메서드가 **한 가지 값으로만** 불릴 때, 그 인자에 실리는 값(ARTEL-602).
 *
 * 사전조건이 메서드 매개변수를 이름 그대로 적으면 실행하는 사람이 그 값을 찾을 수 없다 — 실측에서
 * `stagePosition == 1` 이 그랬고, 그것은 `ShowBattle(int)` 의 첫 매개변수다. 게임에 있는 것은
 * `MapMove.StagePosition` 이고 둘은 같은 값인데 이름이 다르다.
 *
 * **부르는 곳마다 값이 다르면 여기 안 나온다.** 그때는 하나를 골라 적는 것이 곧 거짓이라, 모른다고
 * 두는 편이 낫다. 실측에서 인자를 가진 메서드 44개 중 33개가 한 값으로 정해진다.
 *
 * @property capabilityId 그 메서드로 앉은 기능 행. 한 메서드가 갈래마다 여러 행이 되므로 메서드가
 *   아니라 행으로 답한다 — 부르는 쪽이 조건을 볼 때 들고 있는 것이 행 번호다.
 * @property position 몇 번째 인자인가. 조건의 `context` 가 `arg:0` 처럼 적는 그 번호다.
 * @property value 호출자가 넘기는 값. `MapMove.StagePosition` 처럼 지도가 부르는 이름이다.
 */
data class MethodArgument(
    val capabilityId: Long,
    val position: Int,
    val value: String,
)
