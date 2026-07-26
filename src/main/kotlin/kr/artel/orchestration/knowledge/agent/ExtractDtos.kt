package kr.artel.orchestration.knowledge.agent

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import kr.artel.orchestration.knowledge.dto.KnowledgeIngestItem
import kr.artel.orchestration.knowledge.dto.KnowledgeMetadata

/**
 * Agent `POST /extract` 요청. presigned GET URL과 포맷 힌트를 넘기면 Agent가 문서를 받아
 * knowledge 항목들을 뽑아 돌려준다. Agent는 자격증명 없이 익명 GET만 하므로 [sourceUrl]은
 * 반드시 서명된(presigned) URL이어야 한다.
 *
 * @property model 선택. null이면 직렬화에서 빠지고 Agent 기본 모델을 쓴다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ExtractRequest(
    @JsonProperty("source_url") val sourceUrl: String,
    val filename: String,
    val model: String? = null,
)

/**
 * Agent `/extract` 200 응답 — knowledge 인입 구조와 동일(`source`/`metadata`/`game_context[]`).
 * docs 경로에선 `metadata.hash`와 `gameContext`(항목 리스트)만 쓰고 나머지는 참고용이다.
 */
data class ExtractResponse(
    val source: String? = null,
    val metadata: KnowledgeMetadata? = null,
    @JsonProperty("game_context") val gameContext: List<KnowledgeIngestItem> = emptyList(),
)
