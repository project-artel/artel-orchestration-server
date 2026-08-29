package kr.artel.orchestration.contentmap.observe

import kr.artel.orchestration.contentmap.entity.SceneScreenSelectorEntity
import kr.artel.orchestration.contentmap.entity.ScreenSelectorMatch
import kr.artel.orchestration.contentmap.entity.ScreenSelectorSource

/**
 * 한 씬에서 화면을 식별하는 selector 목록 (ARTEL-654). `discriminator` 는 이 목록에 맞는 것만 담는다.
 *
 * ## 목록 밖은 무시한다
 *
 * 처음 보는 selector 도 무시한다. 그것이 이 클래스가 존재하는 이유다 — 기본값이 넣는 쪽이면
 * 런타임에 생기는 것이 하나 뜰 때마다 `discriminator` 가 바뀌어 새 화면이 되고, 게임이 오브젝트
 * 이름에 카운터를 넣으면(`agent(1)` · `agent(2)`) 끝이 없다. 실측 `TurnBattleScene` 이 화면 29행까지
 * 올라 [ScreenObservationService.MAX_SCREENS_PER_SCENE] 32 코앞이었다.
 *
 * 무엇을 뺄지를 기계가 알아내는 방향은 후보 셋을 다 재봤고 셋 다 반례가 있었다 — 마이그레이션
 * `V60__whitelist_screen_defining_selectors.sql` 의 머리말에 표로 적었다. 새 기계 추정을 더 만드는
 * 것이 이 규칙이 멈추려는 일이므로, 여기에 휴리스틱을 더하지 않는다.
 *
 * ## 목록이 비면 화면이 하나다
 *
 * [defines] 가 늘 `false` 를 내고 `discriminator` 가 빈 배열이 되어, 그 씬의 관측이 전부 화면 한
 * 행에 앉는다. **오류가 아니다.** 가를 근거가 하나도 없는데 가르는 것보다 맞다. 씨앗
 * (`capability.control_selector`)이 그 상태를 드물게 만들지만, 씨앗이 없는 씬은 정상적으로 화면
 * 하나로 산다.
 *
 * ## 항목을 더해도 소급되지 않는다
 *
 * 이미 뭉쳐 있던 과거 화면은 안 갈린다. 빠진 selector 가 애초에 `discriminator` 에 안 들어갔으니
 * 기록이 없어서 복원할 수 없다. **다음 관측부터** 갈린다. 반대 방향(항목을 빼는 것)은 소급해서
 * 합칠 수 있고 그것은 ARTEL-655 다.
 *
 * ## SQL 에 같은 규칙이 있다
 *
 * `V60__whitelist_screen_defining_selectors.sql` 의 `screen_defining_selector(scene_id, selector)`
 * 가 [defines] 와 **같은 답을 내야 한다.** 갈리면 소급 처리가 합친 화면과 런타임이 앉히는 화면이
 * 다른 규칙을 따르게 되어, `uk_screen_discriminator`(V59) 가 막으려던 분열이 규칙 쪽에서 다시
 * 열린다. 항목에 정규식을 저장하지 않는 것이 그 위험을 줄이는 첫 번째 장치다 — `java.util.regex`
 * 와 POSIX ARE 는 다르고, 한쪽에서만 맞는 항목이 하나 생기면 그것으로 충분하다.
 */
class ScreenSelectorWhitelist(private val rules: List<ScreenSelectorRule>) {

