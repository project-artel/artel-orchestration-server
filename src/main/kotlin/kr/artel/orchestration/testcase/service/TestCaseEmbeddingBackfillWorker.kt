package kr.artel.orchestration.testcase.service

import kr.artel.orchestration.common.embedding.BackfillTickResult
import kr.artel.orchestration.common.embedding.EmbeddingBackfillWorker
import kr.artel.orchestration.common.embedding.agent.EmbeddingClient
import kr.artel.orchestration.testcase.config.TestCaseEmbeddingProperties
import kr.artel.orchestration.testcase.repository.TestCaseEmbeddingRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator

/**
 * test_case에 검색용 벡터를 채우는 백필 워커(ARTEL-216).
 *
 * 골격은 공용 [EmbeddingBackfillWorker]가 갖고, 여기서는 test_case 큐/소싱/설정을 묶어 **CONTENT**
 * 벡터를 채우도록 구성한다. knowledge가 QUERY를 채우는 것과 대칭이며 같은 워커를 공유한다.
 */
@Service
class TestCaseEmbeddingBackfillWorker(
    embeddingRepository: TestCaseEmbeddingRepository,
    embeddingClient: EmbeddingClient,
    source: TestCaseEmbeddingSource,
    properties: TestCaseEmbeddingProperties,
    transactionalOperator: TransactionalOperator,
) {
    private val delegate = EmbeddingBackfillWorker(
        queue = embeddingRepository,
        embeddingClient = embeddingClient,
        source = source,
        config = properties,
        transactionalOperator = transactionalOperator,
        kind = CONTENT_KIND,
    )

    suspend fun runOnce(): BackfillTickResult = delegate.runOnce()

    private companion object {
        /** 케이스는 본문 합성 1벡터를 CONTENT로 채운다(QUERY 생성 없음). */
        const val CONTENT_KIND = "CONTENT"
    }
}
