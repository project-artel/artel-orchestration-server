package kr.artel.orchestration.testcase.generator

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.contentmap.dto.ContentMapCapabilityRow
import com.fasterxml.jackson.databind.JsonNode
import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.ConditionOverlap
import kr.artel.orchestration.contentmap.evidence.ConditionPrune
import kr.artel.orchestration.contentmap.evidence.GroupKind
import kr.artel.orchestration.contentmap.evidence.EvidenceParser
import kr.artel.orchestration.contentmap.evidence.LoopExits
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
    private val objectRefs: kr.artel.orchestration.contentmap.repository.SceneObjectRefRepository,
    private val objectMapper: ObjectMapper,
) {

    suspend fun generate(contentMapId: Long): List<MapTestCase> {
        val edges = contentMaps.findCallEdges(contentMapId).toList()
        // 매개변수 이름을 호출자가 넘긴 값으로 되돌리려면 필요하다(ARTEL-602).
        val settled = contentMaps.findSettledArguments(contentMapId).toList()
            .groupBy { it.capabilityId }
            .mapValues { (_, args) -> args.associate { it.position to it.value } }
        // **반복하면 닿는 자리**(ARTEL-613). 되돌아가는 갈래의 가드를 뒤집으면 "다 돌고 나온
        // 자리"이고, 그 조건은 지울 것이 아니라 스텝으로 옮길 것이다.
        val exits = LoopExits.of(contentMaps.findLoopingConditions(contentMapId).toList().mapNotNull(::parseText))
        // **효과가 가리키는 것을 사람이 찾을 수 있는 이름으로**(ARTEL-615). 씬이 스스로 말한 것만
        // 쓴다 — 문자열에서 이름을 뽑으면 그 게임에만 맞는 규칙이 된다.
        val refs = objectRefs.findByContentMapId(contentMapId).toList()
            .groupBy { it.ownerType to it.field }
            .mapValues { (_, rows) -> rows.map { it.targetName }.toSet() }
        val drafts = contentMaps.findStepCapabilityRows(contentMapId).toList()
            .flatMap { row -> draftsOf(row, edges, settled, exits, refs) }
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
        drafts.groupBy { it.entryKey }
            .flatMap { (_, ofCapability) -> branches(ofCapability) }
            .map { group ->
                val first = group.first()
                // 이 무리가 함께 서려면 참이어야 하는 것. 바깥의 `settled`(정착 인자)와 다른 값이다.
                val groupCondition = weakest(group.map { it.condition })
                MapTestCase(
                    capabilityKey = first.capabilityKey,
                    scene = first.scene,
                    precondition = MapTestCasePhrasing.precondition(
                        first.scene, groupCondition, first.inputKey,
                    ),
                    // **구조를 버리지 않는다**(ARTEL-627). 위 문장은 이것을 사람 말로 렌더한 것이고,
                    // 소비하는 쪽은 문장이 아니라 이쪽을 읽는다.
                    condition = groupCondition,
                    step = first.step,
                    // **한 기능이 내는 관측들을 함께 적는다.** 저작이 이것을 조작 하나 + 검증 여럿으로
                    // 펴고, 채점은 스텝 단위라 어느 지점이 틀렸는지는 그대로 드러난다. 구버전도 같은
                    // 자리를 ` / ` 로 잇는다.
                    expected = group.map { it.outcome }.distinct().joinToString(OUTCOME_SEPARATOR),
                    status = first.status,
                    gaps = group.flatMap { it.gaps }.distinct(),
                    arrivesAt = group.firstNotNullOfOrNull { it.arrivesAt },
                    // 정체는 **기능과 그 케이스가 덮는 관측들**이다. 문구가 바뀌어도, 관측을 어떤
                    // 문장으로 적든 같은 줄이다(ARTEL-617).
                    identity = (listOf(first.capabilityKey) + group.map { it.identity }.sorted())
                        .joinToString(IDENTITY_SEPARATOR),
                )
            }
            .let(::withInterchangeableInputs)
            .let(::withoutSpecialCases)

    /**
     * **같은 시험을 두 번 시키지 않는다**(ARTEL-645).
     *
     * 위의 [merged] 는 **진입점 안에서만** 묶는다. 그래서 같은 코드가 두 경로로 닿으면 케이스가
     * 둘이 된다 — 근거 문서에서 `methodId` 138개 중 **32개가 진입점을 둘 이상** 가진다.
     *
     * 실측(지도 26)에서 그 대표가 이것이다:
     *
     * ```
     * Story.StoryController.IsAdvanceKeyDown
     *   ← Story.StoryController|Start          조건: 아무 키
     *   ← Tutorial.TutorialController|Update   조건: 아무 키 + 튜토리얼 플래그 둘
     * ```
     *
     * 두 상황이 아니다. 게임 코드에서 `TutorialController : StoryController` — **상속이라 같은
     * 메서드**이고, 튜토리얼 경로가 그 위에 자기 가드를 얹었을 뿐이다. 그래서 나온 두 케이스는
     * 하는 일도, 볼 것도, **기대결과 문장도 글자까지 같다.** 다른 것은 화면에 보이지도 않는
     * 내부 플래그뿐이라 실행하는 사람은 두 줄을 구분할 수 없다.
     *
     * ## 포함될 때만 접는다
     *
     * 조건이 **더 붙은 쪽**은 덜 붙은 쪽의 특수 사례다 — 약한 쪽을 실행하면 그 안에 든다.
     * 어느 쪽도 상대를 포함하지 않으면 **서로 다른 갈래이므로 둘 다 남긴다**:
     *
     * ```
     * stagePosition == 1  ↮  stagePosition == 2     둘 다 남는다
     * ```
     *
     * 그래서 [branches] 의 "결과가 다르면 다른 케이스"와 싸우지 않는다. 저기는 **결과**로 가르고
     * 여기는 **결과가 같을 때** 조건의 포함관계로만 접는다.
     *
     * **지도에서 잃는 것은 없다.** 접히는 것은 케이스 줄이고, 어느 기능들이 그 줄을 덮는지는
     * 기능 쪽에 그대로 남는다.
     */
    private fun withoutSpecialCases(cases: List<MapTestCase>): List<MapTestCase> {
        // **자리로 센다.** [MapTestCase] 는 data class 라 값이 같으면 같은 것으로 취급된다 —
        // 집합에 담아 지우면 다른 자리의 같은 값까지 함께 날아간다.
        val conjuncts = cases.map { asserted(it.condition) }
        val dropped = cases.indices
            .groupBy { Triple(cases[it].scene, cases[it].step, cases[it].expected) }
            .values
            .filter { it.size > 1 }
            .flatMapTo(mutableSetOf()) { sameTrial ->
                sameTrial.filter { mine ->
                    sameTrial.any { other ->
                        other != mine &&
                            conjuncts[other].size < conjuncts[mine].size &&
                            conjuncts[mine].containsAll(conjuncts[other])
                    }
                }
            }
        return cases.filterIndexed { index, _ -> index !in dropped }
    }

    /**
     * 이 조건이 **함께 참이라고 말하는 것들**.
     *
     * 최상위 `그리고` 만 편다. `또는` 은 어느 쪽인지 모르는 것이라 통째로 한 조각으로 둔다 —
     * 펴 버리면 갈래 하나가 다른 갈래를 포함하는 것처럼 보인다.
     */
    private fun asserted(node: ConditionNode?): Set<String> = when (node) {
        null, ConditionNode.Always -> emptySet()
        is ConditionNode.Group ->
            if (node.kind == GroupKind.EVERY) node.parts.flatMapTo(mutableSetOf(), ::asserted)
            else setOf(ConditionPrune.signature(node))
        else -> setOf(ConditionPrune.signature(node))
    }

    /**
     * 한 기능의 줄들을 **함께 볼 수 있는 무리**로 가른다(ARTEL-624).
     *
     * 같은 기능이라도 갈래가 배타적이면 한 번의 실행으로 다 볼 수 없다 — `StagePosition == 5` 면
     * 타이틀로 가고 `!= 5` 면 맵으로 간다. 그 둘은 다른 케이스다.
     *
     * 반대로 배타적이지 않은 것은 **한 번 누르면 함께 일어나는 일**이라 한 케이스다. 실측에서
     * StoryScene 의 아홉 줄이 전부 같은 기능 하나였고, 그중 `입력 차단막이 바뀐다` 는 별개 시험이
     * 아니라 대사를 넘길 때 함께 보는 것이다.
     *
     * 담긴 순서대로 훑어 아무와도 어긋나지 않는 첫 무리에 넣는다 — [ScenarioConflictSplit] 이
     * 시나리오를 가르는 방법과 같다.
     */
    private fun branches(ofCapability: List<Draft>): List<List<Draft>> {
        // **화면이 바뀌는 것은 그 자체로 한 케이스다.** 전환은 그 조작의 결말이라, 같은 화면에서
        // 이어 볼 관측과 한 줄에 담으면 "무엇을 확인하라는 것인지"가 흐려진다 — 전환한 뒤에는
        // 그 화면에 있지도 않다.
        val (moves, stays) = ofCapability.partition { it.arrivesAt != null }
        val groups = mutableListOf<MutableList<Draft>>()
        for (draft in stays) {
            val home = groups.firstOrNull { group ->
                // **조작이 다르면 다른 케이스다.** 한 번에 한 키만 누르므로 함께 볼 수가 없다.
                // 이것을 빼면 진입점이 같은 조작들이 통째로 접힌다 — 실측에서 `MapMove.CharacterMove()`
                // 하나에 매달린 앞·뒤 이동이 한 줄이 되어 `LeftArrow` 가 사라졌다. 대표 문구만 남고
                // 기대결과는 양쪽이 섞여, 오른쪽을 누르고 왼쪽 결과를 기다리는 표가 된다.
                group.first().step == draft.step &&
                    group.all { ConditionOverlap.provablyTogether(it.condition, draft.condition) }
            }
            if (home != null) home += draft else groups += mutableListOf(draft)
        }
        // 전환끼리는 조작과 도착 화면으로 가른다. 같은 키로 같은 곳에 가는 갈래는 한 줄이면 된다.
        return groups + moves.groupBy { it.step to it.arrivesAt }.values.map { it.toList() }
    }

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
     * 효과 한 묶음이 어디서 왔나. 자기 것이거나 공통 호출자를 통해 빌려 온 것이다.
     *
     * @property source 결과를 든 기능. 매개변수를 되돌릴 때 볼 인자가 그쪽 것이다.
     * @property repeats 끝까지 되풀이해야 닿는 자리인가(ARTEL-613).
     */
    private data class Source(
        val condition: ConditionNode?,
        val effects: List<CapabilityEffectEntity>,
        val source: Long,
        val repeats: Boolean,
    )

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
        val arrivesAt: String? = null,
        val identity: String = "",
        /**
         * **플레이어가 무엇을 건드렸나.** 이것이 곧 사람이 "기능 하나"로 세는 단위다.
         *
         * [capabilityKey] 로 세면 안 된다 — 그 키는 적재의 정체라 효과가 사는 메서드(`method_id`)까지
         * 넣고, 넣어야만 한다([CapabilityKey] 의 표). 그래서 **한 기능이 여럿으로 갈린다**: 실측
         * GameClearScene 에서 `GameClearController.Update()` 하나가 `Update` 와 그것이 부르는
         * `ShowGettedCard` 로 갈려 같은 "아무 키나 누른다"가 두 벌 나왔다.
         *
         * 근거 출신이 아니면 null 이라, 그때는 적재의 키로 물러선다.
         */
        val entryKey: String,
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
        exits: Set<LoopExits.Guard>,
        refs: Map<Pair<String, String>, Set<String>>,
    ): List<Draft> {
        // 키가 없는 행은 evidence 출신이 아니다. 케이스가 지도를 되짚을 방법이 없으므로 내지 않는다 —
        // 되짚지 못하는 케이스는 이 개편이 없애려는 바로 그 문자열 맞춤으로 돌아간다.
        val key = row.capabilityKey ?: return emptyList()
        val condition = conditionOf(row)

        val gaps = gapsOf(row)

        // 자기 효과가 먼저다. 없을 때만 공통 호출자를 통해 빌려 온다 — 자기가 결과를 들고 있으면
        // 그것이 이 조작의 결과이고, 남의 것까지 끌어오면 무관한 결과가 붙는다.
        val own = effects.findByCapabilityIdOrderByIdAsc(row.capabilityId).toList()
        val sources: List<Source> =
            if (own.isNotEmpty()) {
                listOf(Source(condition, own, row.capabilityId, LoopExits.reachedByRepeating(condition, exits)))
            } else {
                borrowed(row, condition, edges, exits)
            }

        return sources.flatMap { (situation, effectRows, source, repeats) ->
            // **실행하는 사람이 만들 수 있는 조건만 남긴다**(ARTEL-602). 매개변수 이름은 호출자가
            // 넘긴 값으로 바꾸고, 못 푸는 루프 변수는 빼되 그 사실을 사유로 남긴다.
            val step = MapTestCasePhrasing.step(
                row.interaction, row.inputKey, row.controlLabel, row.controlPath, repeats,
            )
            val settledCondition = MapTestCaseLocals.settle(situation, source, settled)
            val reasons = if (settledCondition.unsettable) gaps + MapTestCaseLocals.UNSETTABLE else gaps
            MapTestCasePhrasing.expectedWithSource(effectRows, refs).map { (outcome, effect) ->
                Draft(
                    capabilityKey = key,
                    // 씬을 함께 든다. 한 타입이 두 씬에 놓이면 진입점이 같아도 다른 자리다 —
                    // 실측에서 `GameClearController` 가 그렇다.
                    entryKey = row.entryId?.let { "${row.sceneName}\u001F$it" } ?: key,
                    scene = row.sceneName,
                    condition = settledCondition.condition,
                    inputKey = row.inputKey,
                    step = step,
                    outcome = outcome,
                    status = row.status,
                    gaps = reasons,
                    // 씬 효과의 대상이 곧 도착 화면이다. 산문에서 다시 뽑지 않는다.
                    arrivesAt = effect.target?.takeIf { effect.kind == SCENE },
                    // **문장이 아니라 지도가 정하는 값으로 정체를 잡는다**(ARTEL-617). 효과는
                    // 되짚기 전 원본을 쓴다 — 대상 이름을 씬이 부르는 것으로 바꿔도(ARTEL-615)
                    // 같은 줄이어야 한다.
                    // **갈래도 정체의 일부다.** 같은 효과가 조건만 달리해 여러 갈래에 나오는 일이
                    // 흔하다 — 실측에서 스테이지마다 같은 카드가 나는 자리가 그렇다. 조건을 빼면 그
                    // 갈래들이 한 정체를 두고 서로를 덮어써 **쓰는 쪽에서 조용히 사라진다**(46→42).
                    // 문구가 아니라 구조를 쓰므로 표현을 다듬어도 같은 줄로 남는다.
                    identity = listOf(
                        key, effect.kind, effect.target.orEmpty(), effect.detail.orEmpty(),
                        ConditionPrune.signature(settledCondition.condition),
                    )
                        // NUL 은 Postgres 텍스트에 못 들어간다. 구분자는 사람이 안 쓰는 제어문자로.
                        .joinToString(IDENTITY_SEPARATOR),
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
        exits: Set<LoopExits.Guard>,
    ): List<Source> =
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
            //
            // 넷째 칸이 **버린 호출자 조건을 되살리는 자리**다(ARTEL-613). 그 조건은 문장에 안
            // 싣기로 했지만(코루틴이 몇 번째 대사를 넘겼는지는 테스터가 만들 것이 아니다),
            // 그것이 **루프를 다 돌고 나온 자리**라면 이야기가 다르다 — 끝까지 누르면 닿는다.
            // 버릴 것이 아니라 스텝 문구로 바꿀 것이다.
            Source(
                MapTestCasePhrasing.both(condition, ownCondition),
                rows,
                borrowed.capabilityId,
                LoopExits.reachedByRepeating(callerCondition, exits) ||
                    LoopExits.reachedByRepeating(ownCondition, exits),
            )
        }

    private fun parseText(text: String?): ConditionNode? {
        val node: JsonNode = text
            ?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }
            ?.takeIf { it.isObject && !it.isEmpty }
            ?: return null
        return EvidenceParser(objectMapper).parseCondition(node)
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

    /** 효과 어휘의 씬 전환. `MapTestCasePhrasing` 이 같은 값으로 문장을 만든다. */
    private companion object {
        const val SCENE = "scene"

        /** 정체를 이을 때 쓰는 구분자. 값에 섞일 일이 없고 Postgres 가 받는 문자여야 한다. */
        const val IDENTITY_SEPARATOR = "\u001F"

        /** 한 케이스가 여러 관측을 낼 때 잇는 말. 구버전과 같은 모양이라 읽는 쪽이 안 헷갈린다. */
        const val OUTCOME_SEPARATOR = " / "
    }

    private fun gapsOf(row: ContentMapCapabilityRow): List<String> =
        row.gaps?.let { runCatching { objectMapper.readTree(it.asString()) }.getOrNull() }
            ?.takeIf { it.isArray }
            ?.mapNotNull { it.asText(null) }
            .orEmpty()
}
