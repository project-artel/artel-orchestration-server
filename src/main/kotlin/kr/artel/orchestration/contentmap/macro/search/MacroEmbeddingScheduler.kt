package kr.artel.orchestration.contentmap.macro.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

/**
 * macro 백필 워커의 스케줄 트리거. `artel.macro.search.enabled=true` 일 때만 뜬다.
 *
 * `fixedDelay`(이전 실행이 끝난 뒤 N초)와 `runBlocking` 으로 tick 이 겹치지 않게 한다. 근거는
 * test_case 스케줄러와 같다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "artel.macro.search", name = ["enabled"], havingValue = "true")
class MacroEmbeddingScheduler(
    private val worker: MacroEmbeddingBackfillWorker,
) {
    private val logger = LoggerFactory.getLogger(MacroEmbeddingScheduler::class.java)

    @Scheduled(
        fixedDelayString = "\${artel.macro.search.fixed-delay:PT10S}",
        initialDelayString = "\${artel.macro.search.fixed-delay:PT10S}",
    )
    fun tick() {
        runBlocking {
            try {
                worker.runOnce()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.error("macro 백필 tick 실패: {}", error.message, error)
            }
        }
    }
}
