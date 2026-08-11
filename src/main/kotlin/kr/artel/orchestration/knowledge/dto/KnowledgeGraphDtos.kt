package kr.artel.orchestration.knowledge.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * `KNOWLEDGE_LINK` 프레임의 payload(ARTEL-274).
 *
 * 모든 필드가 nullable이고 기본값이 있다. [KnowledgeMutationRequest]와 같은 이유다 — 파싱이
 * 실패하면 WS 수신 체인에서 예외가 나고, 그것이 소켓을 닫아 런 전체를 실패시킨다. 빠진 필드는
 * 파싱 뒤 서비스가 값(`Rejected`)으로 거절한다.
 *
 * 끝점을 문자열로 받는 것도 같은 관례다(FE·Agent 쪽 64비트 정밀도 손실 방지).
 */
data class KnowledgeLinkRequest(
    @JsonProperty("from_knowledge_id") val fromKnowledgeId: String? = null,
    @JsonProperty("to_knowledge_id") val toKnowledgeId: String? = null,
    val relation: String? = null,
    val note: String? = null,
)

/**
 * `KNOWLEDGE_UNLINK` 프레임의 payload(ARTEL-274).
 *
 * **edge id가 아니라 `(from, to, relation)` 삼중조를 받는다.** 에이전트는 edge id를 본 적이 없다 —
 * 이웃 줄에 찍히는 id는 knowledge id다. edge id를 노출하면 도구 하나 때문에 에이전트가 다뤄야 할
 * id 공간이 하나 더 생기고, 그것을 보여 주려면 검색 응답에도 실어야 한다.
 *
 * `note`가 없다. 거두는 이유는 도구의 `thought`가 지고 그것이 이미 런 타임라인에 남는다 —
 * 지워진 관계에 붙일 곳도 없다.
 */
data class KnowledgeUnlinkRequest(
    @JsonProperty("from_knowledge_id") val fromKnowledgeId: String? = null,
    @JsonProperty("to_knowledge_id") val toKnowledgeId: String? = null,
    val relation: String? = null,
)

/**
 * `KNOWLEDGE_EXPAND` 프레임의 payload(ARTEL-275).
 *
 * 검색과 달리 시작점이 하나다. 깊이는 `artel.knowledge.graph.max-depth`로 잘리고, 상한을 넘겼다고
 * 거절하지 않는다 — [kr.artel.orchestration.knowledge.config.KnowledgeSearchProperties]의 limit과
 * 같은 판단이다. 결과가 Agent 컨텍스트로 들어가는 것을 막는 것이 목적이지 도구 호출을 실패시키는
 * 것이 목적이 아니다.
 */
data class KnowledgeExpandRequest(
    @JsonProperty("knowledge_id") val knowledgeId: String? = null,
    val depth: Int? = null,
    @JsonProperty("include_similar") val includeSimilar: Boolean = true,
    /**
     * 이 확장이 난 런 스텝(선택, ARTEL-293). 검색과 같은 이유로 받는다 — 확장도 usage 행을
     * 남기므로, 검색만 좌표를 갖고 확장은 못 갖는 것은 같은 표에 두 규칙을 두는 셈이다.
     */
    val step: Int? = null,
)

/**
 * 이웃 하나. 검색 히트에 딸려 나가기도 하고 `KNOWLEDGE_EXPAND_RESULT`에 실리기도 한다.
 *
 * @property id 이 스코프에서 실제로 보이는 행의 id. 그림자가 있으면 **그림자의 id**다 —
 *   Agent가 이 값으로 다시 조회하거나 링크할 수 있어야 하기 때문이다.
 * @property relation `LEADS_TO`/`CONTRADICTS`/`REFINES`/`DEPENDS_ON`/`REPLACES` 또는 `SIMILAR`.
 *   **`SIMILAR`은 표시용 라벨일 뿐이고 저장될 수 없다**
 *   ([kr.artel.orchestration.knowledge.entity.KnowledgeRelation]의 CHECK에 없다).
 * @property origin `EDGE`면 런이 이유를 적어 주장한 관계, `VECTOR`면 조회 시점에 계산한 유사도다.
 *   진짜 판별자는 이 필드이지 [relation]이 아니다 — 에이전트가 둘을 같은 무게로 재면 안 된다.
 * @property direction seed 기준 방향(`OUT`/`IN`). 벡터 이웃은 `NONE`이고, 대칭 관계는 렌더가 무시한다.
 * @property note 그 관계를 주장한 이유. 벡터 이웃은 null이다 — 아무도 주장한 적이 없다.
 * @property depth seed에서 몇 홉인가. 1부터.
 * @property score 벡터 이웃의 코사인 유사도. `EDGE`는 null이다.
 * @property via 이 이웃이 어느 노드에 매달렸는가. 깊이 2에서 어디서 뻗어 나온 것인지를 준다.
 */
data class KnowledgeNeighbour(
    val id: String,
    val relation: String,
    val origin: String,
    val direction: String,
    val note: String?,
    val tag: String,
    val source: String,
    val summary: String,
    val depth: Int,
    val score: Double?,
    val via: String,
)

/**
 * `KNOWLEDGE_EXPAND_RESULT` 프레임의 payload.
 *
 * @property truncated 노드 예산이나 fanout이 무언가를 잘랐는가. 잘린 것을 조용히 감추면 Agent는
 *   "이게 전부"로 읽는다 — 상한이 있다는 사실 자체가 결과의 일부다.
 */
data class KnowledgeExpandResponse(
    val id: String,
    val summary: String,
    val neighbors: List<KnowledgeNeighbour>,
    val truncated: Boolean,
)
