package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.contentmap.entity.Actionability
import kr.artel.orchestration.contentmap.entity.AnalysisConfidence
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.entity.Interaction
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneEdgeRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testscenario.repository.ScenarioPathRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * "이 케이스 다음에 저 케이스를 하려면 사이에 무엇을 해야 하나"에 답한다(ARTEL-466).
 *
 * **경로 탐색을 모델에게 시키지 않으려고 있는 서비스다.** 같은 지식을 프롬프트에 실어 주면 읽을
 * 때도 있고 안 읽을 때도 있다 — 실측(2026-08-18)에서 씬 명세를 프롬프트에 실은 쪽이 **아예 없는
 * 쪽보다 나빴고**(75.0% vs 66.7%), 툴로 두고 여기서 계산해 답만 주자 0%가 됐다. 빠짐없이 훑는
 * 일과 길을 찾는 일은 모델이 못하는 종류라는 것이 이 저장소에서 이미 한 번 확인된 판단이다
 * ([TestCaseRepository.findUncoveredIdsByProjectId]를 툴로 만든 것과 같다).
 *
 * **모른다고 답하는 것이 이 서비스의 절반이다.** 씬 명세는 근거 문서와 관측에서 나오고 둘 다
 * 전량이 아니므로, 간선이 없다는 것이 그런 길이 없다는 뜻이 되지 못한다. [ScenarioPathResult.UNKNOWN]은
 * "불가"가 아니라 "모른다"이고, 무엇이 막는지를 함께 낸다 — 그 이름이 곧 사용자에게 물어야 할
 * 질문이자 플레이로 알아내야 할 자리다.
 */
