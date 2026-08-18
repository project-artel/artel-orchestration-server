package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
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

        val fromScene = sceneNameOf(from)
        val toScene = sceneNameOf(to)
        // 출발은 **그 케이스를 실행한 뒤**다. 사전조건이 보장한 것에 그 케이스가 바꾼 것을 얹는다.
        val state = knownValuesOf(from.precondition) + effectOf(from)
        val want = guardsOf(to.precondition)

        val steps = mutableListOf<PathStep>()

        if (fromScene != null && toScene != null && fromScene != toScene) {
            val hop = sceneHop(contentMapId, fromScene, toScene)
                ?: return ScenarioPathAnswer(
                    ScenarioPathResult.UNKNOWN,
                    blockedBy = "$fromScene→$toScene",
                    note = "$fromScene 에서 $toScene 으로 가는 조작이 명세에 없다.",
                )
            steps += hop
            // **씬을 넘으면 알던 변수 값을 버린다.** 화면이 바뀐 뒤 무엇이 유지되는지 명세가
            // 말해 주지 않으므로, 유지된다고 치는 것은 지어내는 것이다.
            return resolveGuards(contentMapId, emptyMap(), want, steps)
        }

        return resolveGuards(contentMapId, state, want, steps)
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
            val writer = writerFor(contentMapId, guard)
            if (writer == null) {
                return ScenarioPathAnswer(
                    ScenarioPathResult.UNKNOWN,
                    capabilityIds = steps.map { it.capabilityId },
                    actions = steps.map { it.action },
                    blockedBy = guard.variable,
                    note = "${guard.variable} 를 ${guard.operator} ${guard.value} 로 만드는 방법이 명세에 없다.",
                )
            }
            steps += writer
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
    private suspend fun writerFor(contentMapId: Long, guard: Guard): PathStep? {
        val effects = pathRepository.findEffectsWriting(contentMapId, guard.variable).toList()
        if (effects.isEmpty()) return null

        val exact = effects.firstOrNull { it.detail != null && guard.holds(it.detail!!) }
        val relative = effects.firstOrNull { it.detail == "+1" || it.detail == "-1" }
        val chosen = exact ?: relative ?: return null
        val capability = capabilityRepository.findById(chosen.capabilityId) ?: return null

        val repeated = exact == null
        return PathStep(
            capabilityId = chosen.capabilityId,
            action = buildString {
                append(describe(capability.interaction, capability.inputKey, capability.controlLabel, capability.controlPath))
                if (repeated) append(" — ${guard.variable} 가 ${guard.operator} ${guard.value} 가 될 때까지 되풀이한다")
                else append(" (${guard.variable} → ${chosen.detail})")
            },
        )
    }

    // ---- 씬 간선 ---------------------------------------------------------------------

    private suspend fun sceneHop(contentMapId: Long, from: String, to: String): PathStep? {
        val fromScene = sceneRepository.findByContentMapIdAndName(contentMapId, from) ?: return null
        val edge = sceneEdgeRepository.findByFromSceneIdAndToSceneName(fromScene.id!!, to).firstOrNull()
            ?: return null
        val capabilityId = edge.capabilityId ?: return null
        val capability = capabilityRepository.findById(capabilityId) ?: return null
        return PathStep(
            capabilityId = capabilityId,
            action = describe(
                capability.interaction, capability.inputKey,
                capability.controlLabel, capability.controlPath,
            ) + " ($from → $to)",
        )
    }

    // ---- 케이스 읽기 -----------------------------------------------------------------

    /** 사전조건 앞부분이 씬 이름이다. 그 문구가 없는 행이 있어 `scene` 컬럼으로 받친다. */
    private fun sceneNameOf(case: TestCaseEntity): String? {
        val pre = case.precondition ?: return case.scene
        val marker = " 화면인 상태"
        return if (pre.contains(marker)) pre.substringBefore(marker).trim().ifBlank { null } else case.scene
    }

    /** 이 케이스를 실행한 뒤 확정되는 값. `metadata.source.state_after` 가 `Var=value` 형식이다. */
    private fun effectOf(case: TestCaseEntity): Map<String, String> = runCatching {
        val after = objectMapper.readTree(case.metadata.asString())
            .path("source").path("state_after").asText(null) ?: return emptyMap()
        val (name, value) = after.split("=", limit = 2).let { it[0] to it.getOrNull(1) }
        if (value.isNullOrBlank()) emptyMap() else mapOf(normalize(name) to value.trim())
    }.getOrElse { emptyMap() }

    // ---- 가드 파싱 -------------------------------------------------------------------

    /**
     * 사전조건의 `<씬> 화면인 상태 / <식>` 뒷부분에서 비교를 뽑는다.
     *
     * **부등식까지 읽는다.** `==` 만 보던 판을 실측했더니 실제 사전조건의 비교 중 58%가
     * `>`·`!=`·`>=`·`<=`·`<` 였고, 그것을 전부 "충돌 없음"으로 통과시키고 있었다.
     */
    private fun guardsOf(precondition: String?): List<Guard> {
        val expr = precondition?.substringAfter(" / ", "") ?: return emptyList()
        return COMPARISON.findAll(expr).map {
            Guard(normalize(it.groupValues[1]), it.groupValues[2], it.groupValues[3].trim())
        }.toList()
    }

    /**
     * 사전조건이 **확정하는** 값만 뽑는다.
     *
     * `==` 만 값을 특정한다. `StagePosition >= 1` 은 그 케이스가 성립하는 조건이지 값이 아니라,
     * 여기서 1이라고 읽으면 다음 케이스와의 비교가 거짓말이 된다.
     */
    private fun knownValuesOf(precondition: String?): Map<String, String> =
        guardsOf(precondition).filter { it.operator == "==" }.associate { it.variable to it.value }

    // ---- 이름 맞추기 -----------------------------------------------------------------

    /**
     * 마지막 마디로 맞춘다.
     *
     * 명세와 사전조건이 같은 값을 다른 경로로 부른다 — `StagePosition` · `MapMove.StagePosition` ·
     * `StageDataSingleton.stagePosition`. 마디가 겹치는 서로 다른 변수(`collision.tag` 와
     * `combineZone.tag`)를 뭉갤 수 있다는 것은 **알려진 한계**이고, 근본 해결은 명세가 별칭을
     * declare 하는 것이다.
     */
    private fun normalize(name: String): String =
        name.trim().trim('`').substringAfterLast('.')

    // ---- 조립 -----------------------------------------------------------------------

    private suspend fun contentMapIdOf(projectId: Long, appUserId: Long): Long? {
        val build = buildRepository.findAllForMember(projectId, appUserId).firstOrNull() ?: return null
        return contentMapRepository.findByGameBuildIdOrderByIdDesc(build.id!!).firstOrNull()?.id
    }

    private fun answer(steps: List<PathStep>) = ScenarioPathAnswer(
        result = ScenarioPathResult.KNOWN,
        capabilityIds = steps.map { it.capabilityId },
        actions = steps.map { it.action },
    )

    private fun unknownCase(caseId: Long) = ScenarioPathAnswer(
        ScenarioPathResult.UNKNOWN,
        blockedBy = "case:$caseId",
        note = "이 프로젝트에 없는 케이스라 어떤 상황인지 알 수 없다.",
    )

    private fun describe(interaction: String, key: String?, label: String?, path: String?): String =
        when {
            key != null -> "$key 키를 ${if (interaction == "press") "누른다" else interaction}"
            label != null -> "$label 을(를) 클릭한다"
            path != null -> "$path 을(를) 클릭한다"
            else -> "조작 미상($interaction)"
        }

    private companion object {
        val COMPARISON = Regex("""([A-Za-z_][\w.]*)\s*(==|!=|>=|<=|>|<)\s*([^\s그리고또는()]+)""")
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
 * @property blockedBy [ScenarioPathResult.UNKNOWN] 일 때 막는 것 — 씬 쌍(`A→B`) 또는 변수명.
 */
data class ScenarioPathAnswer(
    val result: ScenarioPathResult,
    val capabilityIds: List<Long> = emptyList(),
    val actions: List<String> = emptyList(),
    val blockedBy: String? = null,
    val note: String = "",
)

/** 비교 하나. `holds` 는 비교할 수 없으면 **위반이라고 말하지 않는다**. */
internal data class Guard(val variable: String, val operator: String, val value: String) {
    fun holds(have: String): Boolean {
        if (operator == "==") return have == value
        if (operator == "!=") return have != value
        val a = have.toDoubleOrNull() ?: return true
        val b = value.toDoubleOrNull() ?: return true
        return when (operator) {
            ">" -> a > b
            ">=" -> a >= b
            "<" -> a < b
            "<=" -> a <= b
            else -> true
        }
    }
}

private data class PathStep(val capabilityId: Long, val action: String)
