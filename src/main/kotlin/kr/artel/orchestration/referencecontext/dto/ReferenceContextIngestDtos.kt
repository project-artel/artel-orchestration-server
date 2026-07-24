package kr.artel.orchestration.referencecontext.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Agent `/extract` 산출물 `game_context`를 그대로 받는 적재(ingest) DTO.
 *
 * 상단 8개 섹션은 고정 프레임이며, 게임별 다양성은 각 항목 내부(rules/attributes/steps 등)로 흡수된다.
 * 필드명은 Agent 계약(대부분 소문자 단어, `core_loop`만 snake_case)에 맞춘다.
 */
data class GameContextPayload(
    val overview: GameOverview? = null,
    val screens: List<GameScreen> = emptyList(),
    val mechanics: List<GameMechanic> = emptyList(),
    val entities: List<GameEntity> = emptyList(),
    val progression: List<GameProgressionItem> = emptyList(),
    val flows: List<GameFlow> = emptyList(),
    val glossary: List<GameGlossaryItem> = emptyList(),
    val misc: List<GameMiscItem> = emptyList()
)

data class GameOverview(
    val title: String? = null,
    val genre: String? = null,
    val platform: String? = null,
    val summary: String? = null,
    @JsonProperty("core_loop") val coreLoop: String? = null
)

data class GameScreen(
    val name: String,
    val purpose: String? = null,
    val elements: List<String> = emptyList(),
    val transitions: List<String> = emptyList()
)

data class GameMechanic(
    val name: String,
    val description: String? = null,
    val rules: List<String> = emptyList(),
    val preconditions: List<String> = emptyList()
)

data class GameEntity(
    val name: String,
    val type: String? = null,
    /** "key: value" 형태의 자유 서술 특성(예: "weakness: fire"). */
    val attributes: List<String> = emptyList()
)

data class GameProgressionItem(
    val name: String,
    val order: Int? = null,
    val notes: String? = null
)

data class GameFlow(
    val name: String,
    val steps: List<String> = emptyList()
)

data class GameGlossaryItem(
    val term: String,
    val meaning: String? = null
)

data class GameMiscItem(
    val note: String
)

/**
 * reference_context 적재 요청. 한 문서(sourceDocumentId)의 game_context를 타입별로 분해해 저장한다.
 * 같은 문서 재추출 시 그 문서의 기존 타입 행은 교체된다(멱등).
 *
 * @property projectId 소속 프로젝트(스코프)
 * @property sourceDocumentId 출처 문서(project_document.id) — 원본(S3) 추적 키
 * @property gameContext Agent가 추출한 game_context
 */
data class StoreReferenceContextRequest(
    val projectId: Long,
    val sourceDocumentId: Long,
    val gameContext: GameContextPayload
)
