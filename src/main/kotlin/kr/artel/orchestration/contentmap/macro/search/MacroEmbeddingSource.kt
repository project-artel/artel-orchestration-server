package kr.artel.orchestration.contentmap.macro.search

import kr.artel.orchestration.common.embedding.ClaimedRow
import kr.artel.orchestration.common.embedding.EmbeddingSource
import kr.artel.orchestration.common.embedding.EmbeddingSourceResult
import org.springframework.stereotype.Component

/**
 * macro 백필의 도메인 소싱(ARTEL-986). 골격(seed, claim, embed, store)은 공용
 * [kr.artel.orchestration.common.embedding.EmbeddingBackfillWorker] 가 담당하고, 여기서는 macro 를 읽어
 * [MacroEmbeddingText] 로 문장 하나를 만든다. 로컬 합성이라 소싱 자체의 실패는 없다. 대기 행을 만든
 * 뒤 macro 가 지워졌으면(FK CASCADE 가 대기 행을 먼저 지우므로 드물다) orphan 으로 넘겨 큐에서 버린다.
 */
@Component
class MacroEmbeddingSource(
    private val embeddings: MacroEmbeddingRepository,
) : EmbeddingSource {

    override suspend fun sourceTexts(claimed: List<ClaimedRow>): EmbeddingSourceResult {
        val byId = embeddings.findInputs(claimed.map { it.ownerId }).associateBy { it.macroId }
        val orphanedPendingIds = claimed.filter { it.ownerId !in byId }.map { it.pendingId }.toSet()
        val textsByOwnerId = claimed.mapNotNull { row ->
            byId[row.ownerId]?.let { row.ownerId to listOf(it.text()) }
        }.toMap()
        return EmbeddingSourceResult(textsByOwnerId, orphanedPendingIds, emptyMap())
    }
}
