package kr.artel.orchestration.knowledge.dto

import java.time.Instant

/**
 * knowledge 항목 하나를 본문까지 읽는 단건 조회 응답(ARTEL-753).
 *
 * [KnowledgeGraphNode]가 이미 낸 값(`tag`/`source`/`version`/`createdByQaTryId`/`createdAt`/`anchors`)
 * 은 되풀이하지 않는다. 브라우저는 사용자가 노드 하나를 고르기 전에 그래프 목록 호출로 그 값을 이미
 * 쥐고 있으므로, 이 응답은 그래프가 못 주는 값만 더한다.
 *
 * @property description 그래프 목록이 일부러 뺀 본문. [KnowledgeGraphNode]의 KDoc이 적은 이유(노드
 *   수백 개의 본문을 한 번에 내리면 응답이 화면이 쓰는 양의 몇 배가 된다)는 한 항목만 읽는 여기에는
 *   적용되지 않는다.
 * @property updatedAt 이 항목이 마지막으로 고쳐진 시각. [KnowledgeGraphNode]는 `createdAt`만 내므로
 *   "언제 고쳐졌는가"는 그래프가 주지 않는 새 정보다 — `version`이 "고쳐졌는가"(1보다 큰가)는
 *   말해도 "언제"는 말하지 않는다.
 * @property isDocumentNode 이 항목이 기획서 자체를 가리키는 구조적 표지인지(ARTEL-748). 문서 node의
 *   `description`에는 그 역할을 설명하는 고정 문장이 이미 들어 있지만, 그 문장은 자유 텍스트라
 *   프런트엔드가 파싱해 분기할 근거로 삼기엔 약하다(문장이 바뀌면 조용히 깨진다). 이 필드는
 *   [kr.artel.orchestration.knowledge.entity.KnowledgeDocumentNodeSql.IS_DOCUMENT_NODE] 판정
 *   하나로 정해지는 명시적 값이라, 화면이 "이 항목은 QA 런이 뽑아낸 사실이 아니라 문서 자체다"를
 *   문장 내용과 무관하게 판단할 수 있다.
 */
data class KnowledgeDetailResponse(
    val id: String,
    val summary: String,
    val description: String,
    val updatedAt: Instant?,
    val isDocumentNode: Boolean
)
