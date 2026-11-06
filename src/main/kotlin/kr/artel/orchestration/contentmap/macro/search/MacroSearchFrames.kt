package kr.artel.orchestration.contentmap.macro.search

import kr.artel.orchestration.scenecontext.dto.SceneContextMacroParameter
import kr.artel.orchestration.scenecontext.dto.SceneContextMacroScreen

/**
 * `MACRO_SEARCH` 프레임의 계약(ARTEL-986).
 *
 * ```
 * AGENT_TO_ORCHE  MACRO_SEARCH         등록된 macro 를 질의 문장으로 찾는다
 * ORCHE_TO_AGENT  MACRO_SEARCH_RESULT  MACRO_SEARCH 의 답
 * ORCHE_TO_AGENT  ERROR                거절. correlationId 가 요청을 문다
 * ```
 *
 * 필드 이름은 camelCase 다(`matchedBy`, `vectorAvailable`, `screenId`, `sceneName`). 같은 봉투의
 * `MACRO_READ_RESULT` 가 snake_case 인 것과 다르지만, 이 payload 는 scene context 의
 * `macros[].parameters` · `macros[].screens` 와 같은 모양을 그대로 싣도록 정해졌다.
 *
 * 범위는 이 런이 붙은 build 의 content map 하나다. `MACRO_READ` 와 같은 길로 찾고, 못 찾으면 거절한다.
 * `ContentMapMode.FROZEN` 게이트는 걸지 않는다 — 읽기만 한다.
 */
object MacroSearchFrames {

    /** 질의로 macro 를 찾는다. `AGENT_TO_ORCHE`. */
    const val SEARCH: String = "MACRO_SEARCH"

    /** [SEARCH] 의 답. `ORCHE_TO_AGENT`. */
    const val SEARCH_RESULT: String = "MACRO_SEARCH_RESULT"

    val INBOUND: Set<String> = setOf(SEARCH)

    /** 질의 문장 상한. 임베딩 입력이자 키워드 분해 입력이라 무한정 받지 않는다. */
    const val MAX_QUERY_LENGTH: Int = 500

    /** 키워드 목록에 쓰는 단어 수 상한. 단어마다 `ILIKE` 가 둘씩 붙어 SQL 이 단어 수에 비례해 커진다. */
    const val MAX_TERMS: Int = 8

    /** 두 목록(키워드, 벡터) 각각에서 합치기 전에 가져오는 개수. */
    const val CANDIDATES_PER_LIST: Int = 50
}

/**
 * [MacroSearchFrames.SEARCH] 의 payload.
 *
 * @property limit 1 이상 `max-limit` 이하. 없으면 `default-limit` 이다.
 * @property step 시나리오 step 번호. 로그에만 쓴다.
 */
data class MacroSearchRequest(
    val query: String? = null,
    val limit: Int? = null,
    val step: Int? = null,
)

/** 결과 항목이 어느 목록에서 나왔는지. */
enum class MacroMatchSource { KEYWORD, VECTOR }

/**
 * 검색 결과 macro 하나.
 *
 * @property parameters 진입점 `def` 의 parameter. scene context 의 `macros[].parameters` 와 같은 모양이다.
 * @property screens macro 가 달린 `screen`. scene context 의 `macros[].screens` 와 같은 모양이다.
 * @property matchedBy 이 macro 가 든 목록. 둘 다 들면 `KEYWORD`, `VECTOR` 순서다.
 * @property score reciprocal rank fusion 점수. 순서만 뜻이 있다.
 */
data class MacroSearchItem(
    val name: String,
    val parameters: List<SceneContextMacroParameter>,
    val docstring: String?,
    val screens: List<SceneContextMacroScreen>,
    val matchedBy: List<MacroMatchSource>,
    val score: Double,
)

/**
 * [MacroSearchFrames.SEARCH_RESULT] 의 payload.
 *
 * @property vectorAvailable false 면 질의 임베딩이 실패해 [items] 가 키워드 결과뿐이다.
 */
data class MacroSearchResponse(
    val items: List<MacroSearchItem>,
    val vectorAvailable: Boolean,
)
