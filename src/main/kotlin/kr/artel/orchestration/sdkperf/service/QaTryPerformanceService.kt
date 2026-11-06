package kr.artel.orchestration.sdkperf.service

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.sdkperf.dto.QaTryPerformanceResponse
import kr.artel.orchestration.sdkperf.dto.QaTryPerformanceSample
import kr.artel.orchestration.sdkperf.repository.QaTryPerformanceRepository
import kr.artel.orchestration.sdkperf.repository.QaTryPerformanceRow
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** [QaTryPerformanceService.performance] 가 한 번에 돌려주는 표본 상한. 1초에 하나라 10분이다. */
const val MAX_QA_TRY_PERFORMANCE_SAMPLES = 600

/** `sinceFrame` 도 `lastSeconds` 도 없을 때 보는 구간. */
private const val DEFAULT_LAST_SECONDS = 60L

private const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0

/**
 * QA try 하나가 도는 동안 SDK 가 보낸 성능 표본을 agent 에게 준다(ARTEL-1223).
 *
 * 구간은 try 가 시작한 시각부터 끝난 시각(진행 중이면 지금)까지이고, 두 조건으로 더 좁힌다.
 * - `lastSeconds`: 지금(끝난 try 면 끝난 시각)부터 그 초만큼 전까지
 * - `sinceFrame`: `frame` 이 그 값 이상인 표본만
 *
 * 둘 다 없으면 마지막 60초다. 어느 경우든 마지막 [MAX_QA_TRY_PERFORMANCE_SAMPLES] 개까지만 준다.
 */
@Service
class QaTryPerformanceService(
    private val tryRepository: QaTryRepository,
    private val repository: QaTryPerformanceRepository,
    private val clock: Clock
) {
    suspend fun performance(qaTryId: Long, sinceFrame: Long?, lastSeconds: Long?): QaTryPerformanceResponse {
        if (lastSeconds != null && lastSeconds <= 0) {
            throw BadRequestException("lastSeconds 는 1 이상이어야 합니다.")
        }
        if (sinceFrame != null && sinceFrame < 0) {
            throw BadRequestException("sinceFrame 은 0 이상이어야 합니다.")
        }
        val qaTry = tryRepository.findById(qaTryId) ?: throw NotFoundException("QA try 를 찾을 수 없습니다.")
        val end = qaTry.completedAt ?: Instant.now(clock)
        val window = lastSeconds ?: if (sinceFrame == null) DEFAULT_LAST_SECONDS else null
        val from = window?.let { maxOf(qaTry.startedAt, end.minus(Duration.ofSeconds(it))) } ?: qaTry.startedAt
        val rows = repository.findSamples(
            qaTryId, qaTry.gameInstanceId, from, end, MAX_QA_TRY_PERFORMANCE_SAMPLES
        )
        val samples = rows
            .filter { row -> sinceFrame == null || (row.frame != null && row.frame >= sinceFrame) }
            .map { row -> row.toSample() }
        return QaTryPerformanceResponse(samples)
    }

    private fun QaTryPerformanceRow.toSample() = QaTryPerformanceSample(
        // 아직 frame 을 실은 메시지가 오지 않은 try 의 첫 표본이다. 그 try 의 어느 frame 보다도 앞이므로 0 으로 둔다.
        frame = frame ?: 0,
        at = receivedAt,
        frameTimeMs = meanMs,
        fps = if (sampledMs > 0) frameCount * 1000.0 / sampledMs else if (meanMs > 0) 1000.0 / meanMs else 0.0,
        hitchCount = hitchCount,
        cpuPercent = cpuPercent,
        memoryMb = workingSetBytes?.let { it / BYTES_PER_MEGABYTE }
    )
}
