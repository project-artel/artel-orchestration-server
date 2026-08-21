package kr.artel.orchestration.contentmap.ingest

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.EvidenceParser
import kr.artel.orchestration.contentmap.evidence.GroupKind
import kr.artel.orchestration.contentmap.join.EvidenceJoin
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `CapabilityKey.of` 가 골든 문서 위에서 실제로 하는 일을 고정한다.
 *
 * `CapabilityKey.kt` 의 표는 "이 칸이 없으면 무엇이 겹치나"를 말로 적어 둔 것이고, 여기는 그 말을
 * 실측 데이터로 확인한다. 단위 하나마다 KDoc 에 **왜 이 확인이 필요한지**를 적는다 — 숫자만 보고
 * 고치는 사람이 없도록.
 */
class CapabilityKeyTest {

    private val join = EvidenceJoin(document)

    /**
     * 키는 레코드가 아니라 **명세 위에서** 단사여야 한다.
     *
     * `uk_capability_map_key` 가 지키는 약속이다 — 서로 다른 스텝 둘이 한 키를 들면 적재가 거절되거나
     * 한 줄로 접힌다. 다만 "후보 하나 = 기능 한 줄"은 아니다.
     *
     * **실측(`wv-editor-latest.json`): 후보 529건 → 키 491건.** 접히는 38건은 씬·owner·진입점·메서드·
     * `branch` 위치·조건·조작이 전부 같고 `effects` 만 다르거나(20그룹) `recordKind` 만 다르다(8그룹).
     * given 과 when 이 같으면 한 줄이고 then 은 그 줄에 함께 달린다 — 적재기가 그렇게 합친다.
     *
     * 그래서 여기서 보는 것은 두 가지다: 키가 접는 것이 **정말 같은 (given, when) 인가**, 그리고
     * 그 수가 실측 그대로인가.
     */
    @Test
    fun `키가 접는 것은 같은 조작의 다른 조각뿐이다`() {
        val candidates = join.candidates()
        val groups = candidates.groupBy { CapabilityKey.of(it) }

        assertThat(candidates).hasSize(529)
        assertThat(groups).hasSize(491)

        // 접힌 그룹은 전부 같은 자리·같은 조작이다. 하나라도 다르면 서로 다른 스텝이 한 줄로 접힌 것이다.
        groups.values.filter { it.size > 1 }.forEach { group ->
            assertThat(group.map { it.scene }.distinct()).hasSize(1)
            assertThat(group.map { it.record.owner }.distinct()).hasSize(1)
            assertThat(group.map { it.record.entryId }.distinct()).hasSize(1)
            assertThat(group.map { it.record.methodId }.distinct()).hasSize(1)
            assertThat(group.map { it.interaction }.distinct()).hasSize(1)
            assertThat(group.map { it.inputKey }.distinct()).hasSize(1)
            assertThat(group.map { it.branchOffset }.distinct()).hasSize(1)
            assertThat(group.map { CapabilityKey.canonical(it.condition) }.distinct()).hasSize(1)
        }
    }

    /**
     * `methodId` 가 키에 없으면 서로 다른 메서드가 한 줄로 접힌다.
     *
     * 이 칸을 넣기 전 실측이 후보 529건에 키 371건이었다 — 한 진입점 아래 코루틴 본체와 그것이 부르는
     * `set_IsLocked` 가 같은 키를 들었다. `entryId` 는 "어디로 들어왔나"만 말한다.
     */
    @Test
    fun `methodId만 바꾸면 키가 바뀐다`() {
        val candidate = join.candidates().first()
        val changed = candidate.copy(
            record = candidate.record.copy(methodId = candidate.record.methodId + "-다른메서드")
        )

        assertThat(CapabilityKey.of(changed)).isNotEqualTo(CapabilityKey.of(candidate))
    }

    /**
     * `scene` 만 바꾸면 키가 바뀐다.
     *
     * 없으면 `GameClearController` 처럼 같은 타입이 두 씬에 놓였을 때 두 씬의 기능이 한 줄로 접힌다.
     */
    @Test
    fun `씬만 바꾸면 키가 바뀐다`() {
        val candidate = join.candidates().first()
        val changed = candidate.copy(scene = candidate.scene + "-다른씬")

        assertThat(CapabilityKey.of(changed)).isNotEqualTo(CapabilityKey.of(candidate))
    }

    /**
     * `owner` 만 바꾸면 키가 바뀐다.
     *
     * 없으면 `owner` 와 `entryId` 의 타입이 어긋난 레코드끼리 겹친다 — 실측 318건 중 71건이 그 어긋남을
     * 가진다(`EvidenceRecord.owner` KDoc).
     */
    @Test
    fun `owner만 바꾸면 키가 바뀐다`() {
        val candidate = join.candidates().first()
        val changed = candidate.copy(record = candidate.record.copy(owner = candidate.record.owner + "-다른오너"))

        assertThat(CapabilityKey.of(changed)).isNotEqualTo(CapabilityKey.of(candidate))
    }

