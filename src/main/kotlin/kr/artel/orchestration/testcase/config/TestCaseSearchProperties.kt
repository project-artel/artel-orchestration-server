package kr.artel.orchestration.testcase.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * TestCase 벡터 검색 설정(`artel.testcase.search`).
 *
 * **model이 여기 없는 것은 의도적이다.** 검색은 [TestCaseEmbeddingProperties.model]을 그대로 읽는다.
 * 쓰는 쪽(백필)과 읽는 쪽이 같은 `test_case_embedding.model` 파티션을 봐야 하는데, 설정을 둘로 두면
 * 조용히 어긋나고 그 증상은 오류가 아니라 **항상 빈 결과**다(knowledge와 같은 판단).
 */
@ConfigurationProperties(prefix = "artel.testcase.search")
data class TestCaseSearchProperties(

    /** 요청이 개수를 지정하지 않았을 때 돌려줄 항목 수. */
    val defaultLimit: Int = 5,

    /**
     * 한 번의 검색이 돌려줄 수 있는 항목 수의 절대 상한. 결과가 그대로 Agent 컨텍스트로 들어가므로,
     * 요청이 더 큰 값을 보내도 거절이 아니라 이 값으로 자른다.
     */
    val maxLimit: Int = 20,
) {
    init {
        require(maxLimit > 0) { "artel.testcase.search.max-limit는 1 이상이어야 합니다." }
        require(defaultLimit in 1..maxLimit) {
            "artel.testcase.search.default-limit는 1 이상 max-limit($maxLimit) 이하여야 합니다."
        }
    }
}
