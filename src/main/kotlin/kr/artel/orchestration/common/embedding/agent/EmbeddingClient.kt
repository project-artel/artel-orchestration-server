package kr.artel.orchestration.common.embedding.agent

/**
 * 문자열을 벡터로 만드는 능력. 벡터를 만들 자격증명(OpenRouter 키)이 Agent에만 있어, 구현은
 * 항상 Orchestration → Agent(`POST /embed`) 방향으로 부른다.
 *
 * 도메인(knowledge/testcase)과 무관하다. 검색쿼리 생성 같은 도메인 특정 능력은 각 도메인의
 * 클라이언트에 남기고, 이 인터페이스는 순수 임베딩만 노출한다.
 *
 * 인터페이스로 두는 이유는 백필/검색 테스트가 실제 LLM 호출 없이 돌아야 하기 때문이다.
 */
interface EmbeddingClient {

    /** 문자열들을 벡터로 만든다. 배치 상한은 Agent 설정값(기본 128)이다. */
    suspend fun embed(texts: List<String>): EmbedResponse
}
