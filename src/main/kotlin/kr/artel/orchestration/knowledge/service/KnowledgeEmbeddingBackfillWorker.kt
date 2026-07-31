package kr.artel.orchestration.knowledge.service

import kr.artel.orchestration.common.embedding.BackfillTickResult
import kr.artel.orchestration.common.embedding.EmbeddingBackfillWorker
import kr.artel.orchestration.common.embedding.agent.EmbeddingClient
import kr.artel.orchestration.knowledge.config.KnowledgeBackfillProperties
import kr.artel.orchestration.knowledge.repository.KnowledgeEmbeddingRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator

/**
 * knowledge 항목에 검색용 벡터를 채우는 백필 워커.
 *
 * 골격(seed→claim→embed→store)은 공용 [EmbeddingBackfillWorker]가 갖고, 여기서는 knowledge 큐
 * ([KnowledgeEmbeddingRepository]) / 소싱([KnowledgeEmbeddingSource]) / 설정을 묶어 QUERY 벡터를
 * 채우도록 구성한다(ARTEL-215에서 공용 모듈로 추출).
 *
 * 이번 스프린트가 채우는 벡터는 QUERY뿐이다. CONTENT는 스키마(V18)에 자리만 있다.
 */
@Service
class KnowledgeEmbeddingBackfillWorker(
    embeddingRepository: KnowledgeEmbeddingRepository,
    embeddingClient: EmbeddingClient,
    source: KnowledgeEmbeddingSource,
    properties: KnowledgeBackfillProperties,
    transactionalOperator: TransactionalOperator,
) {
    private val delegate = EmbeddingBackfillWorker(
        queue = embeddingRepository,
        embeddingClient = embeddingClient,
        source = source,
        config = properties,
        transactionalOperator = transactionalOperator,
        kind = QUERY_KIND,
    )

    suspend fun runOnce(): BackfillTickResult = delegate.runOnce()

    private companion object {
        /** 이번 스프린트는 QUERY만 채운다. CONTENT는 스키마에 자리만 있다. */
        const val QUERY_KIND = "QUERY"
    }
}
