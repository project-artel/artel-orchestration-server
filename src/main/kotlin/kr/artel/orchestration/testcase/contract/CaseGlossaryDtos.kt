package kr.artel.orchestration.testcase.contract

/** 용어집 화면(ARTEL-1312). [exits] 는 화면 이동 — 사람 · MCP 가 알려 주거나 QA 가 지나가며 본 것이다(ARTEL-1395). */
data class CaseGlossaryResponse(val terms: List<GlossaryTermView>, val exits: List<GlossaryExitView> = emptyList())

/** 화면 이동 하나. [source] 는 알려 준 쪽(`user` · `mcp`), QA 가 본 것은 null. */
data class GlossaryExitView(val from: String, val to: String, val by: String, val source: String?)

/**
 * 용어 하나.
 *
 * @property start 새 게임을 시작할 때 값. 모르면 `unknown`.
 * @property setBy 칸마다 정한 쪽(`ai` · `mcp` · `user`). 사람이 정한 칸은 AI · MCP 가 덮지 않는다.
 * @property issues 사람이 확인할 이유. `NO_MEANING`(설명 없음) · `NO_START`(시작 값 모름) · `START_MISMATCH`(시작 값이 종류와
 *   안 맞음 — has 인데 0/1 이 아니거나, value 인데 보기에 없거나, counter 인데 수가 아님).
 * @property usedBy 이 이름을 필요 · 남김으로 쓰는 TC 수.
 * @property gameVariable 이 이름에 연결한 게임 변수(SDK 가 알려 준 변수 이름, ARTEL-1395). 없으면 null.
 */
data class GlossaryTermView(
    val name: String,
    val kind: String,
    val meaning: String,
    val options: List<String>,
    val start: String,
    val aliases: List<String>,
    val setBy: Map<String, String>,
    val issues: List<String>,
    val usedBy: Int,
    val gameVariable: String? = null,
)

/** 사람이 용어 하나를 고친다. [name] 으로 찾는다. 비운 칸은 그대로 둔다. [gameVariable] 은 빈 값이면 연결을 끊는다. */
data class GlossaryTermEditRequest(
    val name: String? = null,
    val kind: String? = null,
    val meaning: String? = null,
    val options: List<String>? = null,
    val start: String? = null,
    val aliases: List<String>? = null,
    val gameVariable: String? = null,
)
