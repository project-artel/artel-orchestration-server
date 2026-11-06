package kr.artel.orchestration.gamenote.entity

import com.fasterxml.jackson.annotation.JsonValue

/**
 * planner 프롬프트에 붙일 수 있는 genre module. [wire] 는 agent-server 의 `app/prompts/qa_planner/v1/genre_<name>.md`
 * 의 `<name>` 을 `-` 로 쓴 이름이고, `project_game_note.genres` · `/api/projects/{projectId}/game-notes` · 세션 개설
 * 본문의 `game_notes.genres` 의 값이다.
 *
 * 프로젝트 장르([kr.artel.orchestration.project.dto.Genre])와 다르다. 그쪽은 프로젝트를 분류하는 값이고, 이쪽은
 * planner 에게 줄 프롬프트 조각이다. agent-server 에 module 이 늘면 여기에도 더한다.
 */
enum class GenreModule(@get:JsonValue val wire: String) {
    UI_FLOW("ui-flow"),
    TURN_BASED("turn-based");

    companion object {
        val WIRE_NAMES: List<String> = entries.map { it.wire }

        fun fromWire(value: String): GenreModule? = entries.firstOrNull { it.wire == value }
    }
}
