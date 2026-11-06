package kr.artel.orchestration.common.embedding.agent

/**
 * Agent `/embed` 계약(ARTEL-184, artel-agent-server `app/api/embeddings.py`).
 *
 * 도메인 무관한 문자열→벡터 계약이라 공용 임베딩 모듈에 둔다.
 */

data class EmbedRequest(
    val texts: List<String>
)

/**
 * 벡터와 그것을 만든 모델.
 *
 * [model]과 [dimensions]가 함께 오는 이유는 저장하는 쪽이 "이 벡터가 어느 모델 것인지"를 알아야
 * 재색인을 판단할 수 있기 때문이다. 저장하는 쪽은 이 값을 그대로 `*_embedding.model`에 적는다.
 */
data class EmbedResponse(
    val model: String = "",
    val dimensions: Int = 0,
    val vectors: List<List<Double>> = emptyList()
)
