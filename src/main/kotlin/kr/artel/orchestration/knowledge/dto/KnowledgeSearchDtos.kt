package kr.artel.orchestration.knowledge.dto

/**
 * QA WebSocket `KNOWLEDGE_SEARCH` 프레임의 payload(ARTEL-186).
 *
 * 쓰기 쪽 [KnowledgeIngestRequest]와 짝이 되는 읽기 계약이다. 검색 범위(projectId)는 payload에 없다 —
 * 라우터가 `qaTryId → gameInstanceId → projectId`로 해석한다. Agent가 프로젝트를 지목할 수 있으면
 * 프레임 하나로 남의 프로젝트 지식을 읽을 수 있게 된다.
 *
 * @property query 검색어. 이 문자열이 `/embed`로 벡터가 된다.
 * @property tags topic 하드 필터(선택). 비어 있으면 필터하지 않는다.
 * @property tag [tags]의 단수형(선택). Agent 쪽 도구가 어느 쪽으로 보낼지 짝 이슈에서 확정되기
 *   전까지 **둘 다 받는다.** 한쪽만 받으면 다른 쪽으로 온 필터가 조용히 무시되고, 그 증상은
 *   "필터가 안 걸린 결과"라 오류로 드러나지 않는다. 두 값은 합집합으로 쓴다.
 * @property source `DOCS`/`QA` 필터(선택, 대소문자 무시).
 * @property limit 돌려받을 항목 수(선택). `artel.knowledge.search`의 상한으로 잘린다.
 */
data class KnowledgeSearchRequest(
    val query: String? = null,
    val tags: List<String> = emptyList(),
    val tag: String? = null,
    val source: String? = null,
    val limit: Int? = null
)

/**
 * 검색 결과 한 항목.
 *
 * @property score 코사인 유사도(1에 가까울수록 가깝다). 항목당 벡터가 여럿이므로 **가장 가까운
 *   벡터의 값**이다. 원시 거리 대신 유사도로 내보내는 이유는 이 값이 Agent 프롬프트에 그대로
 *   들어가서인데, "작을수록 좋다"는 거리는 그 자리에서 거꾸로 읽히기 쉽다.
 * @property neighbors 이 항목에 한 홉으로 붙은 관계(ARTEL-275). **순수 추가 필드다** — JSON 객체에
 *   필드를 더하는 것이고 Agent의 pydantic 모델이 `extra="allow"`라, 이 필드를 모르는 Agent는
 *   통째로 무시한다. 그래서 Orchestration을 먼저 내보낼 수 있다.
 *
 *   `version`을 이 타입에 얹지 않은 기존 판단(ARTEL-255)과의 차이도 의도다: `version`은 기록용
 *   사실이라 [kr.artel.orchestration.knowledge.service.KnowledgeSearchOutcome] 쪽에 남고,
 *   이웃은 **Agent가 읽을 것**이라 WS 계약 객체에 속한다.
 */
data class KnowledgeSearchHit(
    val id: String,
    val tag: String,
    val source: String,
    val summary: String,
    val description: String,
    val score: Double,
    val neighbors: List<KnowledgeNeighbour> = emptyList()
)

/**
 * `KNOWLEDGE_SEARCH_RESULT` 프레임의 payload.
 *
 * [results]가 비어 있는 것은 오류가 아니다. 백필이 비동기라 방금 저장된 knowledge에 아직 벡터가
 * 없는 상태가 정상이고, 그때 답은 "없음"이다.
 *
 * @property model 어느 임베딩 모델로 검색했는지. 결과가 계속 비면 Agent 설정과 이 값을 맞춰 봐야
 *   하므로 응답에 실어 보낸다.
 */
data class KnowledgeSearchResponse(
    val query: String,
    val model: String,
    val results: List<KnowledgeSearchHit>
)
