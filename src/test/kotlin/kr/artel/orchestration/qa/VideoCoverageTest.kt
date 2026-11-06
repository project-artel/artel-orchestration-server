package kr.artel.orchestration.qa

import kr.artel.orchestration.qa.review.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class VideoCoverageTest {
    private val start = Instant.parse("2026-10-27T00:00:00Z")
    private fun segment(from: Long, to: Long, state: String = "READY") =
        VideoSegment(UUID.randomUUID(), start.plusSeconds(from), start.plusSeconds(to), state, null)

    @Test
    fun `첫 구간과 마지막 구간의 worker crash도 누락으로 표시한다`() {
        val gaps = missingVideoIntervals(start, start.plusSeconds(180), listOf(segment(60, 120)))
        assertThat(gaps.map { it.startedAt to it.endedAt }).containsExactly(
            start to start.plusSeconds(60), start.plusSeconds(120) to start.plusSeconds(180))
    }

    @Test
    fun `저장되지 않은 전체 구간은 누락이고 만료된 구간은 누락이 아니다`() {
        assertThat(missingVideoIntervals(start, start.plusSeconds(60), emptyList())).hasSize(1)
        assertThat(missingVideoIntervals(start, start.plusSeconds(60), listOf(segment(0, 60, "EXPIRED")))).isEmpty()
    }

    @Test
    fun `겹치는 구간과 업로드 중인 구간을 구별한다`() {
        assertThat(missingVideoIntervals(start, start.plusSeconds(90), listOf(segment(0, 60), segment(30, 90)))).isEmpty()
        assertThat(missingVideoIntervals(start, start.plusSeconds(90), listOf(segment(0, 60), segment(60, 90, "UPLOADING")))).hasSize(1)
    }
}
