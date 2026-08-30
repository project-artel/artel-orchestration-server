package kr.artel.orchestration.testscenario.service

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * **무엇 다음에 무엇이 올 수 있나**를 전건에 대해 미리 다 푼다(ARTEL-652).
 *
 * ## 왜 미리 다 푸나
 *
 * 지금은 **저작이 고른 짝만** 물어본다. 그래서 경로 답이 틀려도 세 단계 뒤에 이상한 시나리오로만
 * 드러나고, 원인을 찾으려면 판을 다시 돌려 로그를 긁어야 한다. 실측 사흘 동안 같은 뿌리의 오답을
 * 세 번 만났고 세 번 다 그렇게 찾았다:
 *
 * ```
 * 런 212   1636→1637  KNOWN [7440]      실은 전투를 이겨야 한다
 * 런 216   1660→1661  NOT_REQUIRED      실은 진행도를 5 로 만들 조작이 없다
 * ```
 *
 * 전건을 미리 풀면 그 답이 **눈으로 볼 수 있는 물건**이 된다. 골든으로 박아 두면 지도가 안
 * 바뀌었는데 행렬이 바뀐 것이 곧 코드 버그다 — 위 셋이 전부 그렇게 한 줄 차이로 보였을 것이다.
 *
 * ## 짝은 걸음이 아니다
 *
 * 이 행렬은 **두 자리 사이만** 답한다. 짝으로 맞아도 이어 붙이면 틀릴 수 있다 — 실측(런 216,
 * 시나리오 703)에서 진행도를 0 으로 만드는 브리지가 바로 다음 자리는 살리고 두 칸 뒤를 죽였다.
 * 흐름이 성립하는지는 상태를 들고 걸어 봐야 알고, 그건 이 행렬을 읽는 쪽의 일이다.
 *
 * ## 값
 *
 * 세 가지다. *"이겨서 갈 수 있는 막힘"* 과 *"아예 길이 없는 막힘"* 은 아직 안 가른다 — 지금
 * 경로 답이 둘을 같은 [ScenarioPathResult.UNKNOWN] 으로 내고, 가르려면 답의 모양을 바꿔야 한다.
 */