@Service
class ScenarioPathService(
    private val objectMapper: ObjectMapper,
    private val testCaseRepository: TestCaseRepository,
    private val buildRepository: GameBuildRepository,
    private val contentMapRepository: ContentMapRepository,
    private val sceneRepository: SceneRepository,
    private val sceneEdgeRepository: SceneEdgeRepository,
    private val capabilityRepository: CapabilityRepository,
    private val pathRepository: ScenarioPathRepository,
) {
    private val logger = LoggerFactory.getLogger(ScenarioPathService::class.java)

    /**
     * 두 케이스 사이에 무엇이 필요한가. 씬 명세가 없는 프로젝트면 [ScenarioPathResult.UNKNOWN]이다 —
     * 그것도 정직한 답이라 예외를 던지지 않는다.
     */
    suspend fun findPath(
        projectId: Long,
        appUserId: Long,
        fromTestCaseId: Long,
        toTestCaseId: Long,
    ): ScenarioPathAnswer {
        val from = testCaseRepository.findById(fromTestCaseId)
        val to = testCaseRepository.findById(toTestCaseId)
        if (from == null || from.projectId != projectId) return unknownCase(fromTestCaseId)
        if (to == null || to.projectId != projectId) return unknownCase(toTestCaseId)

        val contentMapId = contentMapIdOf(projectId, appUserId)
            ?: return ScenarioPathAnswer(
                ScenarioPathResult.UNKNOWN,
                blockedBy = "content-map",
                note = "이 프로젝트에는 씬 명세가 아직 없다.",
            )

        val fromScene = ScenarioStateReader.sceneOf(from)
        val toScene = ScenarioStateReader.sceneOf(to)
        // 출발은 **그 케이스를 실행한 뒤**다. 사전조건이 보장한 것에 그 케이스가 바꾼 것을 얹는다.
        val fromAfter = ScenarioStateReader.stateAfter(from, objectMapper)
        val state = ScenarioStateReader.knownValuesOf(from.precondition) + fromAfter
        val want = ScenarioStateReader.guardsOf(to.precondition)
        // 순서가 뒤바뀐 것인지는 **메우는 일과 다른 질문**이다. 여기서 함께 답해 두면 뒤집으면
        // 이어지는 자리를 조용히 이동 스텝으로 덮지 않을 수 있다.
        val ordering = ScenarioOrderCheck.verdict(
            fromAfter = fromAfter,
            fromBefore = ScenarioStateReader.guardsOf(from.precondition),
            toAfter = ScenarioStateReader.stateAfter(to, objectMapper),
            toBefore = want,
        )

        val steps = mutableListOf<PathStep>()

        if (fromScene != null && toScene != null && fromScene != toScene) {
            val hop = sceneHop(contentMapId, fromScene, toScene, state)
            if (hop !is Hop.By) {
                return withOrdering(ordering, when (hop) {
                    is Hop.Blocked -> ScenarioPathAnswer(
                        ScenarioPathResult.UNKNOWN,
                        blockedBy = hop.by.variable,
                        note = "$fromScene 에서 $toScene 으로 가는 조작은 있으나 그 조작이 " +
                            "${hop.by.variable} ${hop.by.operator} ${hop.by.value} 를 요구한다. 지금은 그렇지 않다.",
                    )
                    Hop.Automatic -> ScenarioPathAnswer(
                        ScenarioPathResult.UNKNOWN,
                        blockedBy = "$fromScene→$toScene",
                        note = "$fromScene 에서 $toScene 으로 저절로 넘어가는 전이는 있으나 조작으로 " +
                            "지시할 수 없다. 무엇을 해야 그 전이가 일어나는지는 명세에 없다.",
                    )
                    else -> ScenarioPathAnswer(
                        ScenarioPathResult.UNKNOWN,
                        blockedBy = "$fromScene→$toScene",
                        note = "$fromScene 에서 $toScene 으로 가는 조작이 명세에 없다.",
                    )
                })
            }
            steps += hop.step
            // **씬을 넘으면 알던 변수 값을 버린다.** 화면이 바뀐 뒤 무엇이 유지되는지 명세가
            // 말해 주지 않으므로, 유지된다고 치는 것은 지어내는 것이다.
            return withOrdering(ordering, resolveGuards(contentMapId, emptyMap(), want, steps))
        }

        return withOrdering(ordering, resolveGuards(contentMapId, state, want, steps))
    }

    /**
     * [ids] 중 **이 프로젝트 지도에 살아 있는** 기능만 골라 돌려준다(ARTEL-467).
     *
     * 시나리오가 인용한 기능 번호가 실재하는지 보려고 있다. 실재를 안 보면 없는 번호를 적는 것이
     * 가장 싼 통과 방법이 되고, 그러면 근거 필드가 근거가 아니라 형식이 된다 — 없는 케이스 번호를
     * 다루는 방식([ScenarioCoverageAudit]의 ghost)과 같은 이유다.
     *
     * `merged_into` 가 찍힌 기능은 죽은 것으로 본다. 관측으로 발견한 것이 나중에 근거로도 확인되면
     * 한쪽으로 합쳐지고, 합쳐진 쪽은 더 이상 가리킬 대상이 아니다.
     *
     * 지도가 없으면 **전부 살아 있다고 답한다.** 확인하지 못한 것을 없다고 말할 수는 없다.
     */
    suspend fun liveCapabilities(projectId: Long, appUserId: Long, ids: Collection<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        val unique = ids.toSet()
        val contentMapId = contentMapIdOf(projectId, appUserId) ?: return unique
        return unique.filterTo(mutableSetOf()) { id ->
            val capability = capabilityRepository.findById(id)
            capability != null && capability.mergedInto == null &&
                sceneRepository.findById(capability.sceneId)?.contentMapId == contentMapId
        }
    }

    // ---- 가드 해소 -------------------------------------------------------------------

    /**
     * 도착이 요구하는 가드 중 지금 상태와 **어긋나는 것**을 하나씩 푼다.
     *
     * 값을 모르는 변수는 어긋난다고 보지 않는다. 모르는 것을 위반으로 세면 거의 모든 전이가
     * [ScenarioPathResult.UNKNOWN]이 되어 답이 쓸모없어진다.
     */
    private suspend fun resolveGuards(
        contentMapId: Long,
        state: Map<String, String>,
        want: List<Guard>,
        steps: MutableList<PathStep>,
    ): ScenarioPathAnswer {
        val unmet = want.filter { g ->
            val have = state[g.variable]
            have != null && !g.holds(have)
        }
        if (unmet.isEmpty()) {
            return if (steps.isEmpty()) ScenarioPathAnswer(ScenarioPathResult.NOT_REQUIRED)
            else answer(steps)
        }

        for (guard in unmet) {
            when (val writer = writerFor(contentMapId, guard, state)) {
                is Writer.By -> steps += writer.step
                // 그 값을 쓰는 조작은 있는데 **그 조작 자신이** 지금 못 하는 경우다(ARTEL-466).
                // 실제 지도에서 맵 조작 전부가 `InteractionLock.IsLocked == 0` 을 요구하고,
                // `RightArrow` 는 `position == 0` 일 때만 성립한다. 이것을 안 보면 코드가 스스로
                // 실행 불가 스텝을 끼워 넣는다 — 없애려던 것을 다른 자리에서 만드는 셈이다.
                is Writer.Blocked -> return ScenarioPathAnswer(
                    ScenarioPathResult.UNKNOWN,
                    capabilityIds = steps.map { it.capabilityId },
                    actions = steps.map { it.action },
                    inputs = steps.map { it.input },
                    blockedBy = writer.by.variable,
                    note = "${guard.variable} 를 바꾸는 조작은 있으나 그 조작 자신이 " +
                        "${writer.by.variable} ${writer.by.operator} ${writer.by.value} 를 요구한다. " +
                        "지금은 그렇지 않다.",
                )
                // 값을 바꾸는 것이 명세에 **있는데** 지시할 수 없는 경우다. 없는 것과 구분해서
                // 말해 준다 — 사용자가 채워 줘야 할 것이 "어떻게 하면 그 일이 일어나는가"라서
                // 그 문장이 곧 물어볼 질문이 된다.
                Writer.Automatic -> return ScenarioPathAnswer(
                    ScenarioPathResult.UNKNOWN,
                    capabilityIds = steps.map { it.capabilityId },
                    actions = steps.map { it.action },
                    inputs = steps.map { it.input },
                    blockedBy = guard.variable,
                    note = "${guard.variable} 를 바꾸는 것이 명세에 있으나 조작으로 지시할 수 없다" +
                        "(저절로 일어나는 것). ${guard.operator} ${guard.value} 로 만드는 방법은 명세에 없다.",
                )
                Writer.None -> return ScenarioPathAnswer(
                    ScenarioPathResult.UNKNOWN,
                    capabilityIds = steps.map { it.capabilityId },
                    actions = steps.map { it.action },
                    inputs = steps.map { it.input },
                    blockedBy = guard.variable,
                    note = "${guard.variable} 를 ${guard.operator} ${guard.value} 로 만드는 방법이 명세에 없다.",
                )
            }
        }
        return answer(steps)
    }

    /**
     * 그 변수를 그 값으로 만드는 기능을 찾는다.
     *
     * 두 가지를 본다. 값을 **직접 쓰는** 기능이 있으면 그것이고, `+1`/`-1` 처럼 **증감으로만**
     * 알려진 변수면 조작을 되풀이해 옮길 수 있다고 본다. 명세가 몇 번인지는 말해 주지 않으므로
     * 그 판단은 실행하는 쪽에 남긴다 — 여기서 횟수를 지어내면 그것이 곧 거짓 명세다.
     */
    private suspend fun writerFor(contentMapId: Long, guard: Guard, state: Map<String, String>): Writer {
        val effects = pathRepository.findEffectsWriting(contentMapId, guard.variable).toList()
        if (effects.isEmpty()) return Writer.None

        // **지시할 수 없는 기능이 쓴 값은 길이 아니다.** 실제로 `MapMove.StagePosition` 을 올리는
        // 것은 마지막 웨이브가 끝날 때 저절로 도는 코드(`not-a-step` · `interaction=none`)뿐이고,
        // 그것을 답으로 내면 "조작 미상(none)" 같은 실행할 수 없는 스텝이 시나리오에 들어간다.
        val usable = effects
            // **흐린 효과는 단정 근거가 못 된다**(ARTEL-478). `ambiguous` 는 후보를 하나로 못 좁힌
            // 것이고 `unresolved` 는 못 푼 것이라, 그 값을 "이 조작이 이 값을 만든다"로 옮겨 적으면
            // 명세가 모른다고 적어 둔 것을 우리가 안다고 말하는 셈이 된다.
            .filter { it.resolution == null || it.resolution in CERTAIN }
            .mapNotNull { effect ->
                capabilityRepository.findById(effect.capabilityId)
                    ?.takeIf { instructable(it) }
                    ?.let { effect to it }
            }
        if (usable.isEmpty()) return Writer.Automatic

        // **그 조작 자신이 지금 가능한가.** 같은 변수를 쓰는 기능이 여럿이고 각자 성립 조건이
        // 다르므로(맵의 방향키가 각각 다른 `position` 에서만 성립한다), 이것을 보는 것은 거르는
        // 일이자 **고르는 일**이다 — 지금 상태에서 실제로 되는 조작을 집는다.
        val ready = usable.filter { (_, capability) -> ScenarioStateReader.violated(capability.givenText, state) == null }
        if (ready.isEmpty()) {
            val by = usable.firstNotNullOf { (_, capability) ->
                ScenarioStateReader.violated(capability.givenText, state)
            }
            return Writer.Blocked(by)
        }

        val exact = ready.firstOrNull { (e, _) -> e.detail != null && guard.holds(e.detail!!) }
        val relative = ready.firstOrNull { (e, _) -> e.detail == "+1" || e.detail == "-1" }
        // 지시할 수 있는 기능이 그 변수를 쓰기는 하는데 **이 값으로는** 못 만드는 경우다. 자동이라
        // 못 시키는 것과는 다르므로 그렇게 말하지 않는다 — 실제로 `StagePosition` 을 0 으로
        // 되돌리는 버튼은 있고, 2 로 만드는 방법만 없다.
        val (chosen, capability) = exact ?: relative ?: return Writer.None

        // 되풀이해야 하는지는 **지도가 말해 준다**(ARTEL-473). `repeat_until_done` 이 그 자리이고,
        // 증감(`+1`)만 아는 값을 옮기는 경우가 그 다음이다 — 명세가 몇 번인지는 말하지 않으므로
        // 횟수 판단은 실행하는 쪽에 남긴다.
        val repeated = capability.repeatUntilDone || exact == null
        return Writer.By(PathStep(
            capabilityId = chosen.capabilityId,
            input = operation(capability),
            action = buildString {
                append(describe(capability.interaction, capability.inputKey, capability.controlLabel, capability.controlPath))
                if (repeated) append(" — ${guard.variable} 가 ${guard.operator} ${guard.value} 가 될 때까지 되풀이한다")
                else append(" (${guard.variable} → ${chosen.detail})")
            },
        ))
    }

    /**
     * 이 기능을 스텝으로 지시할 수 있나.
     *
     * `interaction = none` 은 조작 없이 일어나는 것(타이머·로딩 완료·코루틴)이고, `not-a-step` ·
     * `unreachable-precondition` 은 지도 자신이 스텝이 아니라고 말한 것이다.
     *
     * **`status` 가 아니라 [CapabilityEntity.actionability] 를 본다**(ARTEL-479). `status` 는 세 축에서
     * 유도된 값이라 관측 불가(`observability`)까지 섞여 `needs-probe` 로 접힌다. 브리지 스텝은 판정
     * 대상이 아니므로 관측 여부는 여기서 물을 것이 아니다 — 우리가 묻는 것은 실행 축 하나다. 이런 기능을 경로에
     * 넣으면 실행할 수 없는 스텝이 되는데, **그것이 바로 이 작업이 없애려는 것**이라 여기서
     * 걸러야 한다. 명세에 있다는 것과 시킬 수 있다는 것은 다른 말이다.
     */
    private fun instructable(capability: CapabilityEntity): Boolean =
        capability.interaction != Interaction.NONE.wire &&
            capability.actionability != Actionability.NOT_A_STEP.wire &&
            capability.actionability != Actionability.UNREACHABLE_PRECONDITION.wire

    // ---- 씬 간선 ---------------------------------------------------------------------

    private suspend fun sceneHop(
        contentMapId: Long,
        from: String,
        to: String,
        state: Map<String, String>,
    ): Hop {
        val fromScene = sceneRepository.findByContentMapIdAndName(contentMapId, from) ?: return Hop.None
        val edge = sceneEdgeRepository.findByFromSceneIdAndToSceneName(fromScene.id!!, to).firstOrNull()
            ?: return Hop.None
        // 간선은 있는데 무엇으로 넘어가는지 모르는 경우(자동 전이)도 저절로 일어나는 쪽이다.
        val capabilityId = edge.capabilityId ?: return Hop.Automatic
        val capability = capabilityRepository.findById(capabilityId) ?: return Hop.None
        if (!instructable(capability)) return Hop.Automatic
        // 간선을 타는 조작에도 자기 사전조건이 있다. `InteractionLock` 이 잠긴 상태에서 씬을
        // 넘으라고 적어 두면 실행은 첫 스텝에서 멎는다.
        ScenarioStateReader.violated(capability.givenText, state)?.let { return Hop.Blocked(it) }
        return Hop.By(PathStep(
            capabilityId = capabilityId,
            input = operation(capability),
            action = describe(
                capability.interaction, capability.inputKey,
                capability.controlLabel, capability.controlPath,
            ) + " ($from → $to)",
        ))
    }

    /**
     * 씬을 넘는 네 경우.
     *
     * [Automatic] 전이는 있으나 스텝으로 지시할 수 없다(저절로 일어난다).
     * [Blocked] 조작은 있으나 그 조작 자신의 사전조건이 지금 어긋난다.
     * [None] 가는 조작이 명세에 없다.
     */
    private sealed interface Hop {
        data class By(val step: PathStep) : Hop
        data class Blocked(val by: Guard) : Hop
        data object Automatic : Hop
        data object None : Hop
    }

    /** 값을 옮기는 네 경우. 뜻은 [Hop] 과 같다. */
    private sealed interface Writer {
        data class By(val step: PathStep) : Writer
        data class Blocked(val by: Guard) : Writer
        data object Automatic : Writer
        data object None : Writer
    }

    // ---- 조립 -----------------------------------------------------------------------

    private suspend fun contentMapIdOf(projectId: Long, appUserId: Long): Long? {
        val build = buildRepository.findAllForMember(projectId, appUserId).firstOrNull() ?: return null
        return contentMapRepository.findByGameBuildIdOrderByIdDesc(build.id!!).firstOrNull()?.id
    }

    private fun withOrdering(ordering: ScenarioOrdering, answer: ScenarioPathAnswer) =
        if (ordering == ScenarioOrdering.NO_OPINION) answer else answer.copy(ordering = ordering)

    private fun answer(steps: List<PathStep>) = ScenarioPathAnswer(
        result = ScenarioPathResult.KNOWN,
        capabilityIds = steps.map { it.capabilityId },
        actions = steps.map { it.action },
        inputs = steps.map { it.input },
    )

    private fun unknownCase(caseId: Long) = ScenarioPathAnswer(
        ScenarioPathResult.UNKNOWN,
        blockedBy = "case:$caseId",
        note = "이 프로젝트에 없는 케이스라 어떤 상황인지 알 수 없다.",
    )

    /**
     * **정규화된 조작 하나.** 사람이 읽는 [describe] 문장과 달리 기계가 그대로 쓰는 값이다 —
     * `key:Return` · `click:Canvas/MapSceneButton`.
     *
     * 문장에서 다시 뽑아 쓰게 하지 않으려고 따로 낸다. 실행하는 쪽이 "Return 키를 누른다
     * (Map_scene → TurnBattleScene)" 를 파싱하기 시작하면 저작이 문구를 다듬을 때마다 실행이
     * 깨진다. 어휘는 씬 명세가 쓰는 것과 같게 둔다(`key:RightArrow`).
     */
    private fun operation(capability: CapabilityEntity): String = when {
        capability.inputKey != null -> "key:${capability.inputKey}"
        capability.controlPath != null -> "click:${capability.controlPath}"
        capability.controlLabel != null -> "click:${capability.controlLabel}"
        else -> capability.interaction
    }

    private companion object {
        /** 단정 근거로 쓸 수 있는 확실성. 나머지(`ambiguous`·`unresolved`)는 경로로 옮기지 않는다. */
        val CERTAIN = setOf(AnalysisConfidence.EXACT.wire, AnalysisConfidence.DERIVED.wire)
    }

    private fun describe(interaction: String, key: String?, label: String?, path: String?): String =
        when {
            key != null -> "$key 키를 ${if (interaction == "press") "누른다" else interaction}"
            label != null -> "$label 을(를) 클릭한다"
            path != null -> "$path 을(를) 클릭한다"
            else -> "조작 미상($interaction)"
        }

}

