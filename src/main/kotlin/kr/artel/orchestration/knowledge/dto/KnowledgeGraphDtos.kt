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
