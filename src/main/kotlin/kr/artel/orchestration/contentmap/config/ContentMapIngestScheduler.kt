package kr.artel.orchestration.contentmap.config

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.contentmap.ingest.ContentMapIngestService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

/**
 * 등록된 근거 문서를 집어 적재한다 (ARTEL-502).
 *
 * `ContentMapIngestService.ingestPending()` 에는 호출자가 없었다. 등록(ARTEL-441)이 만든 행은
 * `ingested_at IS NULL` 로 큐에 남고 아무도 집어가지 않아, 적재기(ARTEL-442)가 다 되어 있는데도
 * 문서를 올리면 `scene` 과 `capability` 가 채워지지 않았다. **이것이 그 호출자다** — 적재기가
 * `@Transactional` 대신 `TransactionalOperator` 를 쓰는 이유를 적으며 지목한 바로 그 입구다.
 *
 * **왜 등록 응답이 아니라 배치인가.** 실측 문서가 1,413 KB 이고 적재는 그것을 통째로 파싱해
 * 씬 7 · 기능 491 행을 앉힌다. 등록 응답에 매달면 SDK 가 게임 실행마다 그 시간을 기다리는데,
 * 등록이 SDK 에 돌려주는 것은 문서를 받았다는 사실뿐이고 그것은 이미 참이다.
 *
 * `runBlocking` 과 `fixedDelay` 는 [kr.artel.orchestration.knowledge.config.KnowledgeBackfillScheduler]
 * 와 같은 이유다 — 블로킹이 곧 tick 이 겹치지 않는다는 보장이고, 막는 스레드는 스케줄러 전용이라
 * WebFlux 이벤트 루프를 건드리지 않는다.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ContentMapIngestProperties::class)
// placeholder 의 기본값은 ContentMapIngestProperties 의 기본 인자와 같이 움직여야 한다.
// 어노테이션은 빈을 읽을 수 없어 문자열로 쓸 수밖에 없다(configuration.md 의 예외 절).
@ConditionalOnProperty(prefix = "artel.content-map.ingest", name = ["enabled"], havingValue = "true")
class ContentMapIngestScheduler(
    private val ingest: ContentMapIngestService,
    private val properties: ContentMapIngestProperties,
) {
    private val logger = LoggerFactory.getLogger(ContentMapIngestScheduler::class.java)

    @Scheduled(
        fixedDelayString = "\${artel.content-map.ingest.interval-millis:60000}",
        initialDelayString = "\${artel.content-map.ingest.interval-millis:60000}"
    )
    fun tick() {
        runBlocking {
            try {
                val results = ingest.ingestPending(properties.batchSize, properties.maxAttempts)
                if (results.isNotEmpty()) {
                    logger.info(
                        "근거 문서 {}건 적재 (씬 {} · 기능 {})",
                        results.size,
                        results.sumOf { it.scenes },
                        results.sumOf { it.capabilities },
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 적재기는 문서 하나의 실패를 스스로 삼키지만, 큐 조회 자체가 깨질 수도 있다(DB 단절 등).
                // 그것 때문에 스케줄이 멈추면 복구된 뒤에도 영영 돌지 않는다.
                logger.error("근거 문서 적재 tick 실패: {}", error.message, error)
            }
        }
    }
}