/** 사이에 스텝이 필요한가 — 한 질문에 대한 세 답. */
enum class ScenarioPathResult {
    /** 필요하고, [ScenarioPathAnswer.capabilityIds] 를 타면 된다. */
    KNOWN,

    /** 필요 없다. 그대로 이어진다. */
    NOT_REQUIRED,

    /** 필요한데 방법을 모른다. [ScenarioPathAnswer.blockedBy] 가 무엇이 막는지 말한다. */
    UNKNOWN,
}

/**
 * @property capabilityIds [ScenarioPathResult.KNOWN] 일 때 순서대로. 스텝의 근거로 그대로 쓴다.
 * @property actions [capabilityIds] 와 같은 길이. 사람이 읽는 문장.
 * @property inputs [capabilityIds] 와 같은 길이. **기계가 쓰는 조작**(`key:Return`·`click:경로`).
 *   실행하는 쪽이 [actions] 문장을 파싱하지 않게 하려고 따로 낸다.
 * @property ordering 이 순서로 이어지는가. [ScenarioOrdering.REVERSED] 는 **메우기 전에 순서를
 *   보라는 뜻**이다 — 뒤집으면 이어지는 자리를 이동 스텝으로 덮으면 실행은 되지만 케이스가
 *   의도한 순서가 아니게 된다.
 * @property blockedBy [ScenarioPathResult.UNKNOWN] 일 때 막는 것 — 씬 쌍(`A→B`) 또는 변수명.
 */
