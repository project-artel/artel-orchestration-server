package kr.artel.orchestration.sdkperf.config

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.sdkperf.repository.SdkPerformanceRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.time.Duration

/**
 * 원본 성능 표본의 보존 기간을 집행한다 (ARTEL-434에서 결정한 보존 정책).
 *
 * ARTEL-378은 원본 전량 보존으로 두되 `원본 롤업·삭제 정책 (보존 정책이 정해지면 별도)`를
 * Non-goals에 남겼다. 이것이 그 별도다.
 *
 * **요약·1초 시계열·budget 도수는 지우지 않는다.** 조회 API가 읽는 것은 그쪽뿐이라, 보존 기간이
 * 지난 런의 상세 화면도 그대로 뜬다. 사라지는 것은 표본 단위 드릴다운이고 그런 경로는 지금 없다.
 * 그래서 이 잡은 응답 계약에 영향을 주지 않는다.
 *
 * **기본값이 꺼짐인 이유.** 배포의 부수 효과로 운영 데이터가 지워지면 되돌릴 수 없다. 보존
 * 기간이 의도한 값인지 확인한 뒤 사람이 켠다.
 *
 * `runBlocking`과 `fixedDelay`는 [kr.artel.orchestration.knowledge.config.KnowledgeBackfillScheduler]와
 * 같은 이유다 — 블로킹이 곧 tick이 겹치지 않는다는 보장이고, 막는 스레드는 스케줄러 전용이라
 * WebFlux 이벤트 루프를 건드리지 않는다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "artel.sdk-performance.retention", name = ["enabled"], havingValue = "true")
class SdkPerformanceRetentionScheduler(
    private val repository: SdkPerformanceRepository,
    private val clock: Clock,
    @Value("\${artel.sdk-performance.retention.days:30}") private val retentionDays: Long,
    /** 한 번의 DELETE가 지우는 행 수. 한 문장으로 다 지우면 긴 잠금이 수신 경로를 막는다. */
    @Value("\${artel.sdk-performance.retention.batch-size:5000}") private val batchSize: Int,
    /**
     * 한 tick의 상한. 배치 하나로 끝내면 삭제 속도가 유입 속도를 못 따라간다 — 인스턴스 하나가
     * 시간당 3600행을 만드는데 시간당 5000행만 지우면 동시 접속 두 개부터 기준선이 영영
     * 전진하지 않고, `deleted > 0`이 계속 참이라 로그만 보면 정상으로 보인다.
     */
    @Value("\${artel.sdk-performance.retention.max-rows-per-tick:500000}") private val maxRowsPerTick: Int
) {
    private val logger = LoggerFactory.getLogger(SdkPerformanceRetentionScheduler::class.java)

    @Scheduled(
        fixedDelayString = "\${artel.sdk-performance.retention.interval-millis:3600000}",
        initialDelayString = "\${artel.sdk-performance.retention.interval-millis:3600000}"
    )
    fun tick() {
        runBlocking {
            try {
                val cutoff = clock.instant().minus(Duration.ofDays(retentionDays))
                var deleted = 0L
                while (deleted < maxRowsPerTick) {
                    val batch = repository.deleteSamplesOlderThan(cutoff, batchSize)
                    deleted += batch
                    if (batch < batchSize) break
                }
                if (deleted > 0) logger.info("성능 원본 표본 {}건 삭제 (기준 {})", deleted, cutoff)
                // 상한에 닿았다는 것은 이 tick이 밀린 분량을 다 못 지웠다는 뜻이다. 조용히 넘기면
                // 보존 기준선이 전진하지 않는 것을 아무도 모른다.
                if (deleted >= maxRowsPerTick) {
                    logger.warn("성능 원본 표본 삭제가 tick 상한({})에 도달했다. 보존 기준선이 밀리는 중일 수 있다.", maxRowsPerTick)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 삭제가 실패해도 스케줄은 살아 있어야 한다. 멈추면 복구된 뒤에도 영영 돌지 않는다.
                logger.error("성능 원본 표본 보존 tick 실패: {}", error.message, error)
            }
        }
    }
}