    /**
     * `entryId` 만 바꾸면 키가 바뀐다.
     *
     * 없으면 서로 다른 진입점이 한 줄로 눌린다.
     */
    @Test
    fun `entryId만 바꾸면 키가 바뀐다`() {
        val candidate = join.candidates().first()
        val changed = candidate.copy(record = candidate.record.copy(entryId = candidate.record.entryId + "-다른진입점"))

        assertThat(CapabilityKey.of(changed)).isNotEqualTo(CapabilityKey.of(candidate))
    }

    /**
     * `branchOffset` 만 바꾸면 키가 바뀐다.
     *
     * 없으면 한 메서드 안의 서로 다른 지점이 눌린다 — 실측 `ShowBattle` 의 다섯 branch 는 offset 이
     * 전부 `@3` 이라 조건만으로는 못 가르는 경우가 있는 것처럼, offset 만으로도 못 가르는 경우가 있어
     * 두 칸이 함께 필요하다. 이 테스트는 offset 쪽만 본다.
     */
    @Test
    fun `branchOffset만 바꾸면 키가 바뀐다`() {
        val candidate = join.candidates().first { it.branchOffset != null }
        val changed = candidate.copy(branchOffset = candidate.branchOffset!! + 1)

        assertThat(CapabilityKey.of(changed)).isNotEqualTo(CapabilityKey.of(candidate))
    }

    /**
     * 조건만 바꾸면 키가 바뀐다.
     *
     * 없으면 `ShowBattle` 처럼 offset 이 전부 같은 다섯 branch 를 가를 방법이 없어진다. 원래 조건이
     * 무엇이든 걸리도록, 실측 문서에 나오지 않을 사유 문자열을 쓴 `Unknown` 으로 바꾼다.
     */
    @Test
    fun `조건만 바꾸면 키가 바뀐다`() {
        val candidate = join.candidates().first()
        val changed = candidate.copy(condition = ConditionNode.Unknown(reason = "capability-key-test", unread = null))

        assertThat(CapabilityKey.of(changed)).isNotEqualTo(CapabilityKey.of(candidate))
    }

    /**
     * `inputKey` 만 바꾸면 키가 바뀐다.
     *
     * 없으면 `either` 를 쪼갠 두 후보가 가드를 공유해 조건까지 같을 때 서로 다른 키를 눌러 줄 마지막
     * 칸이 사라진다.
     */
    @Test
    fun `inputKey만 바꾸면 키가 바뀐다`() {
        val candidate = join.candidates().first { it.inputKey != null }
        val changed = candidate.copy(inputKey = candidate.inputKey + "-다른키")

        assertThat(CapabilityKey.of(changed)).isNotEqualTo(CapabilityKey.of(candidate))
    }

    /**
     * 바인딩 경로(`control_path`)만 바꾸면 키가 바뀐다.
     *
     * 없으면 조인이 컨트롤마다 낸 후보 중, 한 씬의 두 버튼이 같은 메서드를 부를 때 서로 다른 컨트롤
     * 이었다는 사실이 사라지고 한 줄로 겹친다.
     */
    @Test
    fun `바인딩 경로만 바꾸면 키가 바뀐다`() {
        val candidate = join.candidates().first { it.binding != null }
        val binding = candidate.binding!!
        val changed = candidate.copy(
            binding = binding.copy(placement = binding.placement.copy(path = binding.placement.path + "-다른경로")),
        )

        assertThat(CapabilityKey.of(changed)).isNotEqualTo(CapabilityKey.of(candidate))
    }

    /**
     * `spawned_by_field` 만 바꾸면 키가 바뀐다.
     *
     * 없으면 한 씬에 `SpawnOrigin` 이 둘이면(서로 다른 필드가 같은 프리팹 타입을 만들면) 겹친다.
     */
    @Test
    fun `스폰 필드만 바꾸면 키가 바뀐다`() {
        val candidate = join.candidates().first { it.spawn?.field != null }
        val spawn = candidate.spawn!!
        val changed = candidate.copy(spawn = spawn.copy(field = spawn.field + "-다른필드"))

        assertThat(CapabilityKey.of(changed)).isNotEqualTo(CapabilityKey.of(candidate))
    }