data class ScenarioPathAnswer(
    val result: ScenarioPathResult,
    val capabilityIds: List<Long> = emptyList(),
    val actions: List<String> = emptyList(),
    val inputs: List<String> = emptyList(),
    val ordering: ScenarioOrdering = ScenarioOrdering.NO_OPINION,
    val blockedBy: String? = null,
    val note: String = "",
) {
    /**
     * 명세를 **보지도 못한** 답인가. 지도가 아직 없는 프로젝트이거나 물어본 케이스가 이 프로젝트에
     * 없을 때다.
     *
     * "명세가 그 길을 모른다"와는 다른 말이다. 앞은 확인한 결과 없는 것이고 뒤는 확인 자체를 못한
     * 것이라, 뒤를 미상 스텝으로 남기면 지도가 없는 모든 프로젝트의 모든 스텝 사이에 "모른다" 줄이
     * 하나씩 붙는다 — 지금 지도가 있는 프로젝트가 거의 없으므로 그게 곧 전부다.
     * 에이전트에게는 그대로 [ScenarioPathResult.UNKNOWN]으로 답한다(물어봤으니 답은 해야 한다).
     * 구분이 필요한 쪽은 **스스로 묻는** [ScenarioReconcileService]다.
     */
    val unchecked: Boolean
        get() = result == ScenarioPathResult.UNKNOWN &&
            (blockedBy == "content-map" || blockedBy?.startsWith("case:") == true)
}

private data class PathStep(val capabilityId: Long, val action: String, val input: String)
