package kr.artel.orchestration.contentmap.join

import kr.artel.orchestration.contentmap.evidence.EvidenceDocumentModel
import kr.artel.orchestration.contentmap.evidence.EvidenceHandle
import kr.artel.orchestration.contentmap.evidence.EvidenceRecord

/**
 * 씬 절반(`objects[].components[].calls[]`)과 코드 절반(`types` 레코드)을 잇는다.
 *
 * 문서는 이 둘을 따로 적고 서로를 가리키지 않는다. 씬은 "이 버튼이 `Scenes.TitleSceneManager` 의
 * `LoadStoryScene` 을 부른다"까지만 알고, 레코드는 자기 진입점의 안정 키만 안다. 이어 주는 규칙이
 * 여기 있고, **어느 길로 이었는지가 곧 그 `wiring`의 신뢰도**라 [WiringPath] 로 값에 남긴다.
 *
 * 길이 셋인 이유는 실측이 셋을 다 요구하기 때문이다(`wiring` 7건 기준):
 * - [WiringPath.ENTRY] 6건. 이 중 `entryId` 비교만으로 걸리는 것은 5건이고, `owner` + `methodId` 의
 *   메서드 이름까지 봐야 `Scenes.TitleSceneManager::LoadStoryScene` 이 걸린다 — 그 레코드의 진입점은
 *   `InitPlayerData()` 라 `entryId` 에는 이름이 없다.
 * - [WiringPath.ARRIVAL] 2건. 쌍만 세면 [WiringPath.ENTRY] 와 겹치지만, **레코드 단위로는 이 길로만
 *   생기는 `wiring`이 3건 있다** — `Core.SaveLoadController` 의 세 레코드가 `Canvas/continue` 와
 *   `Canvas/Button (Legacy)` 에 닿는 길이 `alsoReachedBy` 뿐이다. 접힌 변형을 펴지 않으면 그 버튼을
 *   누르면 세이브가 일어난다는 사실이 통째로 사라진다.
 * - [WiringPath.HANDLE] 1건. `Combat.UI.CombineZone::OnButtonClick` 은 다른 어느 길로도 걸리지
 *   않는다 — 코드가 런타임에 `AddListener` 로 매단 것이라 인스펙터 쪽 진입점이 없다.
 *
 * 매칭은 타입 풀네임과 메서드 이름의 **완전 문자열 일치**만 본다. 파라미터·제네릭 소거·별칭 정규화는
 * 하지 않는다 — `calls[].method` 자체가 이름만 담아 오버로드를 가릴 정보가 없고, 근거에 없는 사례를
 * 위한 규칙을 지어내는 것은 이 이슈가 금지한 것이다. 못 맞추면 `wiring`이 없는 것으로 남는다.
 */
