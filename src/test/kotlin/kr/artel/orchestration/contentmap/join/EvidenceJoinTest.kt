package kr.artel.orchestration.contentmap.join

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.contentmap.entity.EvidenceGap
import kr.artel.orchestration.contentmap.entity.Interaction
import kr.artel.orchestration.contentmap.evidence.EvidenceParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 조인 다섯 단계를 이어 붙인 결과를 골든 문서로 고정한다.
 *
 * 단계별 테스트는 각자 자기 규칙만 본다. 여기서 보는 것은 **순서와 우선순위** — 배선이 있으면
 * 컨트롤이 주소이고, 없으면 오너 타입의 씬으로 떨어지며, 프리팹 위 타입은 스폰으로 붙되 조작인
 * 척하지 않는다는 것.
 *
 * 수치를 박아 두는 이유: 문서가 바뀌면 이 테스트가 깨져야 조인 규칙을 다시 보게 된다. 숫자만
 * 고치는 사람이 없도록 KDoc 에 **왜 그 수인지**를 함께 적는다.
 */
class EvidenceJoinTest {

    private val join = EvidenceJoin(document)

    /**
     * 후보는 하나도 빠짐없이 씬을 든다.
     *
     * 씬 없는 후보는 `given` 자리가 통째로 빈 명세라 TC 가 어디서 시작할지 말할 수 없다. 씬을 못
     * 찾은 레코드는 후보가 되지 않고 [EvidenceJoin.unaddressedRecords] 로만 세어진다.
     */
    @Test
    fun `모든 후보가 씬을 든다`() {
        val candidates = join.candidates()

        assertThat(candidates).isNotEmpty
        assertThat(candidates).allSatisfy { assertThat(it.scene).isNotBlank() }
        assertThat(candidates.map { it.scene }.distinct()).isSubsetOf(document.scenes)
    }

    /**
     * 배선이 있으면 컨트롤이 주소다.
     *
     * `Canvas/continue` 는 `alsoReachedBy` 를 펴야만 걸리는 자리다(레코드의 `entry` 는
     * `InitPlayerData()` 이고 실제로 물린 것은 `LoadStoryScene()`). 안 펴면 이 버튼이 통째로
     * 사라지므로, 후보 목록에 그 경로가 살아 있는지로 arrivals 단계를 지킨다.
     */
    @Test
    fun `배선된 컨트롤이 후보의 주소가 된다`() {
        val wired = join.candidates().filter { it.binding != null }

        assertThat(wired).isNotEmpty
        assertThat(wired.map { it.binding!!.placement.path }).contains("Canvas/continue")
        // 배선으로 온 후보는 클릭이거나(컨트롤을 누르는 것 자체가 조작) 키 입력이다. 조작 없음이
        // 섞이면 컨트롤을 찾아 놓고 누를 방법을 잃은 것이다.
        assertThat(wired.map { it.interaction }).doesNotContain(Interaction.NONE.wire)
    }

    /**
     * 배선이 없는 키 입력은 오너 타입이 놓인 씬으로 떨어진다.
     *
     * 키는 컨트롤에 물리지 않아 배선이 구조적으로 없다. 이 길이 없으면 `Map.MapMove` 의 방향키
     * 조작이 전부 사라진다 — 실측에서 `either` 가 입력을 가르는 레코드가 여기 몰려 있다.
     */
    @Test
    fun `키 입력은 배선 없이 오너의 씬에서 성립한다`() {
        val presses = join.candidates().filter { it.interaction == Interaction.PRESS.wire }

        assertThat(presses).isNotEmpty
        assertThat(presses).allSatisfy { assertThat(it.inputKey).isNotNull() }
        // 한 갈래에 키 하나. `either` 를 쪼개지 않으면 여기서 두 키가 한 후보에 남는다.
        assertThat(presses.map { it.inputKey }).doesNotContain(null)
        assertThat(presses.filter { it.record.owner == "Map.MapMove" }.map { it.inputKey })
            .contains("LeftArrow", "DownArrow")
    }

    /**
     * 프리팹 위에만 사는 타입은 스폰으로 붙되 **조작인 척하지 않는다.**
     *
     * 만드는 쪽의 경로를 조준 대상으로 내주면 TC 가 카드 매니저를 눌러 카드가 뒤집혔다고 적는다.
     * 스키마도 같은 것을 `ck_capability_spawn_has_no_control` 로 막는다(ARTEL-484).
     */
    @Test
    fun `스폰으로 붙은 후보는 조작을 갖지 않는다`() {
        val spawned = join.candidates().filter { it.spawn != null }

        assertThat(spawned).isNotEmpty
        assertThat(spawned).allSatisfy {
            assertThat(it.interaction).isEqualTo(Interaction.NONE.wire)
            assertThat(it.binding).isNull()
            assertThat(it.inputKey).isNull()
        }
        // 실측: createdBy 가 찬 unplaced 10타입. 그 무게가 전투 씬 대부분이다.
        assertThat(spawned.map { it.record.owner }.distinct()).hasSize(10)
        assertThat(spawned.map { it.scene }).contains("TurnBattleScene")
    }

