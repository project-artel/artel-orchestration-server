package kr.artel.orchestration.testcase.config

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.testcase.service.TestCaseEmbeddingBackfillWorker
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

/**
 * test_case 백필 워커의 스케줄 트리거. `artel.testcase.embedding.enabled=true`일 때만 뜬다.
 *
 * `fixedDelay`(이전 실행 종료 후 N밀리초) + `runBlocking`으로 tick이 겹치지 않게 한다 — 겹치면 같은
 * 행을 두고 자기 자신과 경합한다. 막는 스레드는 스케줄러 전용이라 WebFlux 이벤트 루프를 건드리지
 * 않는다. (근거는 knowledge 스케줄러와 동일.)
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "artel.testcase.embedding", name = ["enabled"], havingValue = "true")
class TestCaseEmbeddingScheduler(
    private val worker: TestCaseEmbeddingBackfillWorker
) {
    private val logger = LoggerFactory.getLogger(TestCaseEmbeddingScheduler::class.java)

    @Scheduled(
        fixedDelayString = "\${artel.testcase.embedding.interval-millis:10000}",
        initialDelayString = "\${artel.testcase.embedding.interval-millis:10000}"
    )
    fun tick() {
        runBlocking {
            try {
                worker.runOnce()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.error("test_case 백필 tick 실패: {}", error.message, error)
            }
        }
    }
}