class SceneWiringIndex private constructor(
    private val controlsByTarget: Map<Pair<String, String>, List<WiredControl>>,
    private val records: List<EvidenceRecord>,
) {

    /**
     * 이 레코드에 닿는 컨트롤들. 씬 문서 순서이고, 같은 자리·같은 이벤트는 한 번만 낸다.
     *
     * [records] 에 없는 레코드(예: `unplaced` 의 근거)를 넣어도 답한다 — 판정에 필요한 것은 레코드가
     * 든 키뿐이라, 인덱스는 씬 쪽 절반만 미리 세워 둔다.
     *
     * 컨트롤 전체를 훑지 않고 **레코드가 든 키로 찾아 들어간다.** 훑으면 (레코드 × 컨트롤)마다 안정 키를
     * 다시 쪼개게 되고, 오늘 318×7 인 것이 게임이 커지면 그대로 곱해진다. 배치 색인이 축을 뒤집어 둔 것과
     * 같은 이유다.
     */
    fun bindingsFor(record: EvidenceRecord): List<ControlBinding> {
        val keys = keysOf(record)
        return keys.all()
            .flatMap { controlsByTarget[it].orEmpty() }
            .distinctBy { it.order }
            .sortedBy { it.order }
            .map { control ->
                // 한 컨트롤이 두 길로 걸릴 수 있다(진입점이자 도착점인 경우). 그때는 가장 곧은 길만
                // 남긴다 — 같은 `wiring`을 두 줄로 내면 세는 쪽이 조작이 둘이라고 읽는다.
                val via = keys.straightestPathTo(control.target)
                ControlBinding(
                    placement = control.placement,
                    event = if (via == WiringPath.HANDLE) keys.handles[control.target]?.channel else control.event,
                    via = via,
                )
            }.distinct()
    }

    /**
     * [path] 하나로 걸리는 (타입, 메서드) 쌍들. null 이면 세 길의 합집합이다.
     *
     * 길마다 따로 셀 수 있어야 "이 길을 지우면 무엇을 잃는가"에 답할 수 있다. 실측은
     * 합집합 7 · [WiringPath.ENTRY] 6 · [WiringPath.ARRIVAL] 2 · [WiringPath.HANDLE] 1 이다
     * (합이 7을 넘는 것은 한 쌍이 여러 길로 걸리기 때문이고, 여기서는 겹침을 접지 않는다).
     */
    fun matchedPairs(path: WiringPath? = null): Set<Pair<String, String>> =
        records.flatMapTo(LinkedHashSet()) { record ->
            val keys = keysOf(record)
            val targets = if (path == null) keys.all() else keys.of(path)
            targets.filter { it in controlsByTarget }
        }

    /** 씬이 들고 있는 `wiring`의 수. 실측 7 — 매칭 성공과 무관하게 "몇 건을 이으려 했는가"다. */
    val wiredControlCount: Int get() = controlsByTarget.values.sumOf { it.size }

    /**
     * 레코드 하나가 `wiring`의 반대편으로 내미는 키들. 레코드마다 한 번만 계산한다.
     *
     * `entryId` 만 보면 실측 7쌍 중 5쌍이다. 진입점은 인라인·중복접기로 다른 이름이 되지만 `owner` 는
     * 레코드가 매달린 타입 키라 흔들리지 않아(실측 318건 중 71건이 `entryId` 의 타입과 어긋난다),
     * `owner` + `methodId` 의 메서드명을 함께 봐야 6쌍이 된다.
     */
    private fun keysOf(record: EvidenceRecord): RecordKeys {
        val entry = buildSet {
            stableIdTarget(record.entryId)?.let { add(it) }
            stableIdTarget(record.methodId)?.second?.let { add(record.owner to it) }
        }
        return RecordKeys(
            entry = entry,
            // `alsoReachedBy` 에 entryId 를 끼워 넣지 않는다. 끼우면 ENTRY 로 걸린 것이 ARRIVAL 로도
            // 걸렸다고 기록돼 길 이름이 거짓이 된다.
            arrivals = record.alsoReachedBy.mapNotNull { stableIdTarget(it.entryId) }.toSet(),
            handles = record.handles.mapNotNull { handle ->
                handle.handlerId?.let { stableIdTarget(it) }?.let { it to handle }
            }.toMap(),
        )
    }

    private data class RecordKeys(
        val entry: Set<Pair<String, String>>,
        val arrivals: Set<Pair<String, String>>,
        val handles: Map<Pair<String, String>, EvidenceHandle>,
    ) {
        fun all(): List<Pair<String, String>> = (entry + arrivals + handles.keys).toList()

        fun of(path: WiringPath): Set<Pair<String, String>> = when (path) {
            WiringPath.ENTRY -> entry
            WiringPath.ARRIVAL -> arrivals
            WiringPath.HANDLE -> handles.keys
        }

        /** 곧은 순서: 진입점 → 도착점 → 런타임 `wiring`. */
        fun straightestPathTo(target: Pair<String, String>): WiringPath = when (target) {
            in entry -> WiringPath.ENTRY
            in arrivals -> WiringPath.ARRIVAL
            else -> WiringPath.HANDLE
        }
    }

    /** `wiring` 하나의 씬 쪽 절반 — 어떤 자리의 어떤 이벤트가 어떤 (타입, 메서드) 를 부르는가. */
    private data class WiredControl(
        val placement: ScenePlacement,
        /** `m_OnClick` 등. */
        val event: String,
        val target: Pair<String, String>,
        /** 씬 문서에서의 순서. 키로 찾아 들어가도 원래 순서로 되돌리려고 든다. */
        val order: Int,
    )

    companion object {

        fun build(document: EvidenceDocumentModel): SceneWiringIndex {
            var order = 0
            val controls = document.allObjects.flatMap { obj ->
                val placement = obj.toPlacement()
                obj.components.flatMap { it.calls }.map { call ->
                    WiredControl(placement, call.event, call.targetType to call.method, order++)
                }
            }
            return SceneWiringIndex(
                controlsByTarget = controls.groupBy { it.target },
                // `types` 만 본다. `unplaced` 의 근거는 자리가 없어 `wiring`의 반대편이 될 수 없고, 실측에서도
                // 넣든 빼든 걸리는 쌍이 7로 같다 — 그쪽은 [SpawnOrigin] 이 붙일 몫이다.
                records = document.types.values.flatten(),
            )
        }
    }
}

/**
 * `Assembly-CSharp|타입|메서드|반환형(인자형)` 에서 (타입 풀네임, 메서드 이름) 을 뽑는다.
 * 그 형식이 아니면 null 이다 — 못 읽은 키를 빈 문자열로 바꾸면 빈 타입끼리 서로 맞아 버린다.
 *
 * 타입을 `/` 로 잘라 앞 조각만 쓰는 이유: 컴파일러가 만든 중첩 타입이
 * `Battle.Turns.TurnBattleSystem/<EnemyTurnCounter>d__13` 처럼 온다(실측 methodId 318건 중 31건).
 * 코루틴 본체가 그 안에 있어도 인스펙터가 `wiring`하는 것은 바깥 타입이라, 자르지 않으면 그 레코드는
 * 어떤 컨트롤에도 닿지 못한다. 메서드 이름은 자르지 않는다 — 람다 처리기가
 * `<StoryTelling>b__8_0` 처럼 오고, 그 이름 그대로가 `handlerId` 의 키다.
 */
fun stableIdTarget(stableId: String): Pair<String, String>? {
    val parts = stableId.split('|')
    if (parts.size < 3) return null
    val type = parts[1].substringBefore('/')
    val method = parts[2]
    if (type.isEmpty() || method.isEmpty()) return null
    return type to method
}
