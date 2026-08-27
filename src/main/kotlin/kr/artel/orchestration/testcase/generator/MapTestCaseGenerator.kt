package kr.artel.orchestration.testcase.generator

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.contentmap.dto.ContentMapCapabilityRow
import com.fasterxml.jackson.databind.JsonNode
import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.ConditionOverlap
import kr.artel.orchestration.contentmap.evidence.GroupKind
import kr.artel.orchestration.contentmap.evidence.EvidenceParser
import kr.artel.orchestration.contentmap.entity.CapabilityEffectEntity
import kr.artel.orchestration.contentmap.repository.CapabilityEffectRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import org.springframework.stereotype.Service

/**
 * 지도에서 케이스를 뽑는다(ARTEL-554).
 *
 * ## 읽는 곳은 하나다
 *
 * `v_content_map_capability` — 뷰가 자기 주석에 **"TC 생성기가 읽는 유일한 창구"** 라고 적어 두었고,
 * 그 이유도 함께 적혀 있다:
 *
 * > 읽는 곳을 한 군데로 못 박지 않으면 TC 생성기가 근거 문서를 직접 보게 되고, 그 순간
 * > "TC 입력은 content_map 단독"이라는 계약이 무너진다
 *
 * 그래서 여기는 근거 문서를 열지 않는다. 뷰가 이미 `not-a-step` 과 `merged_into` 를 걸러 낸다 —
 * 실측(적재기가 앉힌 word-venture 지도)에서 기능 491행이 뷰를 지나면 **51행**이 된다.
 *
 * ## 무엇이 케이스가 되나
 *
 * **확인할 것이 있어야 케이스다.** `then` 에 쓸 수 있는 효과(`observable` · `availability`)가 하나도
 * 없으면 내지 않는다. 실행해도 통과·실패를 말할 수 없는 줄이라, 사용자에게는 기대결과가 빈 케이스로
 * 보인다.
 *
 * 실측으로 그 자리가 51행 중 15행이고, 그중 12행은 `record_kind = 'flow'` 다 —
 * **명세 후보가 아니라 연결점**이라고 문서가 스스로 말한 레코드다(`RecordKind` KDoc). 나머지 3행은
 * `state` 효과만 든다(값은 바뀌는데 화면에서 볼 수 없다).
 *
 * 버리는 것이 아니다. `v_spec_gap` 이 그 자리를 `then-missing` 으로 이미 세고 있고, 그것은
 * **"QA 결함이 아니라 개발 우선순위 신호"** 로 화면에 나간다.
 *
 * ## 순서
 *
 * 뷰의 `ORDER BY scene_name, capability_id` 를 그대로 물려받는다. 그 정렬은 취향이 아니라
 * 프롬프트 캐시 계약이다([ContentMapRepository.findStepCapabilityRows] 의 주석).
 */
