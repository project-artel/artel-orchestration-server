package kr.artel.orchestration.knowledge.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/**
 * Agent가 배치로 전달하는 knowledge 인입 구조. docs `/extract` 응답과 QA WS `KNOWLEDGE` 메시지가
 * 같은 모양을 쓴다(입력부가 다 Agent라 계약을 하나로 통일).
 *
 * @property source "docs" | "qa" (대소문자 무시 파싱)
 * @property metadata source별 가변 메타(있을 때만). docs는 filename/hash/id.
 * @property gameContext 항목 리스트 — 한 번에 여러 개 온다.
 */
data class KnowledgeIngestRequest(
    val source: String? = null,
    val metadata: KnowledgeMetadata? = null,
    @JsonProperty("game_context") val gameContext: List<KnowledgeIngestItem> = emptyList(),
)

/** source별 가변 메타. Phase 1은 docs의 hash/id만 컬럼으로 승격해 쓰고 filename은 저장 안 함. */
data class KnowledgeMetadata(
    val filename: String? = null,
    val hash: String? = null,
    val id: Long? = null,
)

/**
 * knowledge 한 항목. summary/description은 Agent 생성물로 그대로 TEXT 저장한다.
 * tag는 우리가 정의한 enum 토큰(CONTROL/INFO/MISC).
 */
data class KnowledgeIngestItem(
    val tag: String? = null,
    val summary: String? = null,
    val description: String? = null,
)

/** knowledge 조회 응답 한 줄. id 계열은 FE 64비트 정밀도 손실 방지로 문자열로 낸다. */
data class KnowledgeResponse(
    val id: String,
    val projectId: String,
    val source: String,
    val sourceId: String?,
    val contentHash: String?,
    val tag: String,
    val summary: String,
    val description: String,
    val createdAt: Instant,
)

/** knowledge 목록 응답. 팀 목록 API 관례에 맞춰 items로 감싼다. */
data class KnowledgeListResponse(
    val items: List<KnowledgeResponse>,
)
