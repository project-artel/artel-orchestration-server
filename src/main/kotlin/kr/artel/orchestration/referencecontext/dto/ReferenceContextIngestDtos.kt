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