    /**
     * 이 selector 가 화면을 식별하는가.
     *
     * 맞는 항목이 여럿이면 **출처가 먼저**, 그 다음 좁은 것이 이긴다. 순서는 SQL 쪽
     * `screen_defining_selector` 의 `ORDER BY` 와 같다:
     *
     * 1. 출처 — human > agent > static-analysis ([ScreenSelectorSource.rank])
     * 2. 대상의 좁기 — selector > path > subtree ([ScreenSelectorMatch.specificity])
     * 3. `subtree` 끼리는 긴 pattern 이 좁다 (`A/B` 가 `A` 를 이긴다)
     * 4. 그래도 같으면 나중에 쓴 행. 여기까지 오는 경우는 없다 — `uk_scene_screen_selector` 가
     *    (kind, pattern, source) 를 하나로 묶고, 길이가 같은 서로 다른 `subtree` pattern 둘이 한
     *    경로에 동시에 맞을 수는 없다. 그래도 못박는 것은 SQL 과 답이 갈릴 자리를 남기지 않기
     *    위해서다
     *
     * 맞는 항목이 하나도 없으면 `false`.
     */
    fun defines(selector: String): Boolean {
        val path = indexFreePathOf(selector)
        return rules.asSequence()
            .filter { it.matches(selector, path) }
            .maxWithOrNull(PRECEDENCE)
            ?.screenDefining
            ?: false
    }

    /**
     * 이 selector 에 대해 이미 답이 있나 (ARTEL-655).
     *
     * [defines] 와 다르다. `defines` 는 "화면을 가르나" 이고 이것은 "**물어볼 필요가 있나**" 다.
     * 명시적 제외 항목(`screen_defining=false`)은 `defines` 가 `false` 를 내지만 이미 답이 있는
     * 것이므로 다시 묻지 않는다. 이 둘을 같은 함수로 하면 "안 가른다" 는 답을 받은 selector 가
     * 나타날 때마다 제안이 다시 나가고, 그것이 이 기능이 막으려던 바로 그 반복이다.
     */
    fun covers(selector: String): Boolean {
        val path = indexFreePathOf(selector)
        return rules.any { it.matches(selector, path) }
    }

    companion object {
        /** 항목이 하나도 없는 목록. 그 씬은 화면이 하나다 — 오류가 아니다(클래스 주석). */
        val EMPTY = ScreenSelectorWhitelist(emptyList())

        private val PRECEDENCE: Comparator<ScreenSelectorRule> = compareBy(
            { it.source.rank },
            { it.match.specificity },
            { it.pattern.length },
            { it.id },
        )
    }
}

/**
 * 목록 항목 하나. `scene_screen_selector` 한 행이 그대로 이것이다.
 *
 * [id] 는 우선순위의 마지막 못이다([ScreenSelectorWhitelist.defines] 의 4번).
 */
data class ScreenSelectorRule(
    val match: ScreenSelectorMatch,
    val pattern: String,
    val source: ScreenSelectorSource,
    val screenDefining: Boolean,
    val id: Long = 0,
) {
    /**
     * [selector] 는 원문이고 [path] 는 형제 index 를 지운 것이다. 지우기를 호출자가 한 번만 하도록
     * 밖에서 받는다 — 항목마다 다시 지우면 목록 길이만큼 정규식이 돈다.
     *
     * [ScreenSelectorMatch.SUBTREE] 는 **마디 경계**로만 맞는다. `startsWith(pattern)` 로 하면
     * `CombineSystem/CombineZone/Zone1` 이 `.../Zone1Extra` 에 걸리고, 실측에서 `Zone1` 과 `Zone2`
     * 는 끝자리가 숫자여도 서로 다른 오브젝트였다.
     */
    fun matches(selector: String, path: String): Boolean = when (match) {
        ScreenSelectorMatch.SELECTOR -> pattern == selector
        ScreenSelectorMatch.PATH -> pattern == path
        ScreenSelectorMatch.SUBTREE -> path == pattern || path.startsWith("$pattern/")
    }
}

/**
 * DB 행을 목록 항목으로.
 *
 * 모르는 `match_kind` · `source` 는 흘린다. `CHECK` 가 막지만, 막지 못한 값이 들어왔을 때 읽는 쪽이
 * 죽는 것보다 그 항목 하나가 없는 것이 낫다 — 화면 적재는 `pulse` 를 끊지 않는다는 규율이
 * `ScreenObservationService.observe` 에 이미 있다.
 */
fun SceneScreenSelectorEntity.toRule(): ScreenSelectorRule? {
    val match = ScreenSelectorMatch.from(matchKind) ?: return null
    val source = ScreenSelectorSource.from(source) ?: return null
    return ScreenSelectorRule(match, pattern, source, screenDefining, id ?: 0)
}
