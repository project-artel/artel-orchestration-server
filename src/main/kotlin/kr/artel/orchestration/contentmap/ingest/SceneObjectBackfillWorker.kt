package kr.artel.orchestration.contentmap.ingest

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kr.artel.orchestration.contentmap.entity.ContentMapDocumentEntity
import kr.artel.orchestration.contentmap.evidence.EvidenceParser
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * V114 이전에 적재된 지도에 `scene_object` 를 채운다(ARTEL-1059).
 *
 * 그 지도들은 오브젝트를 버린 채 적재됐고, 원본 문서는 스토리지(`content-map-evidence/...`)에 그대로 있다.
 * 문서 전체를 다시 적재하지 않고 **오브젝트만** 앉힌다. 재적재는 그 사이 바뀐 적재 규칙을 함께 적용해
 * `capability` 를 지우거나 확인을 되돌릴 수 있고, 이 백필이 맡을 일이 아니다.
 *
 * 지도 하나가 한 트랜잭션이다. 한 지도가 깨져도(문서가 스토리지에 없거나 읽을 수 없으면) 로그만 남기고
 * 다음 지도로 간다 — 한 빌드의 문서 때문에 나머지 지도가 비어 있을 이유가 없다.
 *
 * 행이 이미 있는 지도는 고르지 않으므로 몇 번을 돌려도 같다. 다만 오브젝트가 하나도 `scene` 행에 붙지
 * 않는 지도는 돌 때마다 다시 읽힌다. 기동마다 한 번만 돌기 때문에 그 비용을 그대로 둔다.
 */
@Service
class SceneObjectBackfillWorker(
    private val documents: ContentMapDocumentRepository,
    private val scenes: SceneRepository,
    private val storage: DocumentStorage,
    private val writer: SceneObjectWriter,
    private val objectMapper: ObjectMapper,
    private val transactionalOperator: TransactionalOperator,
) {

    private val logger = LoggerFactory.getLogger(SceneObjectBackfillWorker::class.java)

    suspend fun runOnce(): SceneObjectBackfillResult {
        val pending = documents.findLatestIngestedWithoutSceneObjects().toList()
        var filled = 0
        var skipped = 0
        var failed = 0
        for (document in pending) {
            try {
                if (backfill(document) == null) skipped++ else filled++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failed++
                logger.warn(
                    "scene_object 백필 실패, 건너뜀 (contentMapId={}, documentId={}): {}",
                    document.contentMapId, document.id, failure.message, failure,
                )
            }
        }
        val result = SceneObjectBackfillResult(filled = filled, skipped = skipped, failed = failed)
        if (pending.isNotEmpty()) logger.info("scene_object 백필: {}", result)
        return result
    }

    /** 이 문서로 지도의 오브젝트를 채운다. 그 사이 적재가 먼저 채웠으면 null 이다. */
    private suspend fun backfill(document: ContentMapDocumentEntity): Int? {
        // 스토리지 읽기와 파싱은 트랜잭션 밖이다. 1.4MB 를 읽는 동안 커넥션을 잡고 있을 이유가 없다.
        val bytes = storage.read(document.objectKey).awaitSingleOrNull()
            ?: error("근거 문서를 스토리지에서 찾을 수 없습니다: ${document.objectKey}")
        val model = EvidenceParser(objectMapper).parse(bytes.decodeToString())
        return transactionalOperator.executeAndAwait {
            val sceneIds = scenes.findByContentMapIdOrderByNameAsc(document.contentMapId)
                .toList()
                .associate { it.name to it.id!! }
            writer.fillIfEmpty(document.contentMapId, model, sceneIds)
        }
    }
}

/**
 * [SceneObjectBackfillWorker.runOnce] 한 번의 결말. 모두 지도 수다.
 *
 * @property filled 오브젝트를 채운 지도. 붙을 `scene` 이 없어 0 행을 넣은 지도도 여기 센다.
 * @property skipped 읽는 사이 적재가 먼저 채운 지도.
 * @property failed 문서를 읽거나 쓰지 못해 건너뛴 지도. 사유는 로그에 있다.
 */
data class SceneObjectBackfillResult(
    val filled: Int,
    val skipped: Int,
    val failed: Int,
)
