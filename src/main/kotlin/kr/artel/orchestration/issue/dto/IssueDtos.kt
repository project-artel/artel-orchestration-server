package kr.artel.orchestration.issue.dto

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/**
 * 이슈 조회 응답. id 계열은 QaLog/QaTry 응답과 동일하게 문자열로 내보낸다(FE의 64비트 정밀도 손실 방지).
 * [detail]은 Agent가 보낸 payload 원본(JSONB)을 그대로 노출한다.
 */
data class IssueResponse(
    val id: String,
    val qaTryId: String,
    val messageId: String?,
    val correlationId: String?,
    val severity: String,
    val title: String,
    val detail: JsonNode,
    val createdAt: Instant
)
