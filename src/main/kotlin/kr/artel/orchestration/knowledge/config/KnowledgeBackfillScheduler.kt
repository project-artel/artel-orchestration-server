package kr.artel.orchestration.knowledge.config

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.knowledge.service.KnowledgeEmbeddingBackfillWorker
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

/**
 * 백필 워커의 스케줄 트리거. **이 저장소의 첫 스케줄러다**(`@EnableScheduling` 사용처가 없었다).
 *
 * `@EnableScheduling`을 애플리케이션 클래스가 아니라 여기에 두었다. 스케줄 스레드 풀이 이 기능
 * 때문에만 생기고, 기능을 끄면(`artel.knowledge.backfill.enabled=false`) 풀도 함께 사라진다.
 *
 * **왜 `runBlocking`인가.** `@Scheduled`는 suspend 함수를 부를 수 없어 어딘가에서 코루틴을 열어야
 * 한다. 여기서 막는 것이 오히려 맞는데, `fixedDelay`는 "이전 실행이 끝난 뒤 N밀리초"를 의미하므로
 * 블로킹이 곧 tick이 겹치지 않는다는 보장이 된다. 별도 스코프에 `launch`하면 그 보장이 사라져
 * Agent가 느릴 때 tick이 쌓이고, 같은 행을 두고 자기 자신과 경합하게 된다.
 * 막는 스레드는 요청 처리용이 아니라 스케줄러 전용이라 WebFlux 이벤트 루프를 건드리지 않는다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "artel.knowledge.backfill", name = ["enabled"], havingValue = "true")
class KnowledgeBackfillScheduler(
    private val worker: KnowledgeEmbeddingBackfillWorker
) {
    private val logger = LoggerFactory.getLogger(KnowledgeBackfillScheduler::class.java)

    @Scheduled(
        fixedDelayString = "\${artel.knowledge.backfill.interval-millis:60000}",
        initialDelayString = "\${artel.knowledge.backfill.interval-millis:60000}"
    )
    fun tick() {
        runBlocking {
            try {
                worker.runOnce()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 워커는 스스로 실패를 삼키지만, 큐 조회 자체가 깨질 수도 있다(DB 단절 등).
                // 그것 때문에 스케줄이 멈추면 복구된 뒤에도 영영 돌지 않는다.
                logger.error("knowledge 백필 tick 실패: {}", error.message, error)
            }
        }
    }
}