@Service
class MapTestCaseGenerator(
    private val contentMaps: ContentMapRepository,
    private val effects: CapabilityEffectRepository,
    private val objectMapper: ObjectMapper,
) {

    suspend fun generate(contentMapId: Long): List<MapTestCase> {
        val edges = contentMaps.findCallEdges(contentMapId).toList()
        // 매개변수 이름을 호출자가 넘긴 값으로 되돌리려면 필요하다(ARTEL-602).
        val settled = contentMaps.findSettledArguments(contentMapId).toList()
            .groupBy { it.capabilityId }
            .mapValues { (_, args) -> args.associate { it.position to it.value } }
        val drafts = contentMaps.findStepCapabilityRows(contentMapId).toList()
            .flatMap { row -> draftsOf(row, edges, settled) }
        return merged(drafts)
    }

    /**
     * **결과가 다를 때만 다른 케이스다**(ARTEL-600).
     *
     * 앞서 케이스의 정체는 조작이었다. 조작이 상수인 씬에서 그러면 케이스가 폭발한다 —
     * 실측(word-venture StoryScene)에서 지시 가능한 조작이 `IsAdvanceKeyDown()` 하나뿐인데
     * 케이스가 35건이었다. 그 하나는 `System.Boolean()` 을 돌려주는 **판정 함수**이고, 씬에서
     * 사람이 할 수 있는 일은 "아무 키나 누른다" 뿐이다. 조작이 변수가 아닌 자리에서 조작으로
     * 세면, 전제 29조각 × 결과 10가지가 그대로 곱해진다.
     *
     * 확인하는 것이 같으면 실행하는 사람에게는 같은 시험이다. `chatName.text 가 갱신된다` 를
     * 전제만 바꿔 아홉 번 시키는 표가 그렇게 나왔다.
     *
     * **갈래는 여전히 살아남는다** — 갈래가 갈래인 이유는 결과가 다르기 때문이다:
     *
     * ```
     * StagePosition == 5  →  `TitleScene` 화면으로 전환된다
     * StagePosition != 5  →  `Map_scene` 화면으로 전환된다
     * ```
     *
     * 순서는 첫 조각의 자리를 지킨다. 뷰의 정렬이 프롬프트 캐시 계약이라 흔들면 안 된다.
     */
    private fun merged(drafts: List<Draft>): List<MapTestCase> =
        drafts.groupBy { Triple(it.scene, it.step, it.outcome) }
            .map { (_, group) ->
                val first = group.first()
                MapTestCase(
                    capabilityKey = first.capabilityKey,
                    scene = first.scene,
                    precondition = MapTestCasePhrasing.precondition(
                        first.scene, weakest(group.map { it.condition }), first.inputKey,
                    ),
                    step = first.step,
                    expected = first.outcome,
                    status = first.status,
                    gaps = group.flatMap { it.gaps }.distinct(),
                )
            }
            .let(::withInterchangeableInputs)

    /**
     * **같은 자리에서 같은 일을 하는 입력은 한 줄에 담는다**(ARTEL-602).
     *
     * 전제도 결과도 같은데 누르는 키만 다른 줄이 나온다 — 실측에서 `RightArrow` 와 `UpArrow` 가
     * 같은 지점으로 옮기고, 타이틀의 버튼 둘이 같은 화면으로 간다. 12건이 그 모양이었다.
     *
     * **지우지 않는다.** 두 키가 다 되는지는 QA 가 실제로 확인해야 하는 것이고, 한쪽만 남기면 다른
     * 키가 고장 난 것을 아무도 못 잡는다. 그래서 한 줄에 **둘 다 적는다** — 실행하는 사람이 한
     * 자리에서 둘을 다 눌러 보게 된다.
     *
     * 조작 문구만 잇는다. 앞의 것을 대표로 두고 순서는 원래 자리를 지킨다.
     */
    private fun withInterchangeableInputs(cases: List<MapTestCase>): List<MapTestCase> =
        cases.groupBy { Triple(it.scene, it.precondition, it.expected) }
            .map { (_, group) ->
                if (group.size == 1) group.single()
                else group.first().copy(
                    step = MapTestCasePhrasing.eitherStep(group.map { it.step }),
                    gaps = group.flatMap { it.gaps }.distinct(),
                )
            }

    /**
     * 같은 결과를 내는 여러 전제를 **한 전제로** 만든다.
     *
     * **조건 없는 갈래가 하나라도 있으면 그것이 답이다.** 조건 없이도 나는 결과라면, 거기에 조건을
     * 덧붙인 갈래들은 논리적으로 그 안에 든다 — 실측에서 26건이 그렇게 겹쳐 있었다. 좁은 쪽을
     * 적으면 "이 조건일 때만 난다"는 거짓말이 된다.
     *
     * 아니면 갈래들을 `또는` 으로 잇되, **모든 갈래가 함께 요구하는 것은 앞으로 뺀다.** 그러지
     * 않으면 같은 조건이 갈래 수만큼 되풀이되어 읽을 수 없다.
     */
    private fun weakest(conditions: List<ConditionNode?>): ConditionNode? {
        val arms = conditions.distinct()
        if (arms.size == 1) return arms.single()
        // 조건 없는 갈래가 있으면 나머지는 그 안에 든다.
        if (arms.any { it == null || it == ConditionNode.Always }) return null

        // **넓은 갈래가 좁은 갈래를 덮는다.** 요구가 적을수록 성립하기 쉬우므로, 어떤 갈래의 요구가
        // 다른 갈래의 요구를 통째로 품고 있으면 품긴 쪽만 남는다 — 실측(EndingScene)에서 `i < N`
        // 단독 갈래가 있는데 `A 그리고 B 그리고 (i < N)` 을 나란히 적어 여덟 갈래가 됐다. 좁은 쪽을
        // 함께 적는 것은 틀린 데다 읽히지도 않는다.
        val required = arms.map { every(it!!).toSet() }
            .let { all -> all.filter { one -> all.none { other -> other != one && one.containsAll(other) } } }
            .distinct()
        if (required.size == 1) return conjunction(required.single().toList())

        // 모든 갈래가 함께 요구하는 것은 앞으로 뺀다. 그러지 않으면 같은 조건이 갈래 수만큼 되풀이된다.
        val shared = required.reduce { a, b -> a intersect b }
        val rest = required.map { it - shared }.distinct()
        val either = ConditionNode.Group(GroupKind.EITHER, rest.map { conjunction(it.toList())!! })
        return conjunction(shared.toList() + either)
    }

    /** `every` 를 평평하게 편다. 갈래끼리 견주려면 요구들이 한 겹이어야 한다. */
    private fun every(node: ConditionNode): List<ConditionNode> =
        if (node is ConditionNode.Group && node.kind == GroupKind.EVERY) node.parts.flatMap(::every)
        else listOf(node)

    private fun conjunction(parts: List<ConditionNode>): ConditionNode? = when (parts.size) {
        0 -> null
        1 -> parts.single()
        else -> ConditionNode.Group(GroupKind.EVERY, parts)
    }

    /**
     * 아직 합치기 전의 케이스 한 줄. 전제를 **문장이 아니라 조건 트리로** 들고 있다 — 합칠 때
     * 갈래끼리 견줘야 하고, 글자로는 그것을 못 한다.
     */
    private data class Draft(
        val capabilityKey: String,
        val scene: String,
        val condition: ConditionNode?,
        val inputKey: String?,
        val step: String,
        val outcome: String,
        val status: String,
        val gaps: List<String>,
    )

    /**
     * 기능 하나에서 케이스 **여럿**이 나온다 — 확인할 수 있는 효과 하나마다 하나다.
     *
     * 합쳐서 한 줄로 내면 실행하는 사람이 무엇을 볼지 모른다([MapTestCasePhrasing.expectedEach] 의
     * 주석에 실측이 있다). 구버전도 같은 기능에서 아홉 줄을 냈다.
     */
    private suspend fun draftsOf(
        row: ContentMapCapabilityRow,
        edges: List<kr.artel.orchestration.contentmap.dto.ContentMapCallEdge>,
        settled: Map<Long, Map<Int, String>>,
    ): List<Draft> {
        // 키가 없는 행은 evidence 출신이 아니다. 케이스가 지도를 되짚을 방법이 없으므로 내지 않는다 —
        // 되짚지 못하는 케이스는 이 개편이 없애려는 바로 그 문자열 맞춤으로 돌아간다.
        val key = row.capabilityKey ?: return emptyList()
        val condition = conditionOf(row)
        val step = MapTestCasePhrasing.step(row.interaction, row.inputKey, row.controlLabel, row.controlPath)
        val gaps = gapsOf(row)

        // 자기 효과가 먼저다. 없을 때만 공통 호출자를 통해 빌려 온다 — 자기가 결과를 들고 있으면
        // 그것이 이 조작의 결과이고, 남의 것까지 끌어오면 무관한 결과가 붙는다.
        val own = effects.findByCapabilityIdOrderByIdAsc(row.capabilityId).toList()
        val sources: List<Triple<ConditionNode?, List<CapabilityEffectEntity>, Long>> =
            if (own.isNotEmpty()) listOf(Triple(condition, own, row.capabilityId))
            else borrowed(row, condition, edges)

        return sources.flatMap { (situation, effectRows, source) ->
            // **실행하는 사람이 만들 수 있는 조건만 남긴다**(ARTEL-602). 매개변수 이름은 호출자가
            // 넘긴 값으로 바꾸고, 못 푸는 루프 변수는 빼되 그 사실을 사유로 남긴다.
            val settledCondition = MapTestCaseLocals.settle(situation, source, settled)
            val reasons = if (settledCondition.unsettable) gaps + MapTestCaseLocals.UNSETTABLE else gaps
            MapTestCasePhrasing.expectedEach(effectRows).map { outcome ->
                Draft(
                    capabilityKey = key,
                    scene = row.sceneName,
                    condition = settledCondition.condition,
                    inputKey = row.inputKey,
                    step = step,
                    outcome = outcome,
                    status = row.status,
                    gaps = reasons,
                )
            }
        }
    }

    /**
     * 자기 효과가 없는 조작 갈래가 **공통 호출자**를 통해 결과를 빌려 온다(ARTEL-554).
     *
     * 실측: StoryScene · EndingScene 의 `press any` 갈래는 효과가 0이고, 결과는
     * `UpdateChatStream` · `SetAnyKeyPromptVisible` · `LoadMapScene` 에 있다. 셋을 다 부르는
     * `StoryController.StoryTelling()` 이 그 셋과 입력 갈래를 잇는 유일한 자리다.
     *
     * 빌려 온 케이스의 사전조건은 **세 조건을 함께** 든다 — 자기 조건, 그 호출이 일어나는 조건,
     * 결과 갈래 자신의 조건. 셋이 다 참일 때만 그 결과가 난다.
     */
    private suspend fun borrowed(
        row: ContentMapCapabilityRow,
        condition: ConditionNode?,
        edges: List<kr.artel.orchestration.contentmap.dto.ContentMapCallEdge>,
    ): List<Triple<ConditionNode?, List<CapabilityEffectEntity>, Long>> =
        MapTestCaseSiblings.of(row.capabilityId, edges).mapNotNull { borrowed ->
            val callerCondition = parse(borrowed.callerCondition)
            val ownCondition = parse(borrowed.ownCondition)
            // **모순되는 갈래는 잇지 않는다.** 이은 케이스의 사전조건이 양쪽을 함께 드는데 둘이
            // 모순이면 절대 만들 수 없는 전제가 된다 — 실측에서 `waitingForAcknowledge != 0` 과
            // `== 0` 이 한 줄에 들어왔다. 만들 수 없는 것을 만들라고 적는 것이 곧 거짓 명세다.
            if (!ConditionOverlap.compatible(condition, callerCondition)) return@mapNotNull null
            if (!ConditionOverlap.compatible(condition, ownCondition)) return@mapNotNull null
            if (!ConditionOverlap.compatible(callerCondition, ownCondition)) return@mapNotNull null

            val rows = effects.findByCapabilityIdOrderByIdAsc(borrowed.capabilityId).toList()
            if (rows.isEmpty()) return@mapNotNull null
            // **호출자 조건은 판정에만 쓰고 문장에는 싣지 않는다.** 그것은 "코드가 그 호출에
            // 닿는 조건"이지 테스터가 만들 것이 아니다 — 코루틴이 몇 번째 대사를 넘겼는지 같은
            // 내부 진행 상태다. 사전조건에 실으면 사람이 만들 수 없는 것을 요구하게 되고,
            // 실측에서 그것 때문에 전제가 구버전의 2.5배로 부풀었다.
            //
            // 판정에서는 여전히 본다. 모순되는 갈래를 잇지 않으려면 필요하다.
            // 셋째 칸은 **결과를 든 쪽**이다. 조건도 그쪽 메서드의 것이라, 매개변수를 되돌릴 때
            // 봐야 하는 인자도 그쪽 것이다.
            Triple(MapTestCasePhrasing.both(condition, ownCondition), rows, borrowed.capabilityId)
        }

    private fun parse(json: io.r2dbc.postgresql.codec.Json?): ConditionNode? {
        val node: JsonNode = json
            ?.let { runCatching { objectMapper.readTree(it.asString()) }.getOrNull() }
            ?.takeIf { it.isObject && !it.isEmpty }
            ?: return null
        return EvidenceParser(objectMapper).parseCondition(node)
    }

    /**
     * 저장된 조건 트리를 읽는다. **파서가 읽는다** — 대문자 `kind` 도 이름표 없는 노드도 그쪽이
     * 이미 다룬다. 여기서 한 벌 더 쓰면 두 곳이 서로 다르게 관대해진다.
     */
    private fun conditionOf(row: ContentMapCapabilityRow): ConditionNode? {
        val json = row.conditionTree
            ?.let { runCatching { objectMapper.readTree(it.asString()) }.getOrNull() }
            ?.takeIf { it.isObject && !it.isEmpty }
            ?: return null
        return EvidenceParser(objectMapper).parseCondition(json)
    }

    private fun gapsOf(row: ContentMapCapabilityRow): List<String> =
        row.gaps?.let { runCatching { objectMapper.readTree(it.asString()) }.getOrNull() }
            ?.takeIf { it.isArray }
            ?.mapNotNull { it.asText(null) }
            .orEmpty()
}
