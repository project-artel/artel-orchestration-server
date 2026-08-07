package kr.artel.orchestration.knowledge.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 지식 그래프 탐색 설정(`artel.knowledge.graph`, ARTEL-275).
 *
 * 여기 값 전부가 **Agent 컨텍스트 예산**이다. 이웃은 검색 결과에 딸려 나가 런이 끝날 때까지
 * 전사에 남으므로(`app/agents/qa/knowledge.py`가 지식 결과를 접지 않는다고 적어 뒀다), 상한이
 * 없으면 검색 한 번이 프로젝트의 그래프 전부를 끌어온다. ARTEL-180이 검색 결과 수에 상한을 둔
 * 것과 같은 이유이고, 같은 자리에 다시 뚫리지 않게 하는 것이 이 클래스다.
 *
 * [KnowledgeSearchProperties]와 마찬가지로 **model이 없다.** 벡터 이웃도
 * [KnowledgeBackfillProperties.model]을 그대로 읽는다 — 설정을 둘로 두면 조용히 어긋나고 증상은
 * 오류가 아니라 항상 빈 결과다.
 */
@ConfigurationProperties(prefix = "artel.knowledge.graph")
data class KnowledgeGraphProperties(

    /**
     * 검색 결과 히트마다 1홉 이웃을 붙일 것인가.
     *
     * `true`가 기본이다. `knowledge_edge`가 비어 있으면 비용은 빈 결과를 내는 인덱스 질의 하나뿐이라
     * 관계가 쌓이기 전의 부담이 사실상 없다. 이 값은 **킬 스위치**다 — 늘어난 `knowledge_usage`
     * 쓰기나 전사 증가가 문제가 되면 재배포 없이 끌 수 있어야 한다.
     */
    val expandSearchHits: Boolean = true,

    /** 검색 자동 확장에서 히트 하나당 데려올 이웃 수. */
    val searchFanout: Int = 2,

    /**
     * 검색 한 번이 데려올 이웃의 총 상한.
     *
     * `히트 수 × searchFanout`이 아니라 별도 상한인 것이 요점이다. 5히트 × fanout 2면 최대 10줄이고,
     * 그것만으로 검색당 전사가 약 40% 는다. 8로 자르면 그 증가분이 한 자릿수 줄에서 멈춘다.
     */
    val searchNeighbourLimit: Int = 8,

    /** `expand_knowledge`가 깊이를 지정하지 않았을 때. */
    val defaultDepth: Int = 1,

    /**
     * `expand_knowledge`의 깊이 절대 상한.
     *
     * 2이지 3이 아닌 이유는 산수다: fanout 3에서 깊이 2는 최대 13노드, 깊이 3은 40노드다.
     * "호출 하나가 프로젝트를 통째로 컨텍스트에 끌어오면 안 된다"(ARTEL-180)가 선을 2에 긋는다.
     */
    val maxDepth: Int = 2,

    /** `expand_knowledge`에서 노드 하나당 데려올 이웃 수. */
    val fanout: Int = 3,

    /** `expand_knowledge` 한 번이 데려올 노드의 총 상한. 넘으면 잘라내고 `truncated`를 세운다. */
    val nodeBudget: Int = 20,

    /** `expand_knowledge`가 붙일 벡터 이웃 수. 검색 자동 확장에는 붙지 않는다. */
    val similarLimit: Int = 3,

    /**
     * 벡터 이웃으로 칠 코사인 **거리**의 상한(0.40 ≈ 유사도 0.60).
     *
     * ⚠️ **이 값은 추측이다.** 이 코퍼스의 쌍별 거리 분포를 본 적이 없다. 실제 프로젝트 하나의
     * 분포를 뽑아 무릎을 찾기 전까지는 믿지 말 것이고, 테스트도 이 상수 자체를 단언하지 않는다
     * (순서와 "임계값이 실제로 거른다"만 본다). 너무 느슨하면 이웃이 소음이 되고, 너무 빡빡하면
     * 아무것도 안 나와 기능이 있는지도 모르게 된다.
     */
    val similarMaxDistance: Double = 0.40
) {
    init {
        require(searchFanout > 0) { "artel.knowledge.graph.search-fanout는 1 이상이어야 합니다." }
        require(searchNeighbourLimit > 0) { "artel.knowledge.graph.search-neighbour-limit는 1 이상이어야 합니다." }
        require(maxDepth in 1..2) {
            "artel.knowledge.graph.max-depth는 1 또는 2여야 합니다(3 이상은 노드 수가 컨텍스트 예산을 넘습니다)."
        }
        require(defaultDepth in 1..maxDepth) {
            "artel.knowledge.graph.default-depth는 1 이상 max-depth($maxDepth) 이하여야 합니다."
        }
        require(fanout > 0) { "artel.knowledge.graph.fanout은 1 이상이어야 합니다." }
        require(nodeBudget > 0) { "artel.knowledge.graph.node-budget은 1 이상이어야 합니다." }
        require(similarLimit >= 0) { "artel.knowledge.graph.similar-limit는 0 이상이어야 합니다." }
        require(similarMaxDistance in 0.0..2.0) {
            "artel.knowledge.graph.similar-max-distance는 코사인 거리라 0.0~2.0 사이여야 합니다."
        }
    }
}