    /**
     * 문서를 다시 파싱해도 같은 위치의 후보는 같은 키를 낸다.
     *
     * "재적재를 넘어 살아남는다"는 말의 실체가 이것이다 — 같은 문서를 다시 구워 다시 적재해도
     * `scene_edge.capability_id` 같은 참조가 엉뚱한 기능에 붙지 않으려면, 같은 문서를 두 번 읽었을 때
     * 같은 순서로 같은 키가 나와야 한다. `EvidenceParser`/`EvidenceJoin` 이 문서 순서를 보존한다고
     * 약속하므로(각 KDoc) 인덱스로 맞춰 비교한다.
     */
    @Test
    fun `문서를 다시 파싱해도 같은 자리의 후보는 같은 키를 낸다`() {
        val firstPass = join.candidates()

        val reparsedDocument = EvidenceParser(ObjectMapper())
            .parse(File("src/test/resources/contentmap/wv-editor-latest.json").readText())
        val secondPass = EvidenceJoin(reparsedDocument).candidates()

        assertThat(secondPass).hasSameSizeAs(firstPass)
        val firstKeys = firstPass.map { CapabilityKey.of(it) }
        val secondKeys = secondPass.map { CapabilityKey.of(it) }
        assertThat(secondKeys).containsExactlyElementsOf(firstKeys)
    }

    /**
     * 키는 64자 소문자 16진수다 — `capability_key VARCHAR(64)` 가 담을 수 있는 꼴 그대로.
     *
     * SHA-256 을 `%02x` 로 적은 값이라 원래 그래야 하지만, 다이제스트 알고리즘이나 인코딩이 바뀌면
     * 컬럼 길이를 넘기거나 대문자가 섞여 마이그레이션 없이는 저장이 깨진다.
     */
    @Test
    fun `키는 64자 소문자 16진수다`() {
        val keys = join.candidates().map { CapabilityKey.of(it) }

        assertThat(keys).isNotEmpty
        assertThat(keys).allSatisfy { assertThat(it).matches("[0-9a-f]{64}") }
    }

    /**
     * `every` 와 `either` 는 같은 부분을 묶어도 다른 키를 낸다.
     *
     * `canonical()` 이 `kind.wire` 를 앞에 적지 않으면 "모두 성립"과 "하나만 성립"이 같은 문자열이
     * 되고, 조건까지 같은데 뜻이 반대인 두 기능이 한 줄로 접힌다.
     */
    @Test
    fun `every와 either는 같은 부분에서도 다른 키를 낸다`() {
        val a = ConditionNode.Gesture(input = "key:RightArrow (down)", offset = 1)
        val b = ConditionNode.Test(left = "HP", operator = ">", right = "0", context = "this", offset = 2)

        val every = CapabilityKey.canonical(ConditionNode.Group(GroupKind.EVERY, listOf(a, b)))
        val either = CapabilityKey.canonical(ConditionNode.Group(GroupKind.EITHER, listOf(a, b)))

        assertThat(every).isNotEqualTo(either)
    }

    /**
     * `either` 의 배열 순서를 바꾸면 키가 바뀐다.
     *
     * `CapabilityKey.canonical` 의 KDoc 이 밝히듯, 이는 의도한 동작이다 — `either[a,b]` 와 `either[b,a]`
     * 는 뜻으로는 같지만, 코드는 정렬하지 않고 문서가 준 순서를 그대로 적는다("정렬하면 '둘 중 하나'의
     * 원래 모양이 사라진다"). 이 테스트는 그 선택을 그대로 고정한다 — 잘못됐다고 보이면 이 테스트가
     * 아니라 `CapabilityKey.canonical` 을 고쳐야 한다.
     *
     * 이 선택에 반대하지 않는다: 문서 순서는 같은 문서를 다시 구워도 안정적이라고 파서 KDoc 이
     * 약속하므로, 정렬해서 얻을 결정론이 없다. 정렬은 오히려 "둘 중 하나"가 실제로 어느 쪽부터
     * 시도됐는지의 정보를 지운다.
     */
    @Test
    fun `either의 순서를 바꾸면 키가 바뀐다`() {
        val a = ConditionNode.Gesture(input = "key:RightArrow (down)", offset = 1)
        val b = ConditionNode.Test(left = "HP", operator = ">", right = "0", context = "this", offset = 2)

        val ab = CapabilityKey.canonical(ConditionNode.Group(GroupKind.EITHER, listOf(a, b)))
        val ba = CapabilityKey.canonical(ConditionNode.Group(GroupKind.EITHER, listOf(b, a)))

        assertThat(ab).isNotEqualTo(ba)
    }

    private companion object {
        /** 1.4 MB 를 테스트마다 다시 읽지 않는다. 파서는 상태가 없어 나눠 써도 된다. */
        val document = EvidenceParser(ObjectMapper())
            .parse(File("src/test/resources/contentmap/wv-editor-latest.json").readText())
    }
}
