package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * [ScenarioFlowPlan] 이 읽을 사실을 지도에서 모아 준다(ARTEL-657).
 *
 * 계산 자체는 순수하게 두고([ScenarioFlowPlan]) 재료 모으는 일만 여기 둔다 — 그래야 계산을
 * 스프링 없이 못 박을 수 있고, 실측에서 틀렸던 모양을 그대로 세워 둘 수 있다.
 */
@Service
class ScenarioFlowPlanner(
    private val conditions: CaseConditionReader,
    private val testCaseRepository: TestCaseRepository,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun of(
        projectId: Long,
        appUserId: Long,
        matrix: ScenarioFlowMatrix.Matrix,
    ): List<ScenarioFlowPlan.Flow> {
        val raisedIn = runCatching {
            testCaseRepository.findValueRaisers(projectId).toList()
                .groupBy({ ScenarioStateReader.normalize(it.target) }, { it.scene })
                .mapValues { (_, scenes) -> scenes.toSet() }
        }.onFailure { logger.warn("저절로 바뀌는 자리 조회 실패: ${it.message}") }
            .getOrDefault(emptyMap())

        val cases = matrix.testCaseIds.mapNotNull { id ->
            val case = testCaseRepository.findById(id) ?: return@mapNotNull null
            if (case.projectId != projectId) return@mapNotNull null
            val guards = conditions.guardsOf(case)
            // **그 케이스가 서 있는 화면에서 저절로 바뀌는 값은 지나면 모르게 된다.** 도착 화면도
            // 함께 본다 — 화면을 넘기는 케이스는 두 화면을 다 지난다.
            val scenes = setOfNotNull(
                ScenarioStateReader.sceneOf(case),
                ScenarioStateReader.arrivesAt(case, objectMapper),
            )
            ScenarioFlowPlan.Case(
                id = id,
                requires = guards,
                sets = guards.filter { it.operator == "==" && !it.symbolic }
                    .associate { it.variable to it.value } +
                    ScenarioStateReader.stateAfter(case, objectMapper),
                clears = raisedIn.filterValues { where -> where.any { it in scenes } }.keys,
            )
        }

        // **지도가 만들 수 있다고 말하는 값만 시작 조건이다.** 아무 기능도 안 쓰는 값은 거의 모든
        // 케이스에 붙어 있는 배경 조건이라(`InteractionLock.IsLocked` 가 스물두 번), 그것까지 적으면
        // 안내가 스물여덟 줄이 되고 아무도 안 읽는다.
        val produced = runCatching { testCaseRepository.findWrittenValues(projectId).toList() }
            .getOrDefault(emptyList())
            .mapTo(mutableSetOf()) { ScenarioStateReader.normalize(it).lowercase() }

        return ScenarioFlowPlan.of(
            cases,
            opening = { guard -> guard.variable.lowercase() in produced && !guard.symbolic },
        ) { from, to ->
            val cell = matrix.between(from, to)
            ScenarioFlowPlan.Link(
                kind = cell?.link ?: ScenarioFlowMatrix.Link.UNCHECKED,
                // 지나갈 자리는 그 값이 어떻게 되는지 모르게 만든다 — 막은 것이 화면 쌍이면
                // 그 화면에서 오르는 값들이고, 값 이름이면 그 값이다.
                clears = cell?.blockedBy?.let { blocked ->
                    if (blocked.contains("→")) {
                        val passed = blocked.split("→").map { it.trim() }.toSet()
                        raisedIn.filterValues { where -> where.any { it in passed } }.keys
                    } else setOf(blocked)
                }.orEmpty(),
            )
        }
    }
}
