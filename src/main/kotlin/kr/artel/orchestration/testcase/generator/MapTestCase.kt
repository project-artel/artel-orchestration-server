package kr.artel.orchestration.testcase.generator

import kr.artel.orchestration.contentmap.evidence.ConditionNode

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
    /** 사람이 읽는 한 줄. **표시 전용이다** — 되짚을 것은 [condition] 이다(ARTEL-627). */
    val precondition: String,
    /**
     * 사전조건의 구조. 문장으로 렌더하기 **전**의 것이다.
     *
     * 생성기는 이 트리를 늘 손에 들고 있었는데 렌더하고 버렸다. 그래서 소비하는 쪽이 문장을
     * 정규식으로 되읽어야 했고, 거기서 대상의 주인·갈래·식이 사라졌다.
     */
    val condition: ConditionNode? = null,
    val step: String,
    val expected: String,
    val status: String,
    val gaps: List<String> = emptyList(),
    /**
     * 이 케이스를 실행하면 **어느 화면이 되나**(ARTEL-614). 씬 전환이 아니면 `null` 이다.
     *
     * 저작이 브리지를 고를 때 필요하다 — 다음 케이스가 다른 씬에서 시작하면, 거기로 데려다주는
     * 케이스를 찾아야 한다. 지금은 그 답이 기대결과 **산문**에만 있어서 모델이 글을 읽어 맞춰야
     * 하고, 그것이 이 개편이 없애려는 문자열 맞춤이다.
     */
    val arrivesAt: String? = null,
    /**
     * 이 케이스를 **문장과 무관하게** 가리키는 키(ARTEL-617).
     *
     * 앞서 정체는 사용자에게 보이는 네 칸이었다. 그러면 **문장 규칙을 고칠 때마다 정체가 바뀌고**,
     * 옛 줄은 사라진 것으로 판정되어 그것을 인용한 시나리오가 통째로 상한다 — 실측에서 하루에
     * 규칙을 다섯 번 고치자 케이스 18건과 시나리오 3개가 `BROKEN` 이 됐다.
     *
     * 지도 키와 **그 케이스를 낸 효과의 원본**으로 잡는다. 둘 다 문장이 아니라 지도가 정하는 값이라,
     * 문구가 좋아져도 같은 줄로 알아보고 내용만 갱신된다. 대상 이름을 씬이 부르는 것으로 바꾸는
     * 일(ARTEL-615)도 여기에 안 걸린다 — 그때 바뀌는 것은 문장이고 효과 원본은 그대로다.
     */
    val identity: String = "",
    /**
     * 이 조작이 **무엇을 겨누나** — `capability.control_path`. 겨눌 것이 없으면 `null` 이다
     * (키 입력이거나 관측이다).
     *
     * 표시에 쓰는 값이 아니다. [MapTestCaseGenerator] 가 *"바꿔 쓸 수 있는 입력"* 을 한 줄로
     * 담을 때 이것으로 가른다 — 같은 것을 여는 다른 키는 한 줄이고, 다른 버튼은 다른 줄이다.
     */
    val aimedAt: String? = null,
)
