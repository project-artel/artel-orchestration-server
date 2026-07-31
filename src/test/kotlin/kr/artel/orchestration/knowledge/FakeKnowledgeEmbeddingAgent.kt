package kr.artel.orchestration.knowledge

import kr.artel.orchestration.common.embedding.agent.EmbedResponse
import kr.artel.orchestration.common.embedding.agent.EmbeddingClient
import kr.artel.orchestration.knowledge.agent.KnowledgeEmbeddingAgent
import kr.artel.orchestration.knowledge.agent.KnowledgeItemQueries
import kr.artel.orchestration.knowledge.agent.KnowledgeQueriesResponse
import kr.artel.orchestration.knowledge.agent.KnowledgeQueryItem
import java.util.concurrent.atomic.AtomicInteger

/**
 * Agent 서버(ARTEL-184) 대역.
 *
 * 워커가 검증해야 하는 것 — 빈 항목만 집는지, 한 tick 상한, 실패 시 `attempts`가 오르는지,
 * 상한을 넘긴 항목을 건너뛰는지 — 은 전부 Agent 응답의 내용과 무관하다. 그래서 실제 LLM 대신
 * 결정적인 응답을 돌려주고, 실패는 [failFor]로 지정해 재현한다.
 */
class FakeKnowledgeEmbeddingAgent(
    private val model: String
) : KnowledgeEmbeddingAgent, EmbeddingClient {

    /** 이 knowledge id들에 대해서는 검색쿼리 생성이 실패한다(Agent의 all-or-nothing 422 재현). */
    val failFor: MutableSet<Long> = mutableSetOf()

    /** true면 `/embed`가 항상 실패한다. */
    var embedFails: Boolean = false

    val generateQueriesCalls = AtomicInteger()
    val embedCalls = AtomicInteger()

    override suspend fun generateQueries(items: List<KnowledgeQueryItem>): KnowledgeQueriesResponse {
        generateQueriesCalls.incrementAndGet()
        // 실제 엔드포인트는 배치 중 하나만 실패해도 요청 전체를 422로 떨어뜨린다.
        val doomed = items.filter { it.id.toLongOrNull() in failFor }
        if (doomed.isNotEmpty()) {
            throw IllegalStateException("검색쿼리 생성 실패(테스트): ${doomed.map { it.id }}")
        }
        return KnowledgeQueriesResponse(
            results = items.map { item ->
                KnowledgeItemQueries(
                    id = item.id,
                    queries = List(QUERIES_PER_ITEM) { index -> "${item.summary} 질문$index" }
                )
            }
        )
    }

    override suspend fun embed(texts: List<String>): EmbedResponse {
        embedCalls.incrementAndGet()
        if (embedFails) throw IllegalStateException("임베딩 실패(테스트)")
        return EmbedResponse(
            model = model,
            dimensions = VECTOR_DIMENSIONS,
            // 텍스트마다 다른 벡터를 준다. 값 자체는 검증하지 않지만 전부 같으면
            // 행이 뒤바뀌어도 테스트가 못 잡는다.
            vectors = texts.map { text ->
                val seed = text.hashCode().toDouble() / Int.MAX_VALUE
                List(VECTOR_DIMENSIONS) { index -> seed + index * 1e-6 }
            }
        )
    }

    companion object {
        /** V18의 vector(1024)와 같아야 한다. 다르면 INSERT가 거절된다. */
        const val VECTOR_DIMENSIONS = 1024
        const val QUERIES_PER_ITEM = 3
    }
}
