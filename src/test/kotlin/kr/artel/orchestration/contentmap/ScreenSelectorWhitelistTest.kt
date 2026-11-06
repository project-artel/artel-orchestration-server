package kr.artel.orchestration.contentmap

import kr.artel.orchestration.contentmap.entity.ScreenSelectorMatch
import kr.artel.orchestration.contentmap.entity.ScreenSelectorSource
import kr.artel.orchestration.contentmap.observe.ScreenSelectorRule
import kr.artel.orchestration.contentmap.observe.ScreenSelectorWhitelist
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 화면 판정에 쓸 selector 목록의 평가 규칙 (ARTEL-654).
 *
 * 이 파일이 지키는 것은 셋이다.
 *
 * 1. **기본값** — 목록에 없는 selector 는 처음 보는 것이어도 안 들어간다. 목록이 비면 아무것도
 *    안 들어간다
 * 2. **대상 셋** — selector 원문 하나 · 형제 index 를 지운 경로 하나 · 그 경로와 그 아래 전부.
 *    마지막 것은 **마디 경계**로만 맞는다
 * 3. **우선순위** — 사람 > agent > 정적 분석, 같은 출처 안에서는 좁은 것 > 넓은 것
 *
 * SQL 쪽 `screen_defining_selector` 가 같은 답을 내는지는 `ScreenObservationTest` 가 실측 selector
 * 전체를 양쪽에 통과시켜 본다. 여기는 규칙 자체를 고정한다.
 */
class ScreenSelectorWhitelistTest {

    private fun rule(
        match: ScreenSelectorMatch,
        pattern: String,
        source: ScreenSelectorSource = ScreenSelectorSource.STATIC_ANALYSIS,
        screenDefining: Boolean = true,
        id: Long = 0,
    ) = ScreenSelectorRule(match, pattern, source, screenDefining, id)

    /**
     * **기본값이 뒤집힌 자리다.** 목록에 없으면 무시한다.
     *
     * 여기가 깨지면 화면 수가 실제 상태 수가 아니라 플레이 길이에 비례한다 — 런타임에 생기는 것이
     * 하나 뜰 때마다 `discriminator` 가 바뀌기 때문이다. 실측 `TurnBattleScene` 이 그래서 29행이었다.
     */
    @Test
    fun `목록에 없는 selector 는 처음 보는 것이어도 안 들어간다`() {
        val whitelist = ScreenSelectorWhitelist(
            listOf(rule(ScreenSelectorMatch.SELECTOR, "Canvas[2]/continue[1]"))
        )

        assertThat(whitelist.defines("Canvas[2]/continue[1]")).isTrue
        assertThat(whitelist.defines("Card(Clone)[37]")).isFalse
        assertThat(whitelist.defines("agent(1)")).isFalse
    }

    /** 목록이 빈 씬은 화면이 하나다. **오류가 아니다** — 가를 근거가 없는데 가르는 것보다 맞다. */
    @Test
    fun `목록이 비면 아무것도 화면을 식별하지 않는다`() {
        assertThat(ScreenSelectorWhitelist.EMPTY.defines("Canvas[2]/continue[1]")).isFalse
    }

    /** `selector` 항목은 원문 하나만 가리킨다. 형제 index 가 다르면 다른 것이다. */
    @Test
    fun `selector 항목은 원문 하나만 맞는다`() {
        val whitelist = ScreenSelectorWhitelist(
            listOf(rule(ScreenSelectorMatch.SELECTOR, "Card(Clone)[37]"))
        )

        assertThat(whitelist.defines("Card(Clone)[37]")).isTrue
        assertThat(whitelist.defines("Card(Clone)[38]")).isFalse
    }

    /** `path` 항목은 **경로 모든 마디**의 형제 index 를 지운 값과 맞댄다. */
    @Test
    fun `path 항목은 형제 index 를 지운 경로에 맞는다`() {
        val whitelist = ScreenSelectorWhitelist(
            listOf(rule(ScreenSelectorMatch.PATH, "Card(Clone)/Cost"))
        )

        assertThat(whitelist.defines("Card(Clone)[37]/Cost[0]")).isTrue
        // 스폰되는 것이 잎이라는 보장이 없다. 부모 쪽 index 가 흔들려도 같은 항목에 맞아야 한다.
        assertThat(whitelist.defines("Card(Clone)[38]/Cost[0]")).isTrue
        assertThat(whitelist.defines("Card(Clone)[37]")).isFalse
    }

