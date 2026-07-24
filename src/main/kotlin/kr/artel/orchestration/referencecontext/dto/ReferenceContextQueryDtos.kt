package kr.artel.orchestration.referencecontext.dto

import com.fasterxml.jackson.databind.JsonNode

/**
 * reference_context 조회 응답의 한 항목. Agent가 타입별로 뽑아 판단에 참고한다.
 *
 * @property sourceDocumentId 이 항목이 추출된 원본 문서(project_document.id)
 * @property type 섹션 타입(overview/screens/mechanics/…)
 * @property content 해당 타입의 요약 콘텐츠(overview=객체, 나머지=배열)를 구조화 JSON으로 반환
 */
data class ReferenceContextEntryResponse(
    val sourceDocumentId: Long,
    val type: String,
    val content: JsonNode
)

/**
 * reference_context 목록 응답. 팀 목록 API 관례에 맞춰 `items` 배열로 감싼다.
 * 같은 타입이라도 문서마다 별개 항목이므로 여러 문서의 항목이 함께 담길 수 있다.
 */
data class ReferenceContextListResponse(
    val items: List<ReferenceContextEntryResponse>
)
