package kr.artel.orchestration.qa.review

import java.time.Instant

/** worker가 reserve 전에 종료돼도 recording 경계와 저장된 segment로 누락을 확인한다. */
fun missingVideoIntervals(start: Instant, end: Instant, segments: List<VideoSegment>): List<VideoGap> {
    if (end <= start) return emptyList()
    var cursor = start
    val gaps = mutableListOf<VideoGap>()
    for (segment in segments.filter { it.state != "UPLOADING" }.sortedBy { it.startedAt }) {
        if (segment.endedAt <= cursor || segment.startedAt >= end) continue
        if (segment.startedAt > cursor.plusSeconds(1)) gaps += VideoGap(cursor, segment.startedAt, "VIDEO_MISSING")
        cursor = maxOf(cursor, minOf(end, segment.endedAt))
    }
    if (end > cursor.plusSeconds(1)) gaps += VideoGap(cursor, end, "VIDEO_MISSING")
    return gaps
}
