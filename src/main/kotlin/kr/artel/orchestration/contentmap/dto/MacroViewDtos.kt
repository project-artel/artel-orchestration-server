package kr.artel.orchestration.contentmap.dto

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * 이 빌드에 등록된 macro 전부. 이름 오름차순.
 *
 * `items` 로 감싸는 것은 이 저장소의 목록 응답 관례다(`GameBuildListResponse` ·
 * `TestCaseListResponse` · `KnowledgeListResponse`). 배열을 최상위로 내면 나중에 총 개수나 cursor
 * 를 더할 자리가 없다.
 *
 * **빈 목록은 404 가 아니다.** 빌드는 존재하고 접근도 되는데 아직 아무도 macro 를 등록하지 않은
 * 것이고, 그것은 화면이 "아직 등록된 macro 가 없다" 를 그려야 하는 정상 상태다. 지도가 아예 없는
 * 빌드도 같은 빈 목록으로 답한다 — `ContentMapResponse.EMPTY` 가 같은 판단이다.
 */
@Schema(description = "빌드에 등록된 macro 목록")
data class MacroListResponse(
    @Schema(description = "macro 목록. 이름 오름차순")
    val items: List<MacroSummaryResponse>,
) {
    companion object {
        val EMPTY = MacroListResponse(items = emptyList())
    }
}

/**
 * 목록에 서는 macro 한 줄.
 *
 * **[MacroDetailResponse.source] 를 싣지 않는 것이 이 타입의 요점이다.** `source` 는 한 건이 최대
 * 20,000자라(`MacroWriteFrames.MAX_SOURCE_LENGTH`) 목록에 실으면 빌드 하나가 수백 KB 가 된다.
 * 사람이 원문을 읽는 것은 하나를 열었을 때고, 목록은 무엇이 등록돼 있는지만 보여 준다.
 *
 * `definition`(JSON tree)도 양쪽 어디에도 싣지 않는다. 그것은 실행할 때마다 다시 파싱하지 않기
 * 위한 캐시이고(`MacroEntity.definitionJson`), 사람이 읽고 diff 하는 것은 원문이다. 텍스트가
 * 원본이고 JSON 은 파생이라는 결정이 조회에서도 그대로다.
 *
 * @property id `macro.id`. 상세 조회의 경로 변수가 이 값이다
 * @property updatedAt 정의를 마지막으로 고친 시각. 같은 이름을 다시 등록하면 제자리에서 갱신되므로
 *   이 값만 움직이고 [id] 는 그대로다
 */
@Schema(description = "등록된 macro 한 줄. source 는 싣지 않는다")
data class MacroSummaryResponse(
    val id: Long,
    @Schema(description = "진입점 `def` 의 이름. 같은 빌드 안에서 유일하다")
    val name: String,
    @Schema(description = "진입점 `def` 줄의 parameter. 순서가 뜻을 가진다")
    val parameters: List<MacroParameterResponse>,
    @Schema(description = "이 macro 를 쓸 수 있다고 이어 둔 screen. 빈 배열은 '아직 어디서 쓸지 모른다' 다")
    val screens: List<MacroScreenResponse>,
    @Schema(description = "정의를 마지막으로 고친 시각")
    val updatedAt: Instant,
)

/**
 * macro 하나의 상세. 목록 한 줄에 [source] 를 더한 것이다.
 *
 * 목록 타입을 품지 않고 칸을 펼쳐 둔 것은 의도다. 화면은 상세를 열 때 목록 항목을 다시 그리므로,
 * `{ "macro": {...}, "source": "..." }` 로 한 겹 싸면 읽는 쪽이 같은 값을 두 경로로 꺼내게 된다.
 *
 * @property source 저장된 **원문 그대로**. 공백을 깎지 않는다 — 들여쓰기가 Python 문법의 일부고,
 *   정의가 바뀌었을 때 사람이 보는 diff 가 agent 가 적은 그대로여야 한다
 */
@Schema(description = "등록된 macro 하나의 상세. 목록 항목에 source 를 더한 것")
data class MacroDetailResponse(
    val id: Long,
    val name: String,
    val parameters: List<MacroParameterResponse>,
    val screens: List<MacroScreenResponse>,
    val updatedAt: Instant,
    @Schema(description = "저장된 원문 그대로. 공백을 깎지 않는다")
    val source: String,
)

/**
 * 진입점 `def` 줄의 parameter 하나.
 *
 * **이름과 타입의 출처가 다르다.** 이름과 순서는 `macro.parameter_names` 에서 온다 — 그 칸이
 * `run_macro` 가 인자를 위치로 대응시킬 때 보는 값이라 개수와 순서의 원본이다. 선언 타입은 그
 * 칸에 없고(`V98` 이 일부러 안 담았다) `definition_json` 의 진입점 `def` 에만 있다.
 *
 * @property type 선언 타입. **null 이 정상일 수 있다** — 저장된 tree 가 이 parameter 의 타입을
 *   말하지 않으면 null 이다. tree 의 모양은 ARTEL-918 이 확정하므로, 그쪽이 key 이름을 바꾸는 날
 *   이 칸은 null 로 떨어지고 이름과 순서는 그대로 남는다. 조회가 통째로 깨지는 것보다 이쪽이 낫다
 */
@Schema(description = "macro 의 parameter 하나")
data class MacroParameterResponse(
    val name: String,
    @Schema(description = "선언 타입. 저장된 tree 가 말하지 않으면 null")
    val type: String?,
)

/**
 * 이 macro 가 이어진 `screen` 하나. 사람이 그 화면을 알아볼 값만 담는다.
 *
 * [sceneName] 을 함께 내는 이유: [name] 은 nullable 이고 LLM 이 짓는 표시용 값이라, 이름이 아직
 * 없는 `screen` 이 목록에 `null` 하나로 서면 사람이 어느 화면인지 가릴 수 없다. `scene` 이름은
 * NOT NULL 이라 늘 한 조각은 남는다.
 *
 * `discriminator` 는 담지 않는다. 그것은 기계가 이 화면임을 판정하는 조건이고 사람이 화면을
 * 알아보는 값이 아니다. 필요해지면 `ContentMapScreenResponse` 로 가면 된다.
 */
@Schema(description = "macro 가 이어진 screen 하나")
data class MacroScreenResponse(
    val id: Long,
    @Schema(description = "표시용. 아직 아무도 이름을 안 붙였으면 null")
    val name: String?,
    @Schema(description = "이 화면이 속한 씬의 이름")
    val sceneName: String,
)