@Component
class ScenarioFlowMatrix(private val pathService: ScenarioPathService) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * 한 자리에서 다른 자리로 갈 때 무엇이 필요한가.
     *
     * @property BESIDE 아무것도 필요 없다. 바로 이어진다.
     * @property BY_OPERATION 시킬 수 있는 조작이 사이에 있다.
     * @property BLOCKED 지도가 방법을 말하지 않는다. 무엇이 막는지는 [Cell.blockedBy] 에 있다.
     * @property UNCHECKED 확인 자체를 못했다 — 지도가 없거나 조회가 실패했다. **막혔다는 뜻이
     *   아니다.** 둘을 같게 다루면 지도 없는 프로젝트의 저작이 통째로 미상으로 덮인다.
     */
    enum class Link { BESIDE, BY_OPERATION, BLOCKED, UNCHECKED }

    /** 한 칸. [answer] 는 스텝을 만들 때 그대로 쓰려고 들고 있는다. */
    data class Cell(
        val link: Link,
        val capabilityIds: List<Long> = emptyList(),
        val actions: List<String> = emptyList(),
        val blockedBy: String? = null,
        val note: String = "",
    )

    /**
     * 푼 결과. 자기가 무엇을 담고 있는지 아는 물건이라 부르는 쪽이 짝을 다시 세지 않아도 된다.
     */
    class Matrix(
        val testCaseIds: List<Long>,
        private val cells: Map<Pair<Long, Long>, Cell>,
    ) {
        fun between(fromTestCaseId: Long, toTestCaseId: Long): Cell? =
            cells[fromTestCaseId to toTestCaseId]

        /** 이 자리 뒤에 올 수 있는 자리들. 막힌 것은 뺀다. */
        fun after(fromTestCaseId: Long): List<Long> = testCaseIds.filter { to ->
            to != fromTestCaseId &&
                cells[fromTestCaseId to to]?.link.let {
                    it == Link.BESIDE || it == Link.BY_OPERATION
                }
        }

        fun count(link: Link): Int = cells.values.count { it.link == link }

        /**
         * 사람이 읽는 형태. **막히지 않은 것만 적는다** — 42건이면 칸이 1722개라 전부 적으면
         * 읽을 수 없고, 알고 싶은 것은 "무엇이 이어지나"이지 "무엇이 안 이어지나"가 아니다.
         * 막힌 것은 무엇이 막았는지로 묶어 센다.
         */
        fun render(describe: (Long) -> String = { "$it" }): String = buildString {
            appendLine("케이스 ${testCaseIds.size}건 · 칸 ${cells.size}개")
            appendLine(
                "  바로 ${count(Link.BESIDE)} · 조작 ${count(Link.BY_OPERATION)} · " +
                    "막힘 ${count(Link.BLOCKED)} · 확인못함 ${count(Link.UNCHECKED)}"
            )
            appendLine()
            for (from in testCaseIds) {
                val open = testCaseIds.mapNotNull { to ->
                    if (to == from) return@mapNotNull null
                    val cell = cells[from to to] ?: return@mapNotNull null
                    when (cell.link) {
                        Link.BESIDE -> "$to 바로"
                        Link.BY_OPERATION -> "$to ← ${cell.actions.joinToString(" · ")}"
                        else -> null
                    }
                }
                appendLine("${describe(from).ifBlank { "$from" }}")
                if (open.isEmpty()) appendLine("    이어지는 자리 없음")
                else open.forEach { appendLine("    $it") }
            }
            appendLine()
            appendLine("막은 것:")
            cells.entries
                .filter { it.value.link == Link.BLOCKED }
                .groupingBy { it.value.blockedBy ?: "?" }
                .eachCount()
                .entries.sortedByDescending { it.value }
                .forEach { (what, howMany) -> appendLine("  $what — ${howMany}칸") }
        }
    }

    /**
     * [testCaseIds] 의 모든 순서쌍을 푼다. 방향이 다르면 답도 다르므로 양쪽을 다 본다.
     *
     * 한 번에 [PARALLEL] 개까지 동시에 묻는다. 전부 한꺼번에 던지면 커넥션 풀을 통째로 잡아
     * 같은 서버의 다른 요청이 굶는다 — 이 계산은 급한 것이 아니다.
     */
    suspend fun of(projectId: Long, appUserId: Long, testCaseIds: List<Long>): Matrix {
        val ordered = testCaseIds.distinct().sorted()
        val gate = Semaphore(PARALLEL)
        val started = System.currentTimeMillis()

        val cells = coroutineScope {
            ordered.flatMap { from ->
                ordered.mapNotNull { to ->
                    if (from == to) null
                    else async {
                        gate.withPermit { (from to to) to cellOf(projectId, appUserId, from, to) }
                    }
                }
            }.awaitAll().toMap()
        }

        val matrix = Matrix(ordered, cells)
        logger.info(
            "짝 행렬 [projectId={}] 케이스 {}건 · 칸 {}개 · {}ms — 바로 {} · 조작 {} · 막힘 {} · 확인못함 {}",
            projectId, ordered.size, cells.size, System.currentTimeMillis() - started,
            matrix.count(Link.BESIDE), matrix.count(Link.BY_OPERATION),
            matrix.count(Link.BLOCKED), matrix.count(Link.UNCHECKED),
        )
        return matrix
    }

    private suspend fun cellOf(projectId: Long, appUserId: Long, from: Long, to: Long): Cell {
        val answer = runCatching { pathService.findPath(projectId, appUserId, from, to) }
            .onFailure { logger.warn("경로 조회 실패 [{}→{}] {}", from, to, it.message) }
            .getOrNull()
            ?: return Cell(Link.UNCHECKED)
        // 확인 자체를 못한 답은 막힌 것이 아니다. 경로 서비스가 그 둘을 구분해 두었다.
        if (answer.unchecked) return Cell(Link.UNCHECKED, note = answer.note)
        return when (answer.result) {
            ScenarioPathResult.NOT_REQUIRED -> Cell(Link.BESIDE)
            ScenarioPathResult.KNOWN -> Cell(
                Link.BY_OPERATION,
                capabilityIds = answer.capabilityIds,
                actions = answer.actions,
            )
            ScenarioPathResult.UNKNOWN -> Cell(
                Link.BLOCKED,
                blockedBy = answer.blockedBy,
                note = answer.note,
            )
        }
    }

    private companion object {
        const val PARALLEL = 8
    }
}
