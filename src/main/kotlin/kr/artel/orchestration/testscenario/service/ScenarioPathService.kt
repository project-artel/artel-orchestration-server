package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.contentmap.entity.Actionability
import kr.artel.orchestration.contentmap.entity.AnalysisConfidence
import kr.artel.orchestration.contentmap.entity.CapabilityEffectEntity
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.entity.Interaction
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneEdgeRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testscenario.repository.ScenarioCaseFactRepository
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
    // 케이스의 전제를 읽는 유일한 창구(ARTEL-627). 문장이 아니라 구조에서 읽는다.
    private val conditions: CaseConditionReader,

    private val objectMapper: ObjectMapper,
    private val testCaseRepository: TestCaseRepository,
    private val buildRepository: GameBuildRepository,
    private val contentMapRepository: ContentMapRepository,
    private val sceneRepository: SceneRepository,
    private val sceneEdgeRepository: SceneEdgeRepository,
    private val capabilityRepository: CapabilityRepository,
    private val pathRepository: ScenarioPathRepository,
    private val factRepository: ScenarioCaseFactRepository,
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
        val state = conditions.knownValuesOf(from) + fromAfter
        val want = conditions.guardsOf(to)
        // 순서가 뒤바뀐 것인지는 **메우는 일과 다른 질문**이다. 여기서 함께 답해 두면 뒤집으면
        // 이어지는 자리를 조용히 이동 스텝으로 덮지 않을 수 있다.
        val ordering = ScenarioOrderCheck.verdict(
            fromAfter = fromAfter,
            fromBefore = conditions.guardsOf(from),
            toAfter = ScenarioStateReader.stateAfter(to, objectMapper),
            toBefore = want,
        )

        val steps = mutableListOf<PathStep>()

        if (fromScene != null && toScene != null && fromScene != toScene) {
            val hop = sceneHop(contentMapId, fromScene, toScene, state)
            // **출발 케이스가 이미 그 조작이면 또 넣지 않는다.** 실측(런 32)에서 "맵에서 Return 을
            // 누른다"를 검증하는 케이스 바로 뒤에 같은 Return 을 누르는 브리지가 붙었다 — 실행하는
            // 사람은 같은 것을 두 번 하게 되고, 화면에는 스텝이 중복돼 보인다.
            // 여러 걸음일 수 있으므로 **첫 걸음만** 본다 — 뒤엣것은 그 케이스가 한 일이 아니다.
            val walked = (hop as? Hop.By)?.steps.orEmpty()
                .let { if (it.isNotEmpty() && performs(contentMapId, from, it.first().capabilityId)) it.drop(1) else it }
            if (hop is Hop.By && walked.isEmpty()) {
                return withOrdering(ordering, resolveGuards(contentMapId, emptyMap(), want, steps, toScene))
            }
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
            steps += walked
            // **씬을 넘으면 알던 변수 값을 버린다.** 화면이 바뀐 뒤 무엇이 유지되는지 명세가
            // 말해 주지 않으므로, 유지된다고 치는 것은 지어내는 것이다.
            return withOrdering(ordering, resolveGuards(contentMapId, emptyMap(), want, steps, toScene))
        }

        return withOrdering(ordering, resolveGuards(contentMapId, state, want, steps, toScene ?: fromScene))
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
     *
     * ## 다만 모른다고 다 넘기지는 않는다(ARTEL-637)
     *
     * 이 규칙에는 구멍이 하나 있었다. **저절로 바뀌는 값은 어떤 케이스의 `state_after` 에도 적히지
     * 않는다** — 조작이 쓰는 값이 아니라서다. 그래서 늘 "모르는 값"이었고, 늘 넘어갔다. 실측(런 187,
     * TS 528)에서 `StagePosition >= 1 → >= 2 → >= 3 → >= 4` 로 올라가는 시나리오에 전투가 한 번도
     * 안 끼워졌다. 사이를 메우는 일이 제대로 돌았는데도 그랬다 — 메울 자리로 세어지지 않았다.
     *
     * 그래서 값을 모르는 요구도 한 번 더 본다. 다만 **지도가 "이건 조작으로 못 만든다"고 말할
     * 때만** 자리로 센다([Writer.Automatic]). 나머지는 앞 스텝이 만들었을 수 있고, 그것까지 막으면
     * 될 것을 못 하게 한다 — 실측(지도 26)에서 `InteractionLock.IsLocked` 가 전제에 22번 나오는데
     * 그 값을 쓰는 기능은 지도에 하나도 없다. 그런 것까지 세면 저작이 온통 미상으로 덮인다.
     *
     * @param at 지금 서 있는 화면. 저절로 바뀌는 값을 만나면 **그 일이 일어나는 화면까지 가는 길**을
     *   여기서부터 찾는다. 모르면 길을 붙이지 않고 이유만 낸다.
     */
    private suspend fun resolveGuards(
        contentMapId: Long,
        state: Map<String, String>,
        want: List<Guard>,
        steps: MutableList<PathStep>,
        at: String?,
    ): ScenarioPathAnswer {
        val unmet = want.filter { g ->
            val have = state[g.variable]
            have != null && !g.holds(have)
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
                is Writer.Automatic -> return automaticAnswer(contentMapId, guard, writer, state, steps, at)
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

        // 값을 모르는 요구를 한 번 더 본다. 위 KDoc 에 적은 구멍이 이 자리다.
        for (guard in want) {
            if (state.containsKey(guard.variable)) continue
            val writer = writerFor(contentMapId, guard, state)
            if (writer !is Writer.Automatic) continue
            return automaticAnswer(contentMapId, guard, writer, state, steps, at)
        }

        if (steps.isEmpty()) return ScenarioPathAnswer(ScenarioPathResult.NOT_REQUIRED)
        return answer(steps)
    }

    /**
     * 저절로 바뀌는 값을 요구하는 자리의 답(ARTEL-637).
     *
     * **아는 만큼은 스텝으로 낸다.** 그 값이 바뀌는 화면까지 가는 길은 대개 지도에 있으므로 그것을
     * 붙이고, 거기서 무엇이 일어나야 하는지는 [ScenarioPathResult.UNKNOWN] 으로 남긴다 —
     * 실행하는 쪽이 "맵에서 Return 을 눌러 전투에 들어간다"까지는 그대로 할 수 있고, 남은 것은
     * "이겨야 한다" 하나다. 전부 미상으로 답하면 아는 절반까지 버리는 셈이다.
     *
     * 이미 그 화면에 서 있으면 갈 곳이 없다 — 길을 붙이지 않고 이유만 낸다.
     */
    private suspend fun automaticAnswer(
        contentMapId: Long,
        guard: Guard,
        writer: Writer.Automatic,
        state: Map<String, String>,
        steps: MutableList<PathStep>,
        at: String?,
    ): ScenarioPathAnswer {
        val here = at != null && at in writer.scenes
        val travelled = if (here || at == null) null else writer.scenes.firstNotNullOfOrNull { target ->
            (sceneHop(contentMapId, at, target, state) as? Hop.By)?.let { target to it.steps }
        }
        travelled?.let { (_, walked) -> steps += walked }

        val where = writer.scenes.joinToString(" · ")
        return ScenarioPathAnswer(
            ScenarioPathResult.UNKNOWN,
            capabilityIds = steps.map { it.capabilityId },
            actions = steps.map { it.action },
            inputs = steps.map { it.input },
            blockedBy = guard.variable,
            note = buildString {
                append("${guard.variable} 는 $where 에서 저절로 바뀐다(조작으로 지시할 수 없다). ")
                append("${guard.operator} ${guard.value} 로 만들려면 거기서 그 일이 일어나야 한다.")
                if (travelled != null) append(" 그 화면까지 가는 길은 스텝으로 넣었다.")
                else if (here) append(" 지금 그 화면에 있다.")
            },
        )
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
        val owners = effects
            // **흐린 효과는 단정 근거가 못 된다**(ARTEL-478). `ambiguous` 는 후보를 하나로 못 좁힌
            // 것이고 `unresolved` 는 못 푼 것이라, 그 값을 "이 조작이 이 값을 만든다"로 옮겨 적으면
            // 명세가 모른다고 적어 둔 것을 우리가 안다고 말하는 셈이 된다.
            .filter { it.resolution == null || it.resolution in CERTAIN }
            .mapNotNull { effect ->
                capabilityRepository.findById(effect.capabilityId)?.let { effect to it }
            }
        val usable = owners.filter { (_, capability) -> instructable(capability) }
        if (usable.isEmpty()) return automatic(owners)

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

        // **값을 정하는 조작**이 먼저다. 증감은 한 번으로 값이 정해지지 않으므로 여기서 뺀다 —
        // 빼지 않으면 `+1` 을 쓰는 조작이 `position >= 1` 을 "한 번 눌러 만족시킨다"로 읽힌다.
        val exact = ready.firstOrNull { (e, _) ->
            increment(e.detail) == null && makes(guard, e.detail)
        }
        // **증감은 방향을 골라야 한다.** 먼저 걸리는 것을 집으면 값을 반대로 밀어내는 조작이
        // 들어간다 — 실측(런 152, TS 250)에서 `position` 을 1에서 3으로 올려야 하는 자리에
        // 내리는 조작을 "position 이 3 이 될 때까지 되풀이한다"로 적어 넣었다. 되풀이해도 영영
        // 도달하지 않는 스텝이고, 실행하는 사람은 그 앞에서 멎는다.
        val toward = push(guard, state[guard.variable])
        val relative = ready.firstOrNull { (e, _) ->
            val by = increment(e.detail) ?: return@firstOrNull false
            // 방향을 아는데 그쪽으로 미는 조작이 없으면 **없는 것이다.** 반대로 미는 것을 넣는 것보다
            // 모른다고 답하는 편이 낫다 — 미상은 사용자가 채울 수 있지만 거짓 스텝은 실행하다 만난다.
            //
            // **방향을 모르면 증감은 답이 아니다**(ARTEL-651). 앞서는 그때 있는 증감을 그냥 썼다.
            // 부등호가 지금 값 없이도 방향을 말하게 된 뒤로, 방향을 모르는 자리는 `==` 를 지금 값
            // 없이 묻는 경우뿐이다 — 어디서 출발하는지 모르는데 몇 칸씩 미는 조작이 그 값에 정확히
            // 멈춘다는 것은 근거 없는 주장이다. 대가가 실측(런 216)에 나왔다: `StagePosition != 5`
            // 에서 `== 5` 로 가는 자리를 타이틀의 `-1` 버튼이 답으로 뽑혀 `NOT_REQUIRED` 가 됐고,
            // 저작은 갈래 양쪽을 한 흐름에 나란히 담았다. 그 조합은 어떤 스텝으로도 성립하지 않는다.
            toward != null && toward == Push.of(by)
        }
        // 지시할 수 있는 기능이 그 변수를 쓰기는 하는데 **이 값으로는** 못 만드는 경우다. 자동이라
        // 못 시키는 것과는 다르므로 그렇게 말하지 않는다 — 실제로 `StagePosition` 을 0 으로
        // 되돌리는 버튼은 있고, 2 로 만드는 방법만 없다.
        //
        // 다만 **미는 방향의 조작이 있는데 지금 못 하는 것**은 없는 것과 다르다. 실측(런 153)에서
        // `position` 을 2에서 3으로 올릴 자리가 그랬다 — `RightArrow` 가 +1 을 쓰지만 지도에는 그
        // 조작의 사전조건이 `position == 0` 으로 적혀 있다. "명세에 없다"고 말하면 사용자는 없는
        // 것을 알려주려 하게 되고, 정작 손볼 자리(지도의 사전조건)는 가려진다.
        //
        // **여기까지 왔다고 아무것도 모르는 것은 아니다**(ARTEL-637). 지시할 수 있는 조작이 이
        // 값을 쓰기는 하는데 이 값으로는 못 만드는 자리인데, 그때도 **저절로 바뀌는 쪽**은 남아
        // 있을 수 있다. 실측(지도 26)에서 `MapMove.StagePosition` 이 그렇다 — 타이틀의 새 게임
        // 버튼이 `0` 을 쓰므로 지시 가능한 쓰기가 있지만 `>= 2` 로 만들지는 못하고, 그 값을 올리는
        // 것은 `TurnBattleScene` 의 `+1`(`interaction=none`)뿐이다. 예전에는 이 자리에서 "명세에
        // 없다"고 답했고, 그래서 저작이 전투를 한 번도 끼우지 않은 시나리오를 냈다(런 187, TS 528).
        val (chosen, capability) = exact ?: relative
            ?: toward?.let { direction ->
                usable.firstOrNull { (effect, _) -> increment(effect.detail)?.let(Push::of) == direction }
                    ?.let { (_, capability) -> ScenarioStateReader.violated(capability.givenText, state) }
                    ?.let { return Writer.Blocked(it) }
            }
            ?: return automatic(owners)

        // 되풀이해야 하는지는 **지도가 말해 준다**(ARTEL-473). `repeat_until_done` 이 그 자리이고,
        // 증감만 아는 값을 옮기는 경우가 그 다음이다 — 몇 칸씩 움직이는지는 알아도 **지금 값과
        // 목표가 정확히 몇 번 만에 만나는지**는 조작의 사전조건과 게임 규칙에 달렸다. 여기서
        // 횟수를 지어내면 그것이 곧 거짓 명세이므로 실행하는 쪽에 남긴다.
        val repeated = capability.repeatUntilDone || exact == null
        return Writer.By(PathStep(
            capabilityId = chosen.capabilityId,
            input = operation(capability),
            action = buildString {
                append(describe(capability.interaction, capability.inputKey, capability.controlLabel, capability.controlPath))
                if (repeated) append(" — ${until(guard)}")
                else append(" (${guard.variable} → ${chosen.detail})")
            },
        ))
    }

    /**
     * 이 효과가 [guard] 를 **증명할 수 있게** 만드나(ARTEL-637).
     *
     * [Guard.holds] 를 쓰면 안 되는 자리다. 그쪽은 *"이것이 위반인가"* 에 답하고 **읽을 수 없으면
     * 참**이라고 말한다 — 모르는 것을 위반이라 하지 않는 것이 이 저장소 전체의 규칙이라 그게 맞다.
     * 그런데 여기서 묻는 것은 정반대다: *"이 조작이 이 값을 만드나."* 만든다는 것은 **주장**이라,
     * 못 읽으면 참이 아니라 거짓이어야 한다.
     *
     * 뒤섞은 대가가 실측(런 188)에 그대로 나왔다. 지도에 `saved StagePosition = MapMove.StagePosition`
     * 이 있는데 그 `detail` 이 숫자가 아니라, `StagePosition >= 2` 를 **만족시킨다고** 읽혔다.
     * 그래서 전투가 필요한 자리가 "지도 화면의 어떤 클릭이 이미 만들어 준다"로 답해졌고, 저작은
     * 전투를 한 번도 안 끼운 시나리오를 냈다.
     *
     * 기호 값(`PlayerPrefs.GetInt(…)` · `stagePosition`)은 무엇이 될지 모르는 것이다. 모르는 것을
     * 근거로 스텝을 끼우면 그 스텝은 실행할 때 어긋난다.
     */
    private fun makes(guard: Guard, detail: String?): Boolean {
        val made = detail?.trim().orEmpty()
        if (made.isEmpty() || guard.symbolic) return false
        return when (guard.operator) {
            // 글자까지 같으면 증명된다 — 숫자가 아니어도 된다(`"GameClearScene"` 같은 이름).
            "==" -> made == guard.value
            // "그 값이 아니다"는 **무엇이 되는지 알아야** 증명된다. 기호는 그 값일 수도 있다.
            "!=" -> made.toDoubleOrNull() != null && made != guard.value
            else -> made.toDoubleOrNull() != null && guard.holds(made)
        }
    }

    /**
     * 지시할 수 없는 쓰기만 남았을 때의 답. **어디서 일어나는지를 함께 낸다**(ARTEL-637).
     *
     * 화면 이름이 답의 절반이다 — 거기까지 가는 길은 대개 지시할 수 있고, 그러면 시나리오는
     * "그 화면까지 가서 그 일이 일어나기를 기다린다"까지 적을 수 있다. 이름을 버리면 실행하는
     * 쪽에도 사용자에게도 "방법이 없다"로만 보인다.
     *
     * 지시할 수 없는 쓰기조차 없으면 [Writer.None] 이다 — 예전 그대로다. 화면을 못 대면서
     * `Automatic` 이라 답하면 없는 자리를 가리키는 셈이라 [Writer.None] 과 구분할 값이 없다.
     */
    private suspend fun automatic(owners: List<Pair<CapabilityEffectEntity, CapabilityEntity>>): Writer {
        val scenes = owners
            .filterNot { (_, capability) -> instructable(capability) }
            .filter { (effect, _) -> tells(effect.detail) }
            .mapNotNull { (_, capability) -> sceneRepository.findById(capability.sceneId)?.name }
            .distinct()
        return if (scenes.isEmpty()) Writer.None else Writer.Automatic(scenes)
    }

    /**
     * 이 효과가 값이 **무엇이 되는지** 말하나(ARTEL-649).
     *
     * [makes] 와 같은 뿌리다 — 다른 이름을 옮겨 적은 `detail` 은 그 값이 무엇이 될지 말하지 않는다.
     * 여기서 그것까지 세면 **엉뚱한 화면을 짚는다.** 실측(런 214)에서 `StagePosition` 의 오름을
     * `TurnBattleScene · Map_scene 에서 저절로 바뀐다`고 답했다. 맵 쪽은 `SelectStage()` 가
     * `stagePosition` 이라는 **지역 이름을 옮겨 적은 것**이라 값을 올리지 않는다. 그런데 지금 서
     * 있는 화면이 그 목록에 들어가는 바람에 "지금 그 화면에 있다"로 끝났고, 전투까지 가는 길을
     * 스텝으로 내지 못했다 — 아는 절반을 그렇게 잃는다.
     *
     * 리터럴(숫자 · 따옴표 · 참거짓)과 증감만이 값이 무엇이 되는지 말한다.
     */
    private fun tells(detail: String?): Boolean {
        val made = detail?.trim().orEmpty()
        if (made.isEmpty()) return false
        return increment(made) != null ||
            made.toDoubleOrNull() != null ||
            made.startsWith("\"") || made.startsWith("'") ||
            made == "true" || made == "false"
    }

    /** 값을 **올려야 하나 내려야 하나.** 크기는 여기서 묻지 않는다 — 방향만이 답이다. */
    private enum class Push {
        UP, DOWN;

        companion object {
            /** 증감 하나가 미는 쪽. 0 은 아무 데도 밀지 않으므로 방향이 없다. */
            fun of(by: Double): Push? = when {
                by > 0 -> UP
                by < 0 -> DOWN
                else -> null
            }
        }
    }

    /**
     * 어느 쪽으로 밀어야 [guard] 를 만족하나. 알 수 없으면 null.
     *
     * **부등호는 지금 값을 몰라도 방향을 안다**(ARTEL-649). `>= 2` 를 만족시키는 길은 올리는 것
     * 하나뿐이고, 지금이 0이든 1이든 그 답은 달라지지 않는다. 지금 값이 있어야 하는 것은 `==`
     * 하나다 — 목표가 위에 있는지 아래에 있는지가 거기서만 갈린다.
     *
     * 앞서 이 함수는 지금 값을 못 읽으면 무조건 방향을 모른다고 답했다. 대가가 실측(런 213)에
     * 그대로 나왔다. `StagePosition >= 2` 는 전제가 `>=` 라 확정값이 없어 늘 "모름"이었고,
     * 방향을 모르면 있는 증감을 쓴다는 규칙에 걸려 **타이틀의 `-1` 을 쓰는 버튼**이 그 값을
     * 만드는 조작으로 뽑혔다. 그래서 전투가 필요한 자리가 `KNOWN` 으로 답해졌고, 저작은 전투를
     * 한 번도 안 끼운 시나리오를 냈다 — 되살린 ARTEL-637 이 이 한 줄에 가려 작동하지 못했다.
     */
    private fun push(guard: Guard, have: String?): Push? {
        val target = guard.value.toDoubleOrNull() ?: return null
        return when (guard.operator) {
            ">", ">=" -> Push.UP
            "<", "<=" -> Push.DOWN
            // `==` 는 지금 값이 어느 쪽에 있는지가 방향이다. 그것만 지금 값을 요구한다.
            "==" -> have?.toDoubleOrNull()?.let {
                if (target > it) Push.UP else if (target < it) Push.DOWN else null
            }
            // `!=` 는 어느 쪽으로 밀어도 벗어난다. 고를 근거가 없다.
            else -> null
        }
    }

    /**
     * 이 효과가 **값을 얼마씩 옮기나.** 옮기는 것이 아니면(대입) null.
     *
     * 지도의 `detail` 은 문자열 하나뿐이라 `=` 인지 `+=` 인지가 **부호 관용구에 얹혀 있다.**
     * 추출기는 양수 리터럴 대입에 부호를 붙이지 않으므로(`"10"` 이지 `"+10"` 이 아니다) 앞에
     * `+` 가 붙어 있다는 것 자체가 증분이라는 뜻이다 — 같은 규칙을
     * [kr.artel.orchestration.contentmap.render.ExpressionWriter] 도 쓴다.
     *
     * **크기를 보지 않는다.** `+1` 만 아는 것으로 짜여 있었는데, 추출기는 `x += 10` 을 `"+10"`
     * 으로 낸다. 특정 게임에 `+1` 밖에 없어서 드러나지 않았을 뿐이고, 그 밖의 증감은 통째로
     * 안 보여 미상으로 떨어졌다.
     *
     * **`-N` 은 형식이 가르지 못한다.** 음수 리터럴 대입(`z = -10`)도 `detail` 이 `"-10"` 이라
     * 감소(`z -= 10`)와 구분되지 않는다. 여기서 감소로 읽는 근거는 하나다 — 이 자리는 값을
     * 정하는 조작([exact])이 이미 없을 때만 닿는다. 대입으로 읽으면 그 조작은 도착 조건을
     * 만족시키지 못해 어차피 쓸 수 없으므로, 감소 해석만이 이 조작을 쓸모 있게 만든다.
     * 형식이 연산자를 명시하게 하는 것이 근본 해결이고 그건 적재 쪽 일이다.
     */
    private fun increment(detail: String?): Double? {
        val text = detail?.trim().orEmpty()
        if (text.length < 2) return null
        if (text.first() != '+' && text.first() != '-') return null
        return text.toDoubleOrNull()
    }

    /**
     * 되풀이 스텝의 **끝 조건 문구.**
     *
     * `position 가 == 1 가 될 때까지` 처럼 연산자 기호와 조사가 뒤섞이던 자리다. 사용자가 읽는
     * 문장이고, 실행하는 사람이 언제 멈추는지 여기서만 안다.
     */
    private fun until(guard: Guard): String {
        val name = guard.variable
        val subject = if (hasFinalConsonant(name)) "$name 이" else "$name 가"
        val value = guard.value
        val target = if (hasFinalConsonant(value)) "$value 이" else "$value 가"
        return when (guard.operator) {
            "==" -> "$subject $target 될 때까지 되풀이한다"
            "!=" -> "$subject $value 이 아니게 될 때까지 되풀이한다"
            else -> "$subject ${guard.operator} $value 를 만족할 때까지 되풀이한다"
        }
    }

    /** 마지막 글자에 받침이 있나. 없으면 조사가 `가`/`를` 쪽이다. */
    private fun hasFinalConsonant(word: String): Boolean {
        val last = word.trimEnd(')', '"', '\'', '`').lastOrNull() ?: return false
        if (last in '0'..'9') return last in setOf('0', '1', '3', '6', '7', '8')
        if (last.code in 0xAC00..0xD7A3) return (last.code - 0xAC00) % 28 != 0
        // 변수 이름은 대개 라틴 문자로 끝난다(`position` · `hp` · `wave`). 끝소리로 가른다.
        if (last.isLetter()) return last.lowercaseChar() !in "aeiouy"
        return false
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

    /**
     * 이 케이스가 **그 기능을 직접 실행하는가.** 그렇다면 같은 것을 시키는 브리지는 중복이다.
     *
     * ## 케이스가 키를 들면 한 줄이다(ARTEL-555)
     *
     * 지도에서 나온 케이스는 자기를 만든 기능의 [CapabilityEntity.capabilityKey] 를 들고 있다.
     * 같은 키면 같은 기능이고, 그것으로 끝이다.
     *
     * ## 키가 없으면 예전처럼 근거 문자열을 맞춘다
     *
     * 손으로 만든 케이스, 엑셀로 적재된 케이스, 구버전 생성기가 낸 케이스는 키가 없다. 그 길은
     * **한 꼬리가 기능 하나를 가리키지 못한다** — 꼬리가 메서드 단위라, 실측(적재기 지도)에서
     * `Map.MapMove|CharacterMove|System.Void()` 하나가 기능 14개를 내고 그 안에 `LeftArrow` 와
     * `RightArrow` 가 섞여 있다. 한쪽을 검증하는 케이스가 반대쪽 브리지를 지울 수 있다.
     *
     * 그래서 **그 길에서는 조작이 하나로 모일 때만** 지운다. 어느 것을 하든 사람이 하는 일이 같으면
     * 브리지는 어차피 중복이고, 갈리면 어느 것인지 모른다.
     *
     * 근거가 없는 케이스는 판단하지 않는다 — 모르면 넣는 쪽이 안전하다. **빠뜨린 스텝은 눈에
     * 띄지만, 없는 스텝은 실행할 때까지 모른다.**
     */
    private suspend fun performs(contentMapId: Long, case: TestCaseEntity, capabilityId: Long): Boolean {
        case.capabilityKey?.takeIf { it.isNotBlank() }?.let { key ->
            return capabilityRepository.findById(capabilityId)?.capabilityKey == key
        }
        return ScenarioStateReader.evidenceTails(case, objectMapper).any { tail ->
            val found = factRepository.findByEvidenceTail(contentMapId, tail).toList()
            found.any { it.id == capabilityId } && found.distinctBy(::actionOf).size == 1
        }
    }

    /** 사람이 하는 일로 본 조작. 이것이 같으면 어느 기능이든 시키는 바가 같다. */
    private fun actionOf(capability: CapabilityEntity): Triple<String, String?, String?> =
        Triple(capability.interaction, capability.inputKey, capability.controlPath)

    // ---- 씬 간선 ---------------------------------------------------------------------

    /**
     * **화면은 거쳐 갈 수 있다**(ARTEL-653).
     *
     * 앞서는 `from → to` 간선을 직접 찾고 없으면 끝냈다. 그런데 지도의 씬 간선은 한 걸음으로 다
     * 안 닿는다 — 실측(지도 27, 간선 19개)에서 `Map_scene → StoryScene` 은 타이틀을 거쳐 두
     * 걸음이면 가는데 "가는 조작이 명세에 없다"고 답했다. 짝 행렬 1,722칸 중 **막힘 1,273칸이고
     * 그 88%가 씬 이동**이었으며, 두 걸음으로 풀리는 것만 178칸이다.
     *
     * 그래서 시킬 수 있는 간선만 밟아 가장 짧은 길을 찾는다. 못 찾으면 예전 그대로 답한다 —
     * 직접 간선이 있는데 그 조작이 지금 막혔으면 [Hop.Blocked], 저절로 넘어가는 것뿐이면
     * [Hop.Automatic], 아무것도 없으면 [Hop.None].
     *
     * **사전조건은 첫 걸음만 본다.** 화면을 넘으면 알던 값을 버리는 것이 이 서비스의 규칙이고,
     * 그러면 둘째 걸음부터는 읽을 값이 없다. 모르는 것을 위반이라 하지 않는다.
     *
     * **저절로 넘어가는 걸음은 안 섞는다.** `TurnBattleScene → GameClearScene`(이겨야 한다)처럼
     * 지시할 수 없는 걸음이 끼면 그 길은 스텝으로 낼 수 없다. 그런 자리는 아직 통째로 막힘이고,
     * 아는 절반을 내는 것은 다음 일이다.
     */
    private suspend fun sceneRoute(
        contentMapId: Long,
        from: String,
        to: String,
        state: Map<String, String>,
    ): List<PathStep>? {
        val scenes = sceneRepository.findByContentMapIdOrderByNameAsc(contentMapId).toList()
        val nameOf = scenes.associate { it.id!! to it.name }
        val edges = sceneEdgeRepository.findByContentMapId(contentMapId).toList()
            .groupBy { nameOf[it.fromSceneId] }
        if (edges.isEmpty()) return null

        // 가장 짧은 길 하나. 여러 걸음짜리 우회로는 실행하는 사람에게 부담이라 깊이를 묶는다 —
        // 지도의 씬은 한 자릿수이고, 네 걸음을 넘겨야 닿는 곳은 길이 있다기보다 없는 쪽에 가깝다.
        val seen = mutableSetOf(from)
        var frontier = listOf(from to emptyList<PathStep>())
        repeat(MAX_SCENE_HOPS) {
            val next = mutableListOf<Pair<String, List<PathStep>>>()
            for ((here, walked) in frontier) {
                for (edge in edges[here].orEmpty()) {
                    val there = edge.toSceneName
                    if (there in seen) continue
                    val capability = edge.capabilityId?.let { capabilityRepository.findById(it) } ?: continue
                    if (!instructable(capability)) continue
                    // 첫 걸음만 지금 상태로 잰다. 그 뒤는 화면이 바뀌어 아는 값이 없다.
                    if (walked.isEmpty() &&
                        ScenarioStateReader.violated(capability.givenText, state) != null
                    ) continue
                    val step = walked + PathStep(
                        capabilityId = capability.id!!,
                        input = operation(capability),
                        action = describe(
                            capability.interaction, capability.inputKey,
                            capability.controlLabel, capability.controlPath,
                        ) + " ($here → $there)",
                    )
                    if (there == to) return step
                    seen += there
                    next += there to step
                }
            }
            if (next.isEmpty()) return null
            frontier = next
        }
        return null
    }

    private suspend fun sceneHop(
        contentMapId: Long,
        from: String,
        to: String,
        state: Map<String, String>,
    ): Hop {
        sceneRoute(contentMapId, from, to, state)?.let { return Hop.By(it) }

        val fromScene = sceneRepository.findByContentMapIdAndName(contentMapId, from) ?: return Hop.None
        val edges = sceneEdgeRepository.findByFromSceneIdAndToSceneName(fromScene.id!!, to).toList()
        if (edges.isEmpty()) return Hop.None

        // 여기까지 왔다는 것은 **시킬 수 있는 길이 하나도 없다**는 뜻이다([sceneRoute] 가 직접
        // 간선도 함께 보므로). 남은 것은 왜 없는지를 가르는 일이다.
        //
        // 시킬 수 있는 간선이 있는데 여기 왔다면 그 조작 자신의 사전조건이 지금 어긋난 것이다.
        // 간선을 타는 조작에도 자기 사전조건이 있다 — `InteractionLock` 이 잠긴 상태에서 씬을
        // 넘으라고 적어 두면 실행은 첫 스텝에서 멎는다.
        val blocked = edges.firstNotNullOfOrNull { edge ->
            val capability = edge.capabilityId?.let { capabilityRepository.findById(it) }
            if (capability != null && instructable(capability)) {
                ScenarioStateReader.violated(capability.givenText, state)
            } else null
        }
        blocked?.let { return Hop.Blocked(it) }
        // 시킬 수 있는 간선이 하나도 없으면 그때는 정말 저절로 일어나는 자리다.
        return Hop.Automatic
    }

    /**
     * 씬을 넘는 네 경우.
     *
     * [Automatic] 전이는 있으나 스텝으로 지시할 수 없다(저절로 일어난다).
     * [Blocked] 조작은 있으나 그 조작 자신의 사전조건이 지금 어긋난다.
     * [None] 가는 조작이 명세에 없다.
     */
    private sealed interface Hop {
        /** 걸음이 여럿일 수 있다 — 화면은 거쳐 갈 수 있다(ARTEL-653). */
        data class By(val steps: List<PathStep>) : Hop
        data class Blocked(val by: Guard) : Hop
        data object Automatic : Hop
        data object None : Hop
    }

    /** 값을 옮기는 네 경우. 뜻은 [Hop] 과 같다. */
    private sealed interface Writer {
        data class By(val step: PathStep) : Writer

        data class Blocked(val by: Guard) : Writer

        /**
         * 지도는 이 값이 **바뀐다고 말하는데** 그 일을 조작으로 시킬 수 없다.
         *
         * [scenes] 는 그 일이 일어나는 화면들이다. 이것을 들고 다니는 이유는 **거기까지 가는 길은
         * 대개 지시할 수 있기 때문**이다(ARTEL-637) — `MapMove.StagePosition` 은 `TurnBattleScene`
         * 에서 저절로 `+1` 오르고, 그 화면에 들어가는 것은 지도에 `Return` 으로 적혀 있다. 화면
         * 이름을 버리고 "방법이 명세에 없다"로만 답하면 절반을 알면서 전부 모른다고 말하는 셈이다.
         */
        data class Automatic(val scenes: List<String>) : Writer

        data object None : Writer
    }

    // ---- 조립 -----------------------------------------------------------------------

    private suspend fun contentMapIdOf(projectId: Long, appUserId: Long): Long? {
        val build = buildRepository.findAllForMember(projectId, appUserId).firstOrNull() ?: return null
        return contentMapRepository.findByGameBuildId(build.id!!)?.id
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
        /**
         * 화면을 몇 걸음까지 거쳐 갈 것인가(ARTEL-653). 지도의 씬은 한 자릿수이고, 이보다 멀리
         * 돌아야 닿는 곳은 길이 있다기보다 없는 쪽에 가깝다 — 실행하는 사람에게도 그렇다.
         */
        const val MAX_SCENE_HOPS = 4

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
