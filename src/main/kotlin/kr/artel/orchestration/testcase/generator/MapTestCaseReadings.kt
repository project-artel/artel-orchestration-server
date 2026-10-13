package kr.artel.orchestration.testcase.generator

import kr.artel.orchestration.contentmap.evidence.ConditionNode

/**
 * **사전조건의 비교가 QA 실행 중에 확인되는가.**
 *
 * QA 가 게임에서 받는 것은 SDK `pulse` 판독이다. 판독은 근거 문서가 이름 댄 **게임 타입의 멤버
 * 값**과 지금 씬을 준다. 메서드를 불러 보지는 않고, 메서드 안에서만 사는 이름은 볼 수 없다.
 * 실측(지도 2, `pulse` 15,154건)에서 사전조건 비교 가운데 그 밖의 것을 요구한 것이 24건이었다:
 *
 * ```
 * TitleSceneManager.saveLoadController.LoadPlayData() != 0   ← 호출
 * collision.CompareTag("Enemy") != 0                         ← 호출, 지역 변수
 * bigSide == 0                                                ← 지역 변수
 * ```
 *
 * ## 빼지 않고 표시한다
 *
 * 그 비교를 구조에서 빼면 형제가 무너진다. `LoadPlayData() != 0` 과 `== 0` 은 같은 조작의 두
 * 갈래인데, 빼는 순간 같은 전제를 가진 두 줄이 되어 서로 반대되는 결과를 기대한다(실측 9건).
 * 비교는 갈래를 가르는 사실로 남기고, `readable` 을 내려 **판정에서만** 뺀다.
 *
 * ## 이름이 아니라 판독 계약으로 가른다
 *
 * 게임 타입인지는 그 게임의 근거 문서가 말한다([ContentMapRepository.findGameTypeNames]).
 * `Vector3` · `Object` 처럼 엔진 타입은 거기에 없어서 안 읽히는 쪽으로 간다. 특정 메서드 이름을
 * 목록으로 들지 않는다 — 그러면 다음 게임에서는 아무것도 못 가른다.
 */
object MapTestCaseReadings {

    /**
     * 트리의 비교마다 [ConditionNode.Test.readable] 을 매긴다. 모양은 그대로다.
     *
     * @param gameTypes 게임 코드가 선언한 타입들. `Map.MapMove` 처럼 전체 이름이어도 된다.
     */
    fun mark(node: ConditionNode?, gameTypes: Set<String>): ConditionNode? {
        if (node == null) return null
        val roots = gameTypes.mapTo(mutableSetOf(), ::shortName)
        return marked(node, roots)
    }

    /**
     * **그림 기대결과를 `pulse` 가 확인하는가.**
     *
     * `pulse` 는 게임 타입 멤버가 SpriteRenderer 면 그 그림 이름을 싣는다. 그래서 대상이
     * `SlimeAnimator.spriteRenderer.sprite` 처럼 게임 타입에서 시작하는 멤버 경로일 때만 확인된다.
     * `GameObject.Find("Background").GetComponent().sprite` 는 이름으로 찾은 오브젝트에 호출로 닿는
     * 자리라 판독에 없다 — 실측에서 `Background` 표시가 `Stage2BG` 로 갱신된다는 기대가 늘 실패했다.
     *
     * 비교의 한쪽을 가르는 것([mark])과 같은 잣대다. 판독이 읽는 것은 같기 때문이다.
     *
     * @param target 효과가 적은 대상 원문. 사람이 찾을 이름으로 바꾸기 **전** 것을 넘긴다 — 바꾼 뒤에는
     *   호출이 지워져 있다.
     */
    fun seesSprite(target: String, gameTypes: Set<String>): Boolean =
        operand(target.removeSuffix(SPRITE), gameTypes.mapTo(mutableSetOf(), ::shortName))

    /** 그림 기대결과인가. 글자와 같은 `ui-value` 로 오고 대상이 `.sprite` 로 끝난다. */
    fun isSprite(kind: String, target: String?): Boolean =
        kind == UI_VALUE && target?.endsWith(SPRITE) == true

    private const val SPRITE = ".sprite"

    private const val UI_VALUE = "ui-value"

    private fun marked(node: ConditionNode, roots: Set<String>): ConditionNode = when (node) {
        is ConditionNode.Test -> node.copy(readable = operand(node.left, roots) && operand(node.right, roots))
        is ConditionNode.Group -> node.copy(parts = node.parts.map { marked(it, roots) })
        else -> node
    }

    /**
     * 비교의 한쪽이 판독으로 확인되는 값인가.
     *
     * 값 그 자체(숫자 · 문자열 · `null`)거나, 게임 타입에서 시작하는 멤버 경로만 읽힌다.
     * `(MapMove.StagePosition - 1)` 처럼 그것들의 산술도 읽힌다 — 판독이 준 값으로 계산할 수 있다.
     * 경로 뒤에 `(` 가 붙으면 호출이라 안 읽힌다.
     */
    private fun operand(text: String, roots: Set<String>): Boolean {
        // 지금 씬은 판독이 늘 싣는다. 이 자리의 `GetActiveScene()` 은 호출이지만 답이 있다.
        val bare = STRING.replace(ACTIVE_SCENE.replace(text, "\"\""), "\"\"")
        return PATH.findAll(bare).all { path ->
            val name = path.groupValues[1]
            when {
                path.groupValues[2].isNotEmpty() -> false
                name in KEYWORDS -> true
                // 마디 없는 이름은 그 메서드 안에서만 산다. 게임 타입 이름 하나만 있어도 값이 아니다.
                !name.contains('.') -> false
                else -> name.substringBefore('.') in roots
            }
        }
    }

    /** `Combat.Enemies.Enemy/<DeathCounter>d__25` → `Enemy`. 비교는 짧은 이름으로 적힌다. */
    private fun shortName(typeName: String): String =
        typeName.substringBefore('/').substringAfterLast('.')

    /** 이름이나 멤버 경로. 둘째 묶음이 차면 호출이다. 숫자 안의 글자(`1e5`)는 잡지 않는다. */
    private val PATH = Regex("""(?<![\w.])([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)(\s*\()?""")

    private val STRING = Regex(""""[^"]*"|'[^']*'""")

    private val ACTIVE_SCENE = Regex("""SceneManager\.GetActiveScene\(\)\.name""")

    private val KEYWORDS = setOf("null", "true", "false")
}
