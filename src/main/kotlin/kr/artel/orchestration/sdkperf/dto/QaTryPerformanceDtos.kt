package kr.artel.orchestration.sdkperf.dto

import java.time.Instant

/** `GET /internal/qa-tries/{qaTryId}/performance` 응답. QA agent 의 성능 조회 tool 이 읽는다(ARTEL-1223). */
data class QaTryPerformanceResponse(val samples: List<QaTryPerformanceSample>)

/**
 * SDK 가 1초마다 보낸 성능 표본 하나.
 *
 * @property frame 표본을 받기 직전에 이 try 로 들어온 `PULSE` · `ACTION_RESULT` 의 `frame`. SDK 성능 보고에는 frame 이
 *   없어서 가장 가까운 메시지의 값을 쓴다. pulse 가 초당 수 회 오므로 차이는 보통 1초 이내의 frame 이다
 * @property at 이쪽이 표본을 받은 시각
 * @property frameTimeMs 그 1초 창의 평균 frame time
 * @property fps 그 창에서 실제로 그린 frame 수를 창 길이로 나눈 값
 * @property hitchCount 그 창에서 `hitchThresholdMs` 를 넘은 frame 수
 * @property cpuPercent 프로세스 CPU 사용률. 읽을 수 없는 플랫폼이면 null 이다
 * @property memoryMb 프로세스 working set. 읽을 수 없는 플랫폼이면 null 이다
 */
data class QaTryPerformanceSample(
    val frame: Long,
    val at: Instant,
    val frameTimeMs: Double,
    val fps: Double,
    val hitchCount: Int,
    val cpuPercent: Double?,
    val memoryMb: Double?
)
