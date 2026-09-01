package kr.artel.orchestration.testcase.generator

/**
 * **효과가 가리키는 것을 사람이 찾을 수 있는 이름으로**(ARTEL-615).
 *
 * 기대결과가 코드 표현식을 그대로 부른다 — `ChatWindowController.anyKeyPrompt 의 표시 상태가 바뀐다`.
 * QA 담당자가 게임에서 그 이름을 찾을 수 없다. 하이어라키에 있는 것은
 * `Canvas/ChatWindow/AnyKeyPrompt` 이고, 문서의 직렬화 참조가 그 대응을 이미 말한다.
 *
 * ## 문자열에서 이름을 뽑지 않는다
 *
 * `GameObject.Find("Background")` 에서 `"Background"` 를 꺼내고 싶어진다. 구버전 `specs_v2` 도 그
 * 유혹을 받았고 **하지 않는다** — 그 게임이 그렇게 찾을 뿐이고, 다음 게임은 다르게 찾는다. 규칙이
 * 한 게임에만 맞으면 다른 게임에서는 아무것도 못 찾으면서 "찾을 것이 없었다"고 보고한다.
 *
 * 씬이 스스로 말한 것만 쓴다. 못 찾으면 코드 표현식을 그대로 둔다 — 사람이 코드 이름을 보는 것이,
 * 코드가 상상한 이름을 보는 것보다 낫다.
 *
 * ## 앞부분만 보고 뒷부분은 되붙인다
 *
 * `CombineZone.activateButton.gameObject` 에서 참조가 답하는 것은 `CombineZone.activateButton`
 * 까지다. 뒤의 `.gameObject` 를 버리면 다른 것을 가리키게 되므로 되붙인다.
 *
 * ## 여럿이면 손대지 않는다
 *
 * `StoryController.backgorunds` 가 셋을 가리킨다(실측). 하나를 골라 적으면 나머지 둘일 때 거짓이다.
 * 그때는 코드 이름을 그대로 두는 편이 정직하다.
 */
object MapTestCaseTargets {

    /**
     * @param refs `타입.필드 → 가리키는 이름들`. 이름이 하나로 정해질 때만 바꾼다.
     */
    fun resolve(target: String, refs: Map<Pair<String, String>, Set<String>>): String {
        // 목록 색인은 지운다 — `backgorunds.Item[_]` 이 가리키는 필드는 `backgorunds` 다.
        val cleaned = LIST_ITEM.replace(target, "")
        val owner = cleaned.substringBefore('.', "")
        // 첫 마디가 대문자로 시작해야 타입이다. `i` 나 `collision` 처럼 소문자로 시작하면 그 메서드
        // 안에서만 사는 것이라 씬이 답할 것이 없다.
        if (owner.isEmpty() || !owner.first().isUpperCase()) return target
        val field = cleaned.substringAfter('.', "").substringBefore('.')
        if (field.isEmpty()) return target

        val name = refs[owner to field]?.singleOrNull() ?: return target
        return name + plain(cleaned.removePrefix("$owner.$field"))
    }

    /**
     * **씬이 이름을 답했으면 코드의 꼬리는 뗀다**(ARTEL-682).
     *
     * `wordHead.transform.position` 에서 실행하는 쪽이 찾을 것은 `wordHead` 다. `.transform.position`
     * 은 코드가 그 자리를 가리키는 방법이지 씬에 있는 것이 아니다 — 이름을 풀어 놓고 꼬리를 남기면
     * 반은 씬 말이고 반은 코드 말인 줄이 된다.
     *
     * **자리를 가리키는 꼬리만 뗀다.** `.text` · `.sprite` 처럼 무엇을 보는지 말하는 것은 남긴다 —
     * 그것까지 떼면 "그 글자가 바뀐다"와 "그 그림이 바뀐다"가 같은 문장이 된다.
     */
    private fun plain(tail: String): String =
        if (tail in POSITIONAL) "" else tail

    /** 자리를 가리키는 꼬리. 씬에서 찾을 것은 오브젝트이지 그 성분이 아니다. */
    private val POSITIONAL = setOf(
        ".transform.position", ".transform.localPosition",
        ".transform.localScale", ".transform.scale", ".transform",
    )

    private val LIST_ITEM = Regex("""\.Item\[[^\]]*\]""")
}
