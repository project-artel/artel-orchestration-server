package kr.artel.orchestration.testcase.generator

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.contentmap.dto.ContentMapCapabilityRow
import kr.artel.orchestration.contentmap.dto.ContentMapObservationRow
import kr.artel.orchestration.contentmap.dto.asCapabilityRow
import com.fasterxml.jackson.databind.JsonNode
import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.testscenario.service.ScenarioStateReader
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
        // **`Component` 가 가리키는 것을 되돌리려면 그 코드가 어느 타입에 붙어 있는지 알아야 한다**
        // (`MapTestCaseTargets.ofOwner`). 지도 하나치를 한 번에 들고 돈다.
        val owners = contentMaps.findCapabilityOwners(contentMapId).toList()
            .associate { it.capabilityId to it.methodId }
        // **씬이 이름으로 아는 것들**. `GameObject.Find("이름")` 을 맞춰 보는 데 쓴다
        // ([MapTestCaseTargets.ofScene]) — 뽑는 것이 아니라 맞추는 것이라 안 맞으면 그대로 둔다.
        val namesByScene = contentMaps.findSceneObjectNames(contentMapId).toList()
            .groupBy({ it.sceneName }, { it.path })
            .mapValues { (_, paths) -> paths.toSet() }
        val refs = objectRefs.findByContentMapId(contentMapId).toList()
            .groupBy { it.ownerType to it.field }
            .mapValues { (_, rows) -> rows.map { it.targetName }.toSet() }
        // **읽는 곳은 하나다.** 앞서 창구가 셋이었고(조작 · 관측 · 존재 확인) 각자 다른 잣대를
        // 손으로 들고 있었다. 무엇을 남길지는 이제 질의가 정한다([ContentMapRepository.findTestCaseRows]).
        //
        // **게임이 스스로 하는 일도 케이스다**(ARTEL-681). 누를 것이 없으면 관측으로 적는데, 그
        // 갈림은 행이 스스로 말한다 — `actionability` 가 `not-a-step` 이면 볼 것이고 아니면
        // 누를 것이다. 아래(효과 읽기 · 갈래 펴기 · 합치기)는 두 벌이 되지 않는다.
        val drafts = contentMaps.findTestCaseRows(contentMapId).toList()
            .flatMap { row -> draftsOf(row, edges, settled, exits, refs, owners, namesByScene) }
        return merged(drafts) + screenElements(contentMapId)
            // **언제 볼지 못 적으면 내지 않는다**(ARTEL-681). 문서가 값을 못 읽은 자리는
            // `(not a literal)` 로 적혀 있는데, 관측은 그 조건이 곧 "언제 확인하나"라서 그것을
            // 못 적으면 케이스가 아니다. 조작은 눌러 보면 되지만 관측은 볼 시점이 전부다.
            .filterNot {
                it.watching &&
                    (it.precondition.contains(UNREADABLE) || it.expected.contains(UNREADABLE))
            }
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
                // **이름과 기대결과가 같은 목록을 센다.** 이름의 `외 N건` 이 기대결과의 ` / ` 수와
                // 어긋나면 읽는 사람이 하나를 못 찾는다. 같은 자리에서 한 번만 접는다.
                val shown = group.distinctBy { it.outcome }
                MapTestCase(
                    capabilityKey = first.capabilityKey,
                    scene = first.scene,
                    precondition = MapTestCasePhrasing.precondition(
                        first.scene, groupCondition, first.inputKey,
                    ),
                    // **구조를 버리지 않는다**(ARTEL-627). 위 문장은 이것을 사람 말로 렌더한 것이고,
                    // 소비하는 쪽은 문장이 아니라 이쪽을 읽는다.
                    condition = groupCondition,
                    aimedAt = first.aimedAt,
                    // **이름은 조작이 아니라 시험이다**([MapTestCasePhrasing.trial]). 무엇이
                    // 일어나는지는 무리가 다 모여야 알 수 있어서 여기서 짓는다. 관측은 부를 조작이
                    // 없으니 제 문장을 그대로 쓴다.
                    step = MapTestCasePhrasing.trial(
                        first.act, first.repeats, shown.mapNotNull { d -> d.does },
                    ),
                    watching = first.watching,
                    // **한 기능이 내는 관측들을 함께 적는다.** 저작이 이것을 조작 하나 + 검증 여럿으로
                    // 펴고, 채점은 스텝 단위라 어느 지점이 틀렸는지는 그대로 드러난다. 구버전도 같은
                    // 자리를 ` / ` 로 잇는다.
                    expected = shown.joinToString(OUTCOME_SEPARATOR) { it.outcome },
                    expectedItems = shown.map { it.outcome },
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
            .let(::withBranchesApart)
            // **갈래를 펴다 보면 서로 다른 케이스의 갈래가 같아진다.** 같은 기능이 같은 전제에서
            // 같은 것을 확인하라고 두 줄로 나오면 실행하는 사람은 같은 일을 두 번 한다.
            .distinctBy { listOf(it.capabilityKey, it.precondition, it.step, it.expected) }
            .let(::withTellingSteps)

    /**
     * **갈래는 갈래대로 낸다**(ARTEL-667).
     *
     * 전제가 `또는` 로 뭉쳐 있으면 그 케이스는 **"언제" 되는지를 말하지 않는다.** 요구로 세어지는
     * 것은 모든 갈래에 함께 있는 것뿐이라(그 규칙은 맞다 — 하나만 만족해도 되니까) 대개
     * `IsLocked == 0` 하나만 남는다. 실측(프로젝트 30):
     *
     * ```
     * 1873   IsLocked == 0 그리고 (위치 == 3 또는 (진행도 >= 4 그리고 위치 == 3) 또는 진행도 == 5)
     * ```
     *
     * 세 갈래가 **사람이 고르는 갈래가 아니라 같은 코드에 닿는 세 경로**다. 그래서 실행하는 사람은
     * 무엇을 준비할지 알 수 없고, 흐름 계산은 이 자리들을 서로 구별하지 못해 순서를 임의로 놓는다 —
     * 지도의 `Return` 넷이 점수가 전부 같았고(런 242) 진행도가 5 → 4 → 3 → 2 로 거꾸로 잡혔다.
     *
     * 갈래마다 한 줄을 낸다. `A 그리고 (B 또는 C)` 는 `A 그리고 B` · `A 그리고 C` 두 줄이다.
     * 결과가 같아도 **준비할 것이 다르면 다른 시험**이다.
     *
     * **너무 많이 갈리면 그대로 둔다.** 갈래가 곱해지는 자리에서 케이스가 폭발하는 것이
     * 이 저장소가 [merged] 로 이미 한 번 겪은 일이다.
     */
    private fun withBranchesApart(cases: List<MapTestCase>): List<MapTestCase> =
        cases.flatMap { case ->
            val branches = spread(case.condition)
            if (branches.size <= 1 || branches.size > MAX_BRANCHES) return@flatMap listOf(case)
            branches.distinctBy(ConditionPrune::signature).map { branch ->
                case.copy(
                    condition = branch,
                    precondition = MapTestCasePhrasing.precondition(case.scene, branch),
                    // **정체가 갈라져야 저장이 두 줄로 앉는다.** 같은 정체면 뒤엣것이 앞엣것으로
                    // 덮여 한 줄만 남는다.
                    identity = case.identity + IDENTITY_SEPARATOR + ConditionPrune.signature(branch),
                )
            }
        }

    /**
     * 전제를 **갈래마다 하나씩** 편다. `A 그리고 (B 또는 C)` → `A 그리고 B`, `A 그리고 C`.
     *
     * 갈래가 없으면 하나다. 그때는 부르는 쪽이 원래 케이스를 그대로 쓴다.
     */
    private fun spread(node: ConditionNode?): List<ConditionNode> = when (node) {
        null -> emptyList()
        is ConditionNode.Group -> when (node.kind) {
            GroupKind.EITHER -> node.parts.flatMap { spread(it) }
            GroupKind.EVERY -> node.parts
                .fold(listOf(emptyList<ConditionNode>())) { grown, part ->
                    val options = spread(part).ifEmpty { listOf(part) }
                    grown.flatMap { so -> options.map { so + it } }
                }
                .map { parts -> if (parts.size == 1) parts.single() else ConditionNode.Group(GroupKind.EVERY, parts) }
        }
        else -> listOf(node)
    }

    /**
     * **형제끼리 무엇이 다른지를 조작 문장에 적는다**(ARTEL-662).
     *
     * 같은 화면에서 같은 조작을 하는 케이스가 여럿이고, 갈리는 것은 전제뿐이다. 그런데 전제는
     * 조작 문장에 안 드러나서 **글자로는 구별이 안 된다.** 실측(프로젝트 24)에서 42건 중 40건이
     * 그렇고, 유일한 것은 2건뿐이다:
     *
     * ```
     * Map_scene | `Return` 키를 누른다                      7건
     * GameClearScene | 아무 키나 누른다                      5건
     * StoryScene | 아무 키나 누른다 — 더 진행되지 않을 때까지  4건
     * ```
     *
     * 대가가 저작에 그대로 나왔다(런 236). 모델이 `저장 데이터가 있는` 케이스에 *"저장 데이터가
     * 없는 상태로"* 라고 썼고, 바로 아래 진짜 없는 경우와 **같은 문장**이 됐다. 프롬프트에는 전제도
     * 기대결과도 다 있었으니 **몰라서가 아니라 구별이 안 돼서다.**
     *
     * **형제 사이에서만 붙인다.** 유일한 케이스에까지 붙이면 문장이 길어지기만 한다. 그리고 형제가
     * **공통으로** 가진 전제도 빼고 **갈리는 것만** 적는다 — 다 적으면 전제를 통째로 두 번 쓰는 셈이다.
     */
    private fun withTellingSteps(cases: List<MapTestCase>): List<MapTestCase> {
        val siblings = cases.groupBy { it.scene to it.step }
        return cases.map { case ->
            val group = siblings.getValue(case.scene to case.step)
            if (group.size < 2) return@map case
            val shared = group.map { comparisonsIn(it.condition) }.reduce { a, b -> a intersect b }
            val others = group.filterNot { it === case }.map { comparisonsIn(it.condition) }
            // **덜 가진 것부터 적는다.** 형제가 셋 이상이면 공통이 아니어도 대부분이 가진 비교가
            // 있고, 그것은 이 케이스를 옆줄과 갈라 주지 못한다. 가나다순으로 자르던 판에서
            // 실측 85건 중 6건이 **이름이 같은 채로** 남았다 — 갈라 주는 비교가 [MAX_TELLING]
            // 밖으로 밀려난 것이다. 같은 수면 가나다순으로 갈라 이름이 실행마다 흔들리지 않게 한다.
            val telling = (comparisonsIn(case.condition) - shared)
                .sortedWith(compareBy({ c -> others.count { c in it } }, { it }))
                .take(MAX_TELLING)
            if (telling.isEmpty()) case
            else case.copy(step = "${case.step} (${telling.joinToString(", ")} 일 때)")
        }
    }

    /**
     * 전제에 나오는 **모든 비교.** `또는` 갈래 안까지 센다.
     *
     * [ScenarioStateReader.guardsIn] 과 일부러 다르다. 그쪽은 *"무엇이 참이어야 하나"* 에 답하므로
     * 갈래에서는 교집합만 요구로 세는 것이 맞다. 여기서 묻는 것은 *"이것이 형제와 무엇이 다른가"* 라,
     * **갈래 안의 차이도 차이다** — 실측(지도 27)에서 `Map_scene` 의 `Return` 일곱 건이 교집합으로는
     * 전부 `IsLocked == 0` 하나로 같아져 구별이 안 됐다.
     */
    private fun comparisonsIn(node: ConditionNode?): Set<String> = when (node) {
        null, is ConditionNode.Always, is ConditionNode.Gesture, is ConditionNode.Unknown -> emptySet()
        is ConditionNode.Test ->
            setOf("${shortName(node.left)} ${node.operator} ${shortName(node.right)}")
        is ConditionNode.Group -> node.parts.flatMapTo(mutableSetOf()) { comparisonsIn(it) }
    }

    /**
     * 소유자 접두를 떼어 읽을 만하게 만든다. `MapMove.position` 은 `position` 이 된다.
     *
     * [ScenarioStateReader.normalize] 를 쓰지 않는다. 그쪽은 마지막 점 뒤만 남기는데, 그 규칙은
     * `이름.속성` 에만 맞고 식에 쓰면 부서진다. 실측 85건에서:
     *
     * ```
     * (MapMove.StagePosition - 1)                    →  StagePosition - 1)     괄호가 안 맞는다
     * collision.gameObject.CompareTag(enemy.tag)     →  tag)                   뜻이 없다
     * ```
     *
     * 앞엣것이 10건이었고, 케이스 이름 한가운데에 짝 없는 닫는 괄호가 섰다. 저쪽 함수를 고쳐서
     * 될 일이 아니다 — 그것은 상태 판정이 쓰는 변수 키라 스무 곳이 물려 있고, 표시가 보기 싫다고
     * 판정의 키를 바꾸면 길찾기가 조용히 달라진다.
     *
     * **괄호 밖의 마지막 점에서만 자른다.** 괄호 안의 점은 남의 것이라 세지 않는다. 그래서 위의
     * 둘은 각각 통째로, `CompareTag(enemy.tag)` 로 남는다.
     *
     * 소수는 건드리지 않는다 — `1.5` 의 점은 소유자를 가르는 점이 아니다.
     */
    private fun shortName(expr: String): String {
        val text = expr.trim().trim('`')
        if (NUMBER.matches(text)) return text
        var depth = 0
        var lastDot = -1
        text.forEachIndexed { i, ch ->
            when (ch) {
                '(', '[' -> depth++
                ')', ']' -> depth--
                '.' -> if (depth == 0) lastDot = i
            }
        }
        return if (lastDot >= 0) text.substring(lastDot + 1) else text
    }

    private val NUMBER = Regex("""-?\d+(\.\d+)?""")

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
        // **누르는 것과 보는 것은 바꿔 쓸 수 없다**(ARTEL-681). 같은 화면에서 같은 결과를 낸다고
        // 해서 "아무 키나 누른다 또는 진입해 관찰한다"로 이으면 무엇을 하라는 것인지 알 수 없다.
        // 조작끼리·관측끼리만 바꿔 쓸 수 있다.
        //
        // **조준 대상이 다르면 다른 조작이다.** 이 함수는 같은 것을 여는 다른 키를 한 줄로 담으려고
        // 만들었는데(`RightArrow` 와 `UpArrow`), 대상까지 안 보니 서로 다른 버튼도 합쳤다.
        // 실측(word-venture, 2026-09-01)에서 `Canvas/MapSceneButton`(새로 시작)과
        // `Canvas/continue`(이어하기)가 한 줄이 됐고, 같은 표의 다른 줄이
        // *"저장 데이터가 없으면 `Canvas/continue` 는 표시 상태가 false"* 라고 말한다 —
        // **화면에 없는 것을 누르라고 적은 것이다.** 지도는 둘을 구별한다:
        // `MapSceneButton` 만 `InitPlayData()` 를 부른다.
        //
        // 합쳐진 줄은 `capabilityKey` 를 하나만 들어서, 나머지 조작의 효과가 저작에 닿지도 않았다.
        cases.groupBy {
            listOf(it.scene, it.precondition, it.expected, it.watching, it.aimedAt)
        }
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
        /** 무엇을 겨누나(`control_path`). 없으면 키 입력이거나 관측이다. */
        val aimedAt: String?,
        val step: String,
        /** 조작(또는 관측의 "그 화면에 있기")을 종결형·연결형 두 벌로. */
        val act: MapTestCasePhrasing.Act,
        /** 누를 것이 없어 보기만 하는 줄인가. */
        val watching: Boolean = false,
        /** 끝까지 되풀이해야 닿는 자리인가(ARTEL-613). 이름을 지을 때 다시 쓴다. */
        val repeats: Boolean = false,
        /** 이 효과를 이름에 붙일 능동꼴. 무리를 지어 세려면 종류까지 들어야 한다. */
        val does: MapTestCasePhrasing.Doing? = null,
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
        owners: Map<Long, String>,
        namesByScene: Map<String, Set<String>>,
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
            // **누를 것이 있으면 조작, 없으면 관측이다.** 행이 스스로 말하는 것이라 부르는 쪽이
            // 따로 알려 줄 것이 없다(ARTEL-681).
            // **누를 것이 있으면 조작, 없으면 관측이다.** 행이 스스로 말하는 것이라 부르는 쪽이
            // 따로 알려 줄 것이 없다(ARTEL-681). 이름의 모양은 양쪽이 같다 — 관측에서는 사람이
            // 하는 일이 "그 화면에 있는 것"뿐이라 그것이 조작 자리에 온다.
            val watching = row.actionability == NOT_A_STEP
            val act = if (watching) {
                MapTestCasePhrasing.watching(row.sceneName, row.triggerRoot)
            } else {
                MapTestCasePhrasing.act(
                    row.interaction, row.inputKey, row.controlLabel, row.controlPath,
                )
            }
            // 결과를 뺀 조작만의 문장. 이름은 [merged] 가 무리를 지은 뒤에 짓는다 — 무엇이
            // 일어나는지는 그 무리가 다 모여야 알 수 있다. 여기 것은 **갈래를 가르는 열쇠**로
            // 쓴다([branches] 가 "조작이 다르면 다른 케이스"를 이것으로 본다).
            val step = MapTestCasePhrasing.trial(act, repeats)
            val settledCondition = MapTestCaseLocals.settle(situation, source, settled)
            val reasons = if (settledCondition.unsettable) gaps + MapTestCaseLocals.UNSETTABLE else gaps
            // 효과의 주인은 **그 효과를 든 기능**이다. 빌려 온 자리(`borrowed`)에서는 이 행이 아니다.
            MapTestCasePhrasing.expectedWithSource(
                effectRows, refs, owners[source], namesByScene[row.sceneName].orEmpty(),
            ).map { (outcome, does, effect) ->
                Draft(
                    capabilityKey = key,
                    act = act,
                    watching = watching,
                    repeats = repeats,
                    does = does,
                    // 씬을 함께 든다. 한 타입이 두 씬에 놓이면 진입점이 같아도 다른 자리다 —
                    // 실측에서 `GameClearController` 가 그렇다.
                    entryKey = row.entryId?.let { "${row.sceneName}\u001F$it" } ?: key,
                    scene = row.sceneName,
                    condition = settledCondition.condition,
                    inputKey = row.inputKey,
                    aimedAt = row.controlPath,
                    step = step,
                    outcome = outcome,
                    status = caseGrade(row),
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
    /**
     * **화면에 무엇이 붙어 있나**(ARTEL-683).
     *
     * 효과에서만 케이스를 만들면 *"그 버튼이 보이는가"* 가 빠진다 — 그냥 있는 것은 바뀌는 것이
     * 아니기 때문이다. 구버전(specs_v2)이 `control_check` 로 내던 자리다.
     *
     * 무엇이 보이는지까지는 말하지 않는다. 지도가 아는 것은 **그 자리에 그것이 있다**는 것뿐이고,
     * 켜져 있는지 꺼져 있는지는 조건에 따라 달라져 관측 케이스가 따로 말한다.
     *
     * 갈래도 전제도 없다. 그래서 [merged] 를 거치지 않고 그대로 낸다 — 접을 것이 없다.
     */
    private suspend fun screenElements(contentMapId: Long): List<MapTestCase> =
        contentMaps.findScreenElements(contentMapId).toList().map { element ->
            MapTestCase(
                // 지도의 기능에서 나온 것이 아니라 씬의 오브젝트에서 나왔다. 되짚을 열쇠는 그 경로다.
                capabilityKey = "screen\u001F${element.sceneName}\u001F${element.path}",
                scene = element.sceneName,
                precondition = "${element.sceneName} 화면인 상태",
                condition = null,
                step = "`${element.path}` 이(가) 화면에 있는지 확인한다",
                expected = "`${element.path}` 이(가) 화면에 있다",
                expectedItems = listOf("`${element.path}` 이(가) 화면에 있다"),
                status = "runnable",
                identity = "screen\u001F${element.sceneName}\u001F${element.path}",
            )
        }

    /**
     * 이 관측을 케이스로 낼까(ARTEL-681).
     *
     * 셋으로 거른다 — 무엇이 일으키나, 볼 것이 있나, 여기 있다는 것이 확인됐나.
     *
     * ## 무엇이 일으키나는 `trigger_kind` 가 답한다
     *
     * 앞서는 메서드 이름 목록(`WATCHED_ROOTS`)으로 걸렀다. 손으로 적은 목록이라 두 가지를
     * 놓쳤다. 컴파일러가 코루틴 이름을 바꾸면 목록에 없고(`Start1`), 목록에 안 적어 둔 Unity
     * 콜백도 통째로 빠진다. 실측(word-venture, 2026-09-01)에서 `OnTriggerEnter2D` 12 ·
     * `OnMouseEnter` 2 · `OnMouseExit` 2 · `OnDrag` 1 · `OnEndDrag` 1 이 그렇게 빠져 있었다.
     *
     * 빠진 것들이 사소하지 않다. `OnTriggerEnter2D` 는 `Player.HpText.text` 가 줄고 죽으면
     * `GameOverScene` 으로 가는 것이고, `OnMouseEnter` 는 마우스를 올리면 1.2배가 되는 것이다.
     * 구버전(specs_v2)도 이 자리를 12건 냈다.
     *
     * `trigger_kind` 는 적재기가 근거에서 읽는 값이라 목록을 손으로 늘릴 일이 없다.
     * `lifecycle` 은 **Unity 가 부르는 콜백**이고 `unity-event` 는 **게임이 인스펙터로 연결한
     * 자기 메서드**다. 뒤엣것은 내지 않는다 — 사람이 부를 수 있는 자리가 아니고, 실측에서
     * `CardMouseUp` 과 `CardAlignment` 이 `this.transform` 세 줄을 똑같이 냈다.
     *
     * ## 확인 안 된 자리는 그 `scene` 의 사실이 아니다
     *
     * `persistent-unconfirmed` 는 `scene` 을 넘어 살아남는 오브젝트가 거기 있다는 사실만 말한다
     * ([ContentMapCapabilityRow.scenePresence] 가 TC 생성기를 지목해 적어 두었다). 실측에서
     * `SaveLoadController` 하나가 여섯 `scene` 에 앉아 *"`this.gameObject` 이(가) 사라진다"* 를
     * 여섯 벌 냈다 — 아무도 확인한 적 없고, 실행하는 사람이 찾을 이름도 아니다.
     */
    /**
     * **케이스로서의 등급**(ARTEL-681).
     *
     * 지도의 `status` 를 그대로 쓰면 안 된다. 그것은 *"이 기능이 단독 명세가 되나"* 에 답하는
     * 값이고, `SpecStatus.derive` 가 `actionability` 를 맨 앞에 두어 `not-a-step` 이 다른 축을
     * 이긴다. 그래서 누를 것이 없는 행은 눈에 보이는 효과를 들고 있어도 `not-a-step` 이다.
     *
     * 케이스가 묻는 것은 다르다 — **실행하는 사람이 이 줄을 돌릴 수 있나.** 누를 것이 없어도
     * 볼 것이 있으면 돌릴 수 있다. 보면 된다.
     *
     * 그대로 실었더니 실측(2026-09-01)에서 `test_case.status` 가 `not-a-step` 인 줄이 70 건
     * 나갔다. 화면은 이 값을 등급으로 보여 주므로 *"단계가 아님"* 이라고 적힌 케이스가 표에 선다.
     */
    private fun caseGrade(row: ContentMapCapabilityRow): String =
        if (row.actionability == NOT_A_STEP) "runnable" else row.status

    private suspend fun keptAsObservation(row: ContentMapObservationRow): Boolean {
        if (row.triggerKind != ENGINE_CALLBACK) return false
        if (row.scenePresence == UNCONFIRMED_HERE) return false
        return effects.findByCapabilityIdOrderByIdAsc(row.capabilityId).toList()
            .any { it.kind in VISIBLE }
    }

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
        /**
         * 조작 문장에 붙이는 **가르는 조건의 최대 개수**. 다 붙이면 전제를 통째로 두 번 쓰는 셈이고,
         * 목록에서 눈으로 훑을 수 있는 길이를 넘긴다. 구별에 필요한 것은 대개 하나둘이다.
         */
        private const val MAX_TELLING = 3

        /**
         * 한 케이스를 몇 갈래까지 펼 것인가(ARTEL-667). 갈래가 곱해지는 자리에서 케이스가
         * 폭발하는 것은 이 저장소가 이미 한 번 겪은 일이라, 넘으면 뭉친 채로 둔다.
         */
        private const val MAX_BRANCHES = 4

        const val SCENE = "scene"

        /** 누를 것이 없는 자리(ARTEL-681). */
        const val NOT_A_STEP = "not-a-step"

        /**
         * **화면에 보이는 효과만 케이스가 된다**(ARTEL-681).
         *
         * 값만 바뀌는 것(`write`)은 실행하는 사람이 눈으로 확인할 수 없고, 소리·애니메이션은 지금
         * 판정할 수단이 없다. 수단이 생기면 그때 넣는다.
         */
        val VISIBLE = setOf(SCENE, "ui-value", "active-state", "transform", "instantiate", "destroy")

        /**
         * Unity 가 부르는 콜백(`capability_evidence.trigger_kind`). 다른 값은 `unity-event` 로,
         * 게임이 인스펙터로 연결한 자기 메서드다 — 그것은 사람이 그 순간을 만들 수 없어서
         * 관측 케이스로 내지 않는다([ContentMapRepository.findTestCaseRows] 가 거른다).
         */
        const val ENGINE_CALLBACK = "lifecycle"

        /** 살아남아 거기 있을 뿐, 여기서 되는지는 아직 아무도 안 본 자리. */
        const val UNCONFIRMED_HERE = "persistent-unconfirmed"

        /** 문서가 값을 못 읽은 자리에 적어 두는 글자. */
        const val UNREADABLE = "(not a"

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
