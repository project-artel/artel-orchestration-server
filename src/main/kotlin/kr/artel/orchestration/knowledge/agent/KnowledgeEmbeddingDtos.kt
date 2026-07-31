package kr.artel.orchestration.knowledge.agent

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Agent 서버의 knowledge 벡터 API 계약(ARTEL-184, artel-agent-server PR #32).
 *
 * 필드 이름은 그쪽 pydantic 모델과 1:1이다. 추측이 아니라 `app/api/embeddings.py`,
 * `app/api/knowledge_queries.py`, `app/agents/knowledge_query/schemas.py`를 읽고 맞췄다.
 */

// ---------------------------------------------------------------------------
// POST /knowledge-queries
// ---------------------------------------------------------------------------

/**
 * 검색쿼리를 만들 knowledge 한 항목.
 *
 * [id]는 Agent가 그대로 되돌려 준다(불투명 값). 배치 응답을 리스트 순서가 아니라 이 값으로
 * 맞춰야 한다 — 순서에 기대면 Agent가 정렬을 바꾸는 순간 벡터가 엉뚱한 항목에 붙는다.
 */
data class KnowledgeQueryItem(
    val id: String,
    val summary: String,
    val description: String = ""
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class KnowledgeQueriesRequest(
    val items: List<KnowledgeQueryItem>
)

/** 항목 하나에 대해 생성된 검색 질문들(현재 3개). */
data class KnowledgeItemQueries(
    val id: String = "",
    val queries: List<String> = emptyList()
)

data class KnowledgeQueriesResponse(
    val results: List<KnowledgeItemQueries> = emptyList()
)

// POST /embed 계약(EmbedRequest/EmbedResponse)은 common/embedding/agent로 옮겼다 — knowledge 전용이
// 아니라 testcase 등 다른 도메인도 같은 임베딩을 쓴다.