    /**
     * 한 씬에서 만드는 쪽 후보가 둘 이상이면 고르지 않고 사유를 남긴다.
     *
     * 컬럼은 단일 값이라 하나를 집으면 고른 쪽이 근거가 없고, 조용히 비우면 "여럿이라 못 정했다"와
     * "원래 없다"가 구분되지 않는다. 실측에서 `Cards.Card` 계열이 `GameClearScene` 에서 이 상태가
     * 된다(`magicCard` 와 `spellCard` 가 같은 프리팹 목록을 싣는다).
     */
    @Test
    fun `스폰 후보가 갈리면 사유를 남긴다`() {
        val ambiguous = join.candidates().filter { it.spawn?.ambiguous == true }

        assertThat(ambiguous).isNotEmpty
        assertThat(ambiguous).allSatisfy {
            assertThat(it.spawn!!.field).isNull()
            assertThat(it.spawn!!.scenePath).isNull()
            assertThat(it.gaps).contains(EvidenceGap.SPAWN_ORIGIN_AMBIGUOUS.wire)
        }
        // 정확히 귀속된 자리는 그대로 남는다 — 한 씬이 흐리다고 다른 씬의 좋은 귀속까지 버리지 않는다.
        val resolved = join.candidates().filter { it.spawn?.ambiguous == false }
        assertThat(resolved.mapNotNull { it.spawn!!.field }.distinct()).isNotEmpty
    }

    /**
     * 근거가 남긴 공백은 버리지 않고 그대로 올라온다.
     *
     * 이 값이 있으면 조건을 단정하면 안 된다는 뜻이라, 적재가 판단할 재료가 여기서 사라지면 안 된다.
     * `subject-null` 은 조인이 판정해 더하는 쪽이다(실측 `context: null` 47건).
     */
    @Test
    fun `근거의 공백과 조인이 판정한 공백이 함께 실린다`() {
        val gaps = join.candidates().flatMap { it.gaps }.distinct()

        assertThat(gaps).contains(
            EvidenceGap.SUBJECT_NULL.wire,
            EvidenceGap.CALLEE_CONDITION_NOT_COMPOSED.wire,
            EvidenceGap.UNREAD_CONDITION.wire,
        )
        // 문서가 준 토큰 중 우리가 어휘로 모르는 것도 그대로 통과한다. 모르는 토큰도 "단정하지
        // 말라"는 말이라, 거르면 그 경고가 사라진다.
        assertThat(gaps).contains("input-not-branched")
    }

    /**
     * 죽은 코드 판정은 캡처 한 장으로 내리지 않는다.
     *
     * 후보는 셋이고, 그중 `Combat.Spells.SpellObj` 는 devbuild 캡처에서 살아 있다. `Cards.Util` 은
     * `createdBy` 가 비었어도 `calledBy` 가 차 있어 후보가 아니다 — 이 한 칸을 안 보면 살아 있는
     * 타입을 지운다.
     */
    @Test
    fun `죽은 코드는 후보로만 남고 조인 결과에 들어가지 않는다`() {
        val deadCandidates = join.deadCodeCandidates()

        assertThat(deadCandidates).containsExactlyInAnyOrder(
            "Scenes.Test.RemoteControlPoCController",
            "Scenes.Test.TrackingTest",
            "Combat.Spells.SpellObj",
        )
        assertThat(deadCandidates).doesNotContain("Cards.Util")
        assertThat(join.candidates().map { it.record.owner }).doesNotContainAnyElementsOf(deadCandidates)
    }

    /**
     * 씬을 못 찾은 레코드는 후보가 되지 않는다.
     *
     * 0 이 목표가 아니다 — 아직 안 걸어 본 씬과 죽은 코드가 여기 섞인다. 이 수가 갑자기 늘면
     * 배선 조인이나 배치 색인이 깨진 것이라 그때 알아채라고 센다.
     */
    @Test
    fun `주소를 못 찾은 레코드는 조용히 사라지지 않고 세어진다`() {
        val unaddressed = join.unaddressedRecords()
        val addressed = join.candidates().filter { it.spawn == null }.map { it.record }.distinct().size

        assertThat(unaddressed).isGreaterThanOrEqualTo(0)
        assertThat(unaddressed + addressed).isEqualTo(document.types.values.sumOf { it.size })
    }

    private companion object {
        /** 1.4 MB 를 테스트마다 다시 읽지 않는다. 파서는 상태가 없어 나눠 써도 된다. */
        val document = EvidenceParser(ObjectMapper())
            .parse(File("src/test/resources/contentmap/wv-editor-latest.json").readText())
    }
}
