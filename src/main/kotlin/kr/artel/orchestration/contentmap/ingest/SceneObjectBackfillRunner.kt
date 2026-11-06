package kr.artel.orchestration.contentmap.ingest

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener

/**
 * `scene_object` 백필 설정(`artel.content-map.scene-object-backfill`, ARTEL-1059).
 *
 * [enabled] 는 [SceneObjectBackfillRunner] 의 `@ConditionalOnProperty` 가 문자열로 읽는다. 기본값을 바꾸면
 * `application.yml` 의 기본값도 함께 바꾼다(`configuration.md` — 빈 주입보다 먼저 필요한 값).
 */
@ConfigurationProperties(prefix = "artel.content-map.scene-object-backfill")
data class SceneObjectBackfillProperties(
    /** 기동할 때 백필을 한 번 돌릴지. 테스트는 끈다 — 백필을 보는 테스트는 worker 를 직접 부른다. */
    val enabled: Boolean = true,
)

/**
 * 기동이 끝나면 [SceneObjectBackfillWorker] 를 **한 번** 돌린다.
 *
 * 다른 백필(`MacroEmbeddingScheduler` · `KnowledgeBackfillScheduler`)은 `@Scheduled` 로 주기마다 돈다. 그쪽은
 * 새 행이 계속 생기는 큐다. 이쪽은 V114 이전에 적재된 지도라는 닫힌 집합이고, V114 이후의 지도는 적재가
 * 직접 채운다. 그래서 주기 대신 기동 한 번이다. `runBlocking` 과 모든 예외를 삼키는 것은 그 스케줄러들과
 * 같은 이유다 — 백필이 깨졌다고 서버 기동이 실패하면 안 된다.
 */
@Configuration
@EnableConfigurationProperties(SceneObjectBackfillProperties::class)
@ConditionalOnProperty(prefix = "artel.content-map.scene-object-backfill", name = ["enabled"], havingValue = "true")
class SceneObjectBackfillRunner(
    private val worker: SceneObjectBackfillWorker,
) {
    private val logger = LoggerFactory.getLogger(SceneObjectBackfillRunner::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun runOnStartup() {
        runBlocking {
            try {
                worker.runOnce()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 지도마다의 실패는 worker 가 삼킨다. 여기까지 오는 것은 대상 조회 자체가 깨진 경우다(DB 단절 등).
                logger.error("scene_object 백필 실패: {}", error.message, error)
            }
        }
    }
}
