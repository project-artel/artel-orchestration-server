package kr.artel.orchestration.maintenance

import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.project.storage.RetiredStorageObject
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/** DB 삭제 전에 확보한 key와 전용 prefix의 orphan을 재시도하며 제거한다. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = ["artel.retired-demonstration-cleanup.enabled"], matchIfMissing = true)
@Component
class RetiredDemonstrationCleanup(private val db: DatabaseClient, private val storage: DocumentStorage) {
    private val running = AtomicBoolean()
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(initialDelay = 30000, fixedDelay = 60000)
    fun scheduled() {
        if (!running.compareAndSet(false, true)) return
        try {
            runBlocking { cleanup() }
        } catch (error: Exception) {
            logger.warn("시연 객체 정리가 완료되지 않아 다음 주기에 다시 시도합니다", error)
        } finally {
            running.set(false)
        }
    }

    suspend fun cleanup() {
        try {
            storage.retiredDemonstrationObjects().asFlow().collect { obj ->
                require(obj.key.startsWith("demonstrations/"))
                db.sql("""INSERT INTO retired_demonstration_object(object_key,version_id)
                    VALUES(:key,:version) ON CONFLICT DO NOTHING""")
                    .bind("key",obj.key).bind("version",obj.versionId ?: "").fetch().rowsUpdated().awaitSingle()
            }
        } catch (error: Exception) {
            logger.warn("시연 전용 prefix 조회를 다음 주기에 다시 시도합니다", error)
        }
        val pending = db.sql("""SELECT object_key,version_id FROM retired_demonstration_object
            WHERE deleted_at IS NULL ORDER BY last_attempted_at NULLS FIRST,object_key,version_id LIMIT 500""")
            .map { row, _ -> RetiredStorageObject(row.get("object_key",String::class.java)!!,
                row.get("version_id",String::class.java)!!.ifEmpty { null }) }.all().collectList().awaitSingle()
        for (obj in pending) {
            try {
                db.sql("""UPDATE retired_demonstration_object SET last_attempted_at=clock_timestamp()
                    WHERE object_key=:key AND version_id=:version""")
                    .bind("key",obj.key).bind("version",obj.versionId ?: "").fetch().rowsUpdated().awaitSingle()
                storage.deleteRetiredDemonstrationObject(obj).awaitSingleOrNull()
                db.sql("""UPDATE retired_demonstration_object SET deleted_at=now()
                    WHERE object_key=:key AND version_id=:version""")
                    .bind("key",obj.key).bind("version",obj.versionId ?: "").fetch().rowsUpdated().awaitSingle()
            } catch (error: Exception) {
                logger.warn("시연 객체 삭제가 완료되지 않아 다시 시도합니다 [key: {}]",obj.key,error)
            }
        }
    }
}