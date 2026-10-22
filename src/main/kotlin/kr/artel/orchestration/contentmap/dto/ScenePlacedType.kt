package kr.artel.orchestration.contentmap.dto

/**
 * 씬에 **놓인 타입** 하나(ARTEL-1209). 판독은 그 씬에 놓인 오브젝트의 멤버만 싣는다.
 *
 * @property sceneName 씬 이름. 씬을 넘어 살아남는 오브젝트(`DontDestroyOnLoad`)는 [ANY_SCENE] 이다
 * @property typeName 타입 이름의 마지막 마디(`MapMove`)
 */
data class ScenePlacedType(
    val sceneName: String,
    val typeName: String,
) {
    companion object {
        const val ANY_SCENE = "*"
    }
}
