package kr.artel.orchestration.knowledge.agent

/**
 * knowledge 항목에서 검색쿼리를 만드는 능력(ARTEL-184). LLM으로 질문을 생성하는 자격증명은 Agent에만 있다.
 *
 * 순수 임베딩(`/embed`)은 도메인 무관이라 [kr.artel.orchestration.common.embedding.agent.EmbeddingClient]로
 * 분리했다. 이 인터페이스에는 knowledge 특정 능력(검색쿼리 생성)만 남는다.
 *
 * 인터페이스로 두는 이유는 백필 워커 테스트가 실제 LLM 호출 없이 돌아야 하기 때문이다.
 * 워커가 검증해야 하는 것(빈 항목만 집는지, 상한, 실패 시 attempts, 동시성)은 전부 Agent 응답과
 * 무관하므로, 테스트는 가짜 구현으로 응답을 고정하고 큐 동작만 본다.
 */
interface KnowledgeEmbeddingAgent {

    /**
     * 항목마다 "그 항목을 찾아낼 법한 질문"들을 만든다.
     *
     * **all-or-nothing이다.** 배치 중 한 항목이라도 생성에 실패하면 요청 전체가 422로 떨어진다
     * (agent-server `KnowledgeQueryAgent.run_batch`). 호출자는 그 실패를 배치 단위로 받아
     * 쪼갤지 포기할지 정해야 한다.
     */
    suspend fun generateQueries(items: List<KnowledgeQueryItem>): KnowledgeQueriesResponse
}