    /**
     * `subtree` 항목은 **마디 경계**로만 맞는다.
     *
     * `contains` 나 맨 `startsWith` 로 하면 `CombineSystem/CombineZone/Zone1` 이 `Zone1Extra` 에
     * 걸린다. 실측에서 `Zone1` 과 `Zone2` 는 끝자리가 숫자여도 서로 다른 오브젝트였고, 그 둘이
     * 실제로 화면을 가른 넷 중 둘이다.
     */
    @Test
    fun `subtree 항목은 마디 경계로만 맞는다`() {
        val whitelist = ScreenSelectorWhitelist(
            listOf(rule(ScreenSelectorMatch.SUBTREE, "CombineSystem/CombineZone"))
        )

        assertThat(whitelist.defines("CombineSystem[7]/CombineZone[1]")).isTrue
        assertThat(whitelist.defines("CombineSystem[7]/CombineZone[1]/Zone1[0]")).isTrue
        assertThat(whitelist.defines("CombineSystem[7]/CombineZone[1]/Button[2]/Label[0]")).isTrue

        assertThat(whitelist.defines("CombineSystem[7]/CombineZoneExtra[1]")).isFalse
        assertThat(whitelist.defines("Other[0]/CombineSystem[7]/CombineZone[1]")).isFalse
    }

    /**
     * 사람이 agent 를 이기고 agent 가 정적 분석을 이긴다.
     *
     * 출처가 `uk_scene_screen_selector` 의 키에 들어 있어 세 행이 함께 산다. 덮어쓰기가 아니라
     * 이기기라서, 사람 항목을 지우면 agent 의 판단이 되살아난다.
     */
    @Test
    fun `사람이 agent 를 이기고 agent 가 정적 분석을 이긴다`() {
        val selector = "Canvas[2]/spinner[0]"
        val static = rule(ScreenSelectorMatch.SELECTOR, selector, ScreenSelectorSource.STATIC_ANALYSIS, true, 1)
        val agent = rule(ScreenSelectorMatch.SELECTOR, selector, ScreenSelectorSource.AGENT, false, 2)
        val human = rule(ScreenSelectorMatch.SELECTOR, selector, ScreenSelectorSource.HUMAN, true, 3)

        assertThat(ScreenSelectorWhitelist(listOf(static)).defines(selector)).isTrue
        assertThat(ScreenSelectorWhitelist(listOf(static, agent)).defines(selector)).isFalse
        // 순서를 뒤집어도 결과가 같아야 한다 — 목록의 나열 순서가 판정에 끼면 안 된다.
        assertThat(ScreenSelectorWhitelist(listOf(human, agent, static)).defines(selector)).isTrue
        assertThat(ScreenSelectorWhitelist(listOf(static, agent, human)).defines(selector)).isTrue
    }

    /**
     * 같은 출처 안에서는 좁은 것이 넓은 것을 이긴다.
     *
     * 이것이 `screen_defining=false` 가 필요한 이유다 — `subtree` 로 패널을 통째로 넣고 그 아래
     * 하나만 빼는 것이 이 규칙 없이는 표현되지 않는다.
     */
    @Test
    fun `같은 출처에서는 좁은 것이 넓은 것을 이긴다`() {
        val panel = rule(ScreenSelectorMatch.SUBTREE, "CombineSystem/CombineZone", screenDefining = true, id = 1)
        val holeByPath = rule(ScreenSelectorMatch.PATH, "CombineSystem/CombineZone/Spinner", screenDefining = false, id = 2)
        val whitelist = ScreenSelectorWhitelist(listOf(panel, holeByPath))

        assertThat(whitelist.defines("CombineSystem[7]/CombineZone[1]/Zone1[0]")).isTrue
        assertThat(whitelist.defines("CombineSystem[7]/CombineZone[1]/Spinner[3]")).isFalse
    }

    /** `subtree` 끼리는 긴 pattern 이 좁다. `A/B` 가 `A` 를 이긴다. */
    @Test
    fun `subtree 끼리는 긴 pattern 이 이긴다`() {
        val wide = rule(ScreenSelectorMatch.SUBTREE, "CombineSystem", screenDefining = true, id = 1)
        val narrow = rule(ScreenSelectorMatch.SUBTREE, "CombineSystem/CombineZone", screenDefining = false, id = 2)
        val whitelist = ScreenSelectorWhitelist(listOf(wide, narrow))

        assertThat(whitelist.defines("CombineSystem[7]/CombineButton[0]")).isTrue
        assertThat(whitelist.defines("CombineSystem[7]/CombineZone[1]/Zone1[0]")).isFalse
    }
}
