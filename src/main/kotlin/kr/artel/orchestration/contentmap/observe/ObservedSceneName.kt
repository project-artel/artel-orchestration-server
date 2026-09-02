package kr.artel.orchestration.contentmap.observe

/**
 * `pulse` 가 댄 이름을 `scene` 행으로 앉혀도 되나 (ARTEL-689).
 *
 * ## 목록이 아니라 거절 셋이다
 *
 * 이름은 SDK 가 보낸 것이고 대개 진짜다. 아는 이름만 통과시키는 목록을 두면 `evidence` 문서 없이
 * 도는 빌드에서 지도가 다시 통째로 비고, 그것이 이 작업이 고치려는 것이다. 그래서 여기서
 * 막는 것은 셋뿐이고 나머지는 전부 통과한다.
 *
 * **한 게임의 `scene` 이름을 코드에 적지 않는다.** SDK 는 임의의 Unity 게임에 붙으므로, 한 게임에서
 * 뽑은 목록은 다음 게임에서 아무것도 못 거르거나 진짜 `scene` 을 거른다.
 *
 * ## `DontDestroyOnLoad` 만 이름으로 지목한다
 *
 * `evidence` 쪽은 이 문자열을 맞대지 않는다. 문서에 `scenes[]` 배열이 있어 무엇이 `scene` 인지
 * 말해 주고, [kr.artel.orchestration.contentmap.join.PersistentSceneAttribution] 이 그 배열에 없는
 * 이름을 거른다(ARTEL-460). `pulse` 에는 그 배열이 없다 — 무엇이 `scene` 인지 말해 주는 것이 이
 * 이름 하나뿐이라 구조로 거를 재료가 없고, 그래서 이 경로에서만 이름을 적는다.
 *
 * `DontDestroyOnLoad` 는 Unity 가 `scene` load 를 넘어 살아남는 오브젝트를 모아 두는 자리이고
 * 아무도 그리로 갈 수 없다. ARTEL-460 이 지도에서 그 행을 없앤 이유가 그것이라, 관측이 도로
 * 앉히면 사전조건이 "`DontDestroyOnLoad` `scene` 이 실행 중이다" 인 TC 가 다시 생긴다. 실측
 * 문서에서 capability 469 개 중 64 개가 그 행에 앉아 있었다.
 *
 * ## 나머지 둘은 행으로 앉힐 수 없는 값이다
 *
 * 빈 이름은 `scene` 을 지목하지 않으므로 지목할 대상이 없고, 255 자를 넘는 이름은
 * `scene.name VARCHAR(255)`(V40) 에 안 들어간다. 길이를 여기서 보는 것은 `INSERT` 가 던지는 것보다
 * 이유가 남는 쪽이 낫기 때문이다.
 */
object ObservedSceneName {

    /** `scene.name` 의 폭(V40). */
    private const val MAX_LENGTH = 255

    /** Unity 가 `scene` load 를 넘어 살아남는 오브젝트를 모아 두는 자리. `scene` 이 아니다. */
    private const val DONT_DESTROY_ON_LOAD = "DontDestroyOnLoad"

    /**
     * 만들지 않을 이유. 만들어도 되면 null 이다.
     *
     * 사유를 문자열로 돌려주는 것은 로그에 그대로 실으려는 것이다. 무엇이 거절됐는지만 남고 왜
     * 거절됐는지가 안 남으면, 지도가 비어 있는 이유를 나중에 아무도 못 찾는다.
     */
    fun refusalReason(name: String): String? = when {
        name.isBlank() -> "이름이 비어 있다"
        name == DONT_DESTROY_ON_LOAD -> "$DONT_DESTROY_ON_LOAD 는 scene 이 아니라 살아남은 오브젝트가 모이는 자리다"
        name.length > MAX_LENGTH -> "이름이 ${name.length}자로 scene.name 의 ${MAX_LENGTH}자를 넘는다"
        else -> null
    }
}
