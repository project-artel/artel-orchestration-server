package kr.artel.orchestration.testcase.generator

/**
 * 지도가 낸 케이스 하나(ARTEL-554).
 *
 * **사용자에게 보이는 것은 셋뿐이다** — [precondition] · [step] · [expected]. 나머지는 저작과 화면이
 * 쓰는 부속이다. 구버전 생성기의 CSV 는 16칸이었지만 그중 사람이 읽는 것은 이 셋이고, 그래서 이
 * 생성기가 책임지는 것도 셋이다.
 *
 * @property capabilityKey 이 케이스를 만든 지도 기능의 **안정 참조 키**(ARTEL-553). 재적재를 넘어
 *   같은 값이 나오므로 저작이 이것으로 지도를 되짚는다. 문자열 맞춤이 사라지는 자리다.
 * @property scene 이 케이스가 시작하는 화면.
 * @property status `ready` · `candidate` · `review` — 얼마나 믿을 수 있나. **저작 판단에는 쓰지
 *   않는다**(모델 자평 순환 회피). 화면 표시용이다.
 * @property gaps 이 등급이 나온 이유 코드. 비어 있으면 걸린 것이 없다.
 */
data class MapTestCase(
    val capabilityKey: String,
    val scene: String,
    val precondition: String,
    val step: String,
    val expected: String,
    val status: String,
    val gaps: List<String> = emptyList(),
)
