package kr.artel.orchestration.contentmap.macro.search

import kr.artel.orchestration.common.embedding.BackfillTickResult
import kr.artel.orchestration.common.embedding.EmbeddingBackfillWorker
import kr.artel.orchestration.common.embedding.agent.EmbeddingClient
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator

/**
 * macro 에 검색용 벡터를 채우는 백필 워커(ARTEL-986). test_case 백필과 같은 공용 워커를 macro 큐,
 * 소싱, 설정으로 구성한다. macro 하나에 벡터 하나(`CONTENT`)다.
 */
@Service
class MacroEmbeddingBackfillWorker(
    embeddingRepository: MacroEmbeddingRepository,
    embeddingClient: EmbeddingClient,
    source: MacroEmbeddingSource,
    properties: MacroSearchProperties,
    transactionalOperator: TransactionalOperator,
) {
    private val delegate = EmbeddingBackfillWorker(
        queue = embeddingRepository,
        embeddingClient = embeddingClient,
        source = source,
        config = properties,
        transactionalOperator = transactionalOperator,
        kind = KIND,
    )

    suspend fun runOnce(): BackfillTickResult = delegate.runOnce()

    companion object {
        /** `macro_embedding.kind` 가 허용하는 유일한 값(V102 의 CHECK). */
        const val KIND = "CONTENT"
    }
}
