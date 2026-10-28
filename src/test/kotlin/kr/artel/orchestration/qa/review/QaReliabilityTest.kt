package kr.artel.orchestration.qa.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class QaReliabilityTest {
    private fun observation(agent: CaseOutcome, human: CaseOutcome?) =
        ReliabilityObservation("1", Instant.EPOCH, agent, human)

    @Test
    fun `검토 표본에서 실제 통과와 실패를 각각 분모로 사용한다`() {
        val result = reliabilityCounts(listOf(
            observation(CaseOutcome.FAILED, CaseOutcome.FAILED),
            observation(CaseOutcome.FAILED, CaseOutcome.PASSED),
            observation(CaseOutcome.PASSED, CaseOutcome.PASSED),
            observation(CaseOutcome.PASSED, CaseOutcome.FAILED),
            observation(CaseOutcome.FAILED, null),
            observation(CaseOutcome.PASSED, CaseOutcome.INCONCLUSIVE),
        ))
        assertEquals(0.5, result.falsePositiveRate)
        assertEquals(0.5, result.falseNegativeRate)
        assertEquals(6L, result.total)
        assertEquals(5L, result.reviewed)
        assertEquals(1L, result.inconclusiveReviews)
    }

    @Test
    fun `중단과 판단 불가에서 실제 실패를 확인하면 미탐에 포함한다`() {
        val result = reliabilityCounts(listOf(
            observation(CaseOutcome.INTERRUPTED, CaseOutcome.FAILED),
            observation(CaseOutcome.INCONCLUSIVE, CaseOutcome.FAILED),
            observation(CaseOutcome.INTERRUPTED, CaseOutcome.PASSED),
        ))
        assertEquals(2L, result.falseNegatives)
        assertEquals(2L, result.interruptedFalseNegatives)
        assertEquals(1.0, result.falseNegativeRate)
        assertNull(result.falsePositiveRate)
    }

    @Test
    fun `검토가 없으면 오류율을 0으로 표시하지 않는다`() {
        val result = reliabilityCounts(listOf(observation(CaseOutcome.PASSED, null)))
        assertNull(result.falsePositiveRate)
        assertNull(result.falseNegativeRate)
        assertEquals(0.0, result.reviewCoverage)
        assertNull(reliabilityCounts(emptyList()).reviewCoverage)
    }
}
