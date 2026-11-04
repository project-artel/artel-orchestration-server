package kr.artel.orchestration.release.service

import kr.artel.orchestration.release.repository.VerificationAttemptRow
import kr.artel.orchestration.release.repository.VerificationCaseRow
import java.time.Instant

/** 한 시점의 TC 상태 수. 뜻은 [kr.artel.orchestration.release.dto.BuildVerificationSummaryResponse] 에 있다. */
data class VerificationCounts(
    val total: Int,
    val passed: Int,
    val failed: Int,
    val regression: Int,
    val inconclusive: Int,
    val carried: Int
) {
    val pending: Int get() = total - passed - failed - regression - inconclusive
}

/**
 * 빌드의 TC 하나하나를 시간순으로 따라가 어느 시점의 상태를 센다(ARTEL-1386).
 *
 * 판정은 두 곳에서 온다. 실행의 시도([VerificationAttemptRow])와, 그 버전 행의 지금 판정([VerificationCaseRow.statusPassed])이다.
 * 버전 행은 시도 없이도 판정이 날 수 있어서(앞 버전에서 이어받음, 사람이 직접 고침) 함께 본다. 버전 행의 판정은
 * `verified_at` 에 난 것으로 두되, 빌드가 올라오기 전이면(이어받은 판정) 빌드가 올라온 때로 당긴다.
 *
 * - 통과 · 실패는 그때까지의 **마지막** 통과 · 실패다. 판정 못함 시도는 앞서 난 통과 · 실패를 지우지 않는다.
 * - 통과 · 실패가 한 번도 없고 판정 못함 시도만 있으면 판정 못함이다.
 * - 실패 중 [previouslyPassed] 에 있는 TC 는 회귀다.
 *
 * 이 빌드의 TC 가 아닌 시도(빌드에서 빠진 TC)는 세지 않는다.
 */
class VerificationTimeline(
    cases: List<VerificationCaseRow>,
    attempts: List<VerificationAttemptRow>,
    private val previouslyPassed: Set<Long>,
    buildCreatedAt: Instant
) {
    private data class Event(val testCaseId: Long, val at: Instant, val passed: Boolean?, val fromAttempt: Boolean)

    private val total = cases.size
    private val carriedIds = cases.filter { it.carried && it.statusPassed != null }.mapTo(HashSet()) { it.testCaseId }
    private val events: List<Event>

    val lastJudgedAt: Instant?

    init {
        val ids = cases.mapTo(HashSet()) { it.testCaseId }
        val fromAttempts = attempts.filter { it.testCaseId in ids }.map { Event(it.testCaseId, it.finishedAt, it.passed, fromAttempt = true) }
        val fromStatus = cases.mapNotNull { row ->
            row.statusPassed?.let { Event(row.testCaseId, maxOf(row.statusAt ?: buildCreatedAt, buildCreatedAt), it, fromAttempt = false) }
        }
        // 같은 시각이면 시도를 뒤에 둔다 — 사람이 검토해 바꾼 시도의 판정이 버전 행보다 앞선다.
        events = (fromStatus + fromAttempts).sortedWith(compareBy<Event> { it.at }.thenBy { it.fromAttempt })
        lastJudgedAt = fromAttempts.maxOfOrNull { it.at }
    }

    /** [until] 앞(제외)까지 난 판정으로 센다. */
    fun countsBefore(until: Instant): VerificationCounts {
        val decided = HashMap<Long, Boolean>()
        val judgedHere = HashSet<Long>()
        val tried = HashSet<Long>()
        for (event in events) {
            if (!event.at.isBefore(until)) break
            when {
                event.passed == null -> tried += event.testCaseId
                else -> {
                    decided[event.testCaseId] = event.passed
                    if (event.fromAttempt) judgedHere += event.testCaseId
                }
            }
        }
        val failedIds = decided.filterValues { !it }.keys
        val regression = failedIds.count { it in previouslyPassed }
        return VerificationCounts(
            total = total,
            passed = decided.count { it.value },
            failed = failedIds.size - regression,
            regression = regression,
            inconclusive = tried.count { it !in decided },
            carried = carriedIds.count { it in decided && it !in judgedHere }
        )
    }
}
