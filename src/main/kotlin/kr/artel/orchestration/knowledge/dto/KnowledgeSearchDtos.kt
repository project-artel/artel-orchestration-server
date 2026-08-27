package kr.artel.orchestration.knowledge.dto

import com.fasterxml.jackson.annotation.JsonProperty

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
 * @property step 이 검색이 난 런 스텝(선택, ARTEL-293). `knowledge_usage.step`에 그대로 남는다.
 *   **검색 결과를 바꾸지 않는다** — 필터가 아니라 기록용 좌표다. 구버전 Agent는 싣지 않고,
 *   그때 값은 null("모른다")이 된다. 값을 검증하지 않는 것도 의도다: 잘못된 스텝 번호 때문에
 *   멀쩡한 검색을 거절하면, 기록 하나 때문에 런의 도구가 실패한다.
 * @property sceneName 이 씬에 묶인 지식만 본다(선택, ARTEL-591). **앵커가 없는 지식은 걸러진다** —
 *   이 필터의 뜻이 "이 화면의 것"이고, 게임 전체의 사실까지 함께 내면 필터가 좁히는 것이 없다.
 *   [tags]와 달리 알 수 없는 값을 거절하지 않는다: 씬 이름은 게임이 부르는 대로이고 우리가 아는
 *   목록이 없다(V55). 없는 씬 이름은 빈 결과가 되며, 그것이 정답이다.
 */
data class KnowledgeSearchRequest(
    val query: String? = null,
    val tags: List<String> = emptyList(),
    val tag: String? = null,
    val source: String? = null,
    val limit: Int? = null,
    val step: Int? = null,
    @JsonProperty("scene_name") val sceneName: String? = null
)

/**
 * 지식 하나가 묶인 씬·화면 한 줄(ARTEL-591).
 *
 * [KnowledgeSearchHit.anchors]에 실려 나간다. **비어 있으면 게임 전체의 사실**이고, 그것이
 * 기본값이라 앵커가 하나도 없는 지식창고에서도 이 필드는 그냥 빈 배열이다.
 *
 * `neighbors`(ARTEL-275)와 같이 **순수 추가 필드다** — Agent의 pydantic 모델이 `extra="allow"`라
 * 이 필드를 모르는 Agent는 통째로 무시한다. 그래서 Orchestration을 먼저 내보낼 수 있다.
 *
 * @property screenId 판정된 화면 id. **null이 정상이다** — 화면은 pulse 관측으로 판정되는 것이라
 *   (V40) 판정이 안 되는 순간이 있고, 그때 앵커는 씬까지만 말한다. id 계열은 다른 payload와 같이
 *   문자열로 낸다(FE 64비트 정밀도 손실 방지).
 */
data class KnowledgeAnchorView(
    @JsonProperty("scene_name") val sceneName: String,
    @JsonProperty("screen_id") val screenId: String? = null
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
 * @property anchors 이 지식이 묶인 씬·화면(ARTEL-591). [neighbors]와 같은 이유로 순수 추가
 *   필드다. 비어 있으면 게임 전체의 사실이라는 뜻이지 "앵커를 못 읽었다"가 아니다 — 이 둘을
 *   구분할 필요가 생기면 그때 별도 신호를 둔다.
 */
data class KnowledgeSearchHit(
    val id: String,
    val tag: String,
    val source: String,
    val summary: String,
    val description: String,
    val score: Double,
    val neighbors: List<KnowledgeNeighbour> = emptyList(),
    val anchors: List<KnowledgeAnchorView> = emptyList()
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
