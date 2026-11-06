package kr.artel.orchestration.common.embedding

/**
 * 임베딩이 끝난 텍스트 한 건: 입력 문자열과 그 벡터.
 *
 * 도메인(knowledge/testcase)과 무관한 값 타입이라 공용 임베딩 모듈에 둔다. pgvector가 받는
 * 리터럴 형식(`[0.1,0.2,...]`)을 만드는 곳이 하나여야 저장·검색 양쪽이 같은 형식을 쓴다.
 */
data class EmbeddedText(
    val text: String,
    val vector: List<Double>
) {
    /** pgvector가 받는 리터럴 형식: `[0.1,0.2,...]`. */
    fun toVectorLiteral(): String = vector.joinToString(prefix = "[", postfix = "]", separator = ",")
}
