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
 * tag는 우리가 정의한 topic enum 토큰(CONTROL/RULE/OBJECTIVE/UI/MISC).
 */
data class KnowledgeIngestItem(
    val tag: String? = null,
    val summary: String? = null,
    val description: String? = null,
)

/**
 * knowledge 항목 하나에 대한 생성·수정·소프트삭제 요청(ARTEL-188). QA WS의
 * `KNOWLEDGE_CREATE` / `KNOWLEDGE_UPDATE` / `KNOWLEDGE_DELETE` payload가 이 모양이다.
 *
 * 셋이 DTO 하나를 공유하는 이유는 필드가 부분집합 관계이기 때문이다 — 생성은 [knowledgeId]가 없고,
 * 삭제는 [knowledgeId]만 쓰며, 수정은 둘 다 쓴다. 어떤 필드가 필수인지는 타입별로 서비스가 값으로
 * 검증한다(파싱 단계에서 throw하면 receive 파이프라인이 끊긴다).
 *
 * @property knowledgeId 대상 항목 id. 64비트 정밀도 손실을 피하려 조회 응답과 같이 문자열로 주고받는다.
 * @property tag 수정 시 null이면 그대로 둔다.
 * @property summary 수정 시 null이면 그대로 둔다.
 * @property description 수정 시 null이면 그대로 둔다.
 * @property sceneName 이 지식이 어느 씬의 것인지(선택, ARTEL-591). 있으면 `KNOWLEDGE_CREATE`가
 *   `anchor` 한 줄을 함께 남긴다. **없으면 게임 전체의 사실**이고, 그것이 기본값이라 `anchor` 를 싣지 않는
 *   프레임은 이 기능 이전과 완전히 같게 동작한다. 생성에만 쓰인다 — `anchor` 수정은 이 이슈의
 *   non-goal이라 `KNOWLEDGE_UPDATE`는 이 값을 보지 않는다.
 * @property screenId 판정된 화면(`content_map.screen.id`, 선택). [knowledgeId]와 같은 이유로
 *   문자열이다. **[sceneName] 없이 이것만 오면 거절한다** — 화면은 씬 안에 살고, 씬을 모르는
 *   화면 `anchor` 는 나중에 어느 씬의 화면이었는지 되짚을 수 없다.
 */
data class KnowledgeMutationRequest(
    @JsonProperty("knowledge_id") val knowledgeId: String? = null,
    val tag: String? = null,
    val summary: String? = null,
    val description: String? = null,
    @JsonProperty("scene_name") val sceneName: String? = null,
    @JsonProperty("screen_id") val screenId: String? = null,
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
