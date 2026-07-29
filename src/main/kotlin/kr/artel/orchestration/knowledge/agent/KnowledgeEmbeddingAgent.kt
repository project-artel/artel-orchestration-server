package kr.artel.orchestration.knowledge.agent

/**
 * Agent 서버의 벡터 생산 능력(ARTEL-184). 벡터를 만들 수 있는 자격증명은 Agent에만 있다.
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

    /** 문자열들을 벡터로 만든다. 배치 상한은 Agent 설정값(기본 128)이다. */
    suspend fun embed(texts: List<String>): EmbedResponse
}
