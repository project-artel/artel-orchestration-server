package kr.artel.orchestration.knowledge.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * knowledge 벡터 검색 설정(`artel.knowledge.search`).
 *
 * **model이 여기 없는 것은 의도적이다.** 검색은 [KnowledgeBackfillProperties.model]을 그대로 읽는다.
 * 쓰는 쪽과 읽는 쪽이 같은 `knowledge_embedding.model` 파티션을 봐야 하는데, 설정을 둘로 두면 조용히
 * 어긋나고 그 증상은 오류가 아니라 **항상 빈 결과**다. 원인을 찾기 어려운 종류의 결함이라 설정 자체를
 * 하나로 둔다.
 */
@ConfigurationProperties(prefix = "artel.knowledge.search")
data class KnowledgeSearchProperties(

    /** 요청이 개수를 지정하지 않았을 때 돌려줄 항목 수. */
    val defaultLimit: Int = 5,

    /**
     * 한 번의 검색이 돌려줄 수 있는 항목 수의 절대 상한.
     *
     * 결과가 그대로 Agent 컨텍스트로 들어간다. 상한이 없으면 요청 하나가 프로젝트의 지식 전부를
     * 끌어와 ARTEL-180이 막아 둔 컨텍스트 증식을 다시 연다. 요청이 더 큰 값을 보내면 거절이 아니라
     * 이 값으로 자른다 — 도구 호출 하나가 실패하는 것보다 조용히 좁혀 답하는 편이 낫다.
     */
    val maxLimit: Int = 20
) {
    init {
        require(maxLimit > 0) { "artel.knowledge.search.max-limit는 1 이상이어야 합니다." }
        require(defaultLimit in 1..maxLimit) {
            "artel.knowledge.search.default-limit는 1 이상 max-limit($maxLimit) 이하여야 합니다."
        }
    }
}
