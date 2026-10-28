package kr.artel.orchestration.qa.review

import java.time.Instant

enum class CaseOutcome { PASSED, FAILED, INCONCLUSIVE, INTERRUPTED }

data class ReliabilityObservation(
    val testCaseId: String,
    val observedAt: Instant,
    val agentVerdict: CaseOutcome,
    val humanVerdict: CaseOutcome?,
)

data class ReliabilityCounts(
    val total: Long,
    val reviewed: Long,
    val inconclusiveReviews: Long,
    val truePositives: Long,
    val trueNegatives: Long,
    val falsePositives: Long,
    val falseNegatives: Long,
    val interruptedFalseNegatives: Long,
    val falsePositiveRate: Double?,
    val falseNegativeRate: Double?,
    val reviewCoverage: Double?,
)

/** 사용자가 선택한 검토 표본만 집계한다. 분모가 없으면 0% 대신 null을 반환한다. */
fun reliabilityCounts(observations: List<ReliabilityObservation>): ReliabilityCounts {
    val truePositives = observations.count { it.agentVerdict == CaseOutcome.FAILED && it.humanVerdict == CaseOutcome.FAILED }.toLong()
    val trueNegatives = observations.count { it.agentVerdict == CaseOutcome.PASSED && it.humanVerdict == CaseOutcome.PASSED }.toLong()
    val falsePositives = observations.count { it.agentVerdict == CaseOutcome.FAILED && it.humanVerdict == CaseOutcome.PASSED }.toLong()
    val falseNegatives = observations.count { it.agentVerdict != CaseOutcome.FAILED && it.humanVerdict == CaseOutcome.FAILED }.toLong()
    val interrupted = observations.count {
        it.agentVerdict in setOf(CaseOutcome.INCONCLUSIVE, CaseOutcome.INTERRUPTED) && it.humanVerdict == CaseOutcome.FAILED
    }.toLong()
    val reviewed = observations.count { it.humanVerdict != null }.toLong()
    fun ratio(numerator: Long, denominator: Long): Double? =
        if (denominator == 0L) null else numerator.toDouble() / denominator
    return ReliabilityCounts(
        total = observations.size.toLong(), reviewed = reviewed,
        inconclusiveReviews = observations.count { it.humanVerdict == CaseOutcome.INCONCLUSIVE }.toLong(),
        truePositives = truePositives, trueNegatives = trueNegatives,
        falsePositives = falsePositives, falseNegatives = falseNegatives,
        interruptedFalseNegatives = interrupted,
        falsePositiveRate = ratio(falsePositives, falsePositives + trueNegatives),
        falseNegativeRate = ratio(falseNegatives, falseNegatives + truePositives),
        reviewCoverage = ratio(reviewed, observations.size.toLong()),
    )
}
