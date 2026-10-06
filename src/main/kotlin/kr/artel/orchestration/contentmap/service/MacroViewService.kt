package kr.artel.orchestration.contentmap.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.contentmap.dto.MacroDetailResponse
import kr.artel.orchestration.contentmap.dto.MacroDetailRow
import kr.artel.orchestration.contentmap.dto.MacroListResponse
import kr.artel.orchestration.contentmap.dto.MacroParameterResponse
import kr.artel.orchestration.contentmap.dto.MacroScreenResponse
import kr.artel.orchestration.contentmap.dto.MacroScreenRow
import kr.artel.orchestration.contentmap.dto.MacroSummaryResponse
import kr.artel.orchestration.contentmap.dto.MacroSummaryRow
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.MacroRepository
import kr.artel.orchestration.contentmap.repository.ScreenMacroRepository
import kr.artel.orchestration.game.repository.GameBuildRepository
import org.springframework.stereotype.Service

/**
 * 사람이 브라우저에서 **등록된 macro 를 읽는** 자리(ARTEL-943).
 *
 * 지금까지 `macro` 표에 닿는 길은 agent 의 WebSocket frame 둘뿐이었다(`MacroDefinitionService`).
 * 그 길은 agent 가 이름을 이미 아는 경우에만 쓸 수 있어, 빌드에 무엇이 등록돼 있는지를 묻는
 * 화면이 쓸 수 없다. 이 서비스가 그 창을 연다.
 *
 * ## 읽기 전용이다
 *
 * 이 경로로 macro 를 만들거나 고치거나 지우지 않는다. 쓰는 길은 agent 의 frame 뿐이고, 그래야
 * 저장된 정의가 실제로 돌려 본 것과 갈리지 않는다.
 *
 * ## 접근 검사가 컨트롤러가 아니라 여기 있다
 *
 * `ContentMapViewService` 와 같은 자리다. 이 표를 사람 쪽으로 읽는 유일한 문이 이 클래스라야,
 * 다음 진입점이 검사를 빠뜨릴 수 없다. 권한 축도 새로 만들지 않는다 — **그 빌드를 볼 수 있으면
 * 그 빌드의 macro 도 볼 수 있다.** `gameBuilds.findAccessibleById` 하나가 그 판정 전부다.
 *
 * 부재와 권한 없음을 같은 null(→ 404)로 묶는 것도 저쪽과 같다. 구분해서 알려주면 id 를 훑어 남의
 * 빌드가 존재한다는 사실을 알아낼 수 있다.
 *
 * ## `ContentMapMode` 게이트를 여기서 보지 않는다
 *
 * 그 게이트는 **런에 매달린 것**이다. `SceneContextService.resolveGate` 가 `qa_try.run_config` 를
 * 읽어 `mode.readable` 을 판정하고, `qaTryId` 가 없는 조회는 `ContentMapMode.DEFAULT` 로 간다고
 * 그 함수의 KDoc 이 적는다 — 런이 없으면 끌 것도 없다.
 *
 * 이 경로에는 런이 없다. 사람이 브라우저에서 빌드를 여는 것이고 어느 arm 으로 도는 중도 아니다.
 * 그래서 같은 자리의 `ContentMapViewService.read` 도 모드를 보지 않는다. 여기서만 보기 시작하면
 * 같은 빌드의 지도는 보이는데 macro 만 안 보이는 화면이 나온다.
 */
@Service
class MacroViewService(
    private val gameBuilds: GameBuildRepository,
    private val contentMaps: ContentMapRepository,
    private val macros: MacroRepository,
    private val screenMacros: ScreenMacroRepository,
    private val objectMapper: ObjectMapper,
) {

    /**
     * 이 빌드에 등록된 macro 전부. 접근할 수 없는 빌드면 null(→ 404).
     *
     * **질의 둘이다.** macro 행 하나와 `screen` 관계 하나. 한 질의로 조인하면 `screen` 수만큼 macro
     * 행이 곱해져 `source` 를 안 싣는 이득이 사라지고, macro 마다 관계를 따로 물으면 macro 수만큼
     * 왕복이 생긴다.
     *
     * 지도가 없는 빌드도 404 가 아니라 **빈 목록**이다. 빌드는 존재하고 접근도 되며, 없는 것은
     * 아직 아무도 등록하지 않은 macro 다 — `ContentMapResponse.EMPTY` 와 같은 판단이다.
     */
    suspend fun list(userId: Long, projectId: Long, gameBuildId: Long): MacroListResponse? {
        gameBuilds.findAccessibleById(gameBuildId, projectId, userId) ?: return null

        // 빌드마다 지도가 하나라 고를 것이 없다(ARTEL-642).
        val contentMapId = contentMaps.findByGameBuildId(gameBuildId)?.id
            ?: return MacroListResponse.EMPTY

        val screensByMacroId = screenMacros.findScreenRowsByContentMapId(contentMapId)
            .toList()
            .groupBy(MacroScreenRow::macroId)

        return MacroListResponse(
            items = macros.findSummariesByContentMapId(contentMapId)
                .toList()
                // 관계가 없는 macro 는 위 질의에 행이 없다. 그것을 목록에서 빼면 "아직 어디서 쓸지
                // 모른다" 가 "없다" 로 바뀐다 — 빈 배열로 **싣는** 것이 이 줄의 요점이다.
                .map { summaryOf(it, screensByMacroId[it.id].orEmpty()) },
        )
    }

    /**
     * macro 하나의 상세. 목록 한 줄에 원문을 더한 것이다. 못 찾으면 null(→ 404).
     *
     * null 이 되는 경우가 셋이고 전부 같은 404 다: 빌드에 접근할 수 없다 · 이 빌드에 지도가 없다 ·
     * 그 macro 가 이 빌드의 지도에 없다. 셋을 가려 주면 id 를 훑어 남의 빌드에 무엇이 있는지
     * 알아낼 수 있고, 화면이 셋에 대해 할 일도 같다.
     */
    suspend fun read(
        userId: Long,
        projectId: Long,
        gameBuildId: Long,
        macroId: Long,
    ): MacroDetailResponse? {
        gameBuilds.findAccessibleById(gameBuildId, projectId, userId) ?: return null

        val contentMapId = contentMaps.findByGameBuildId(gameBuildId)?.id ?: return null
        val row = macros.findDetailByContentMapIdAndId(contentMapId, macroId) ?: return null
        val screens = screenMacros.findScreenRowsByMacroId(contentMapId, macroId).toList()

        return MacroDetailResponse(
            id = row.id,
            name = row.name,
            parameters = parametersOf(row),
            screens = screens.map(::screenOf),
            updatedAt = row.updatedAt,
            source = row.source,
        )
    }

    private fun summaryOf(row: MacroSummaryRow, screens: List<MacroScreenRow>) =
        MacroSummaryResponse(
            id = row.id,
            name = row.name,
            parameters = parametersOf(row),
            screens = screens.map(::screenOf),
            updatedAt = row.updatedAt,
        )

    private fun screenOf(row: MacroScreenRow) =
        MacroScreenResponse(id = row.screenId, name = row.screenName, sceneName = row.sceneName)

    private fun parametersOf(row: MacroSummaryRow) =
        parameters(row.parameterNames, row.declaredParameters)

    private fun parametersOf(row: MacroDetailRow) =
        parameters(row.parameterNames, row.declaredParameters)

    /**
     * 이름과 순서는 `parameter_names` 에서, 선언 타입은 tree 에서 온다.
     *
     * **순서의 원본이 `parameter_names` 인 것이 이 함수의 요점이다.** 그 칸이 `run_macro` 가 인자를
     * 위치로 대응시킬 때 보는 값이므로, tree 쪽이 다른 순서로 적혀 있어도 실행되는 순서는 이쪽이다.
     * tree 를 순서의 출처로 쓰면 화면이 보여 주는 호출 모양이 실제로 도는 것과 갈린다.
     *
     * tree 는 타입만 보탠다. 못 찾으면 null 이고, 그것은 **정상일 수 있다** — 아직 tree 의 모양이
     * 계약이 아니다(ARTEL-918). 타입이 비어도 이름과 개수와 순서는 온전히 나간다.
     */
    private fun parameters(parameterNames: Json, declared: Json?): List<MacroParameterResponse> {
        val typeByName = declaredTypes(declared)
        return readArray(parameterNames)
            .mapNotNull { it.takeIf(JsonNode::isTextual)?.asText() }
            .map { MacroParameterResponse(name = it, type = typeByName[it]) }
    }

    /**
     * 진입점 `def` 의 `parameters` 조각에서 이름 → 선언 타입을 모은다.
     *
     * 기대는 것은 `[{"name": …, "type": …}, …]` 하나뿐이고, 그 모양이 아닌 항목은 조용히 건너뛴다.
     * 여기서 모양을 못 박으면 ARTEL-918 이 tree 를 확정하는 날 조회가 먼저 깨진다 — 타입은 있으면
     * 좋은 값이고 이 응답이 서는 근거가 아니다.
     *
     * 같은 이름이 둘이면 먼저 적힌 것을 쓴다. `def f(a, a)` 는 애초에 파싱이 안 되므로 그 입력은
     * 여기까지 오지 않는다.
     */
    private fun declaredTypes(declared: Json?): Map<String, String> {
        val node = declared?.let { readArray(it) } ?: return emptyMap()
        return node.mapNotNull { parameter ->
            val name = parameter.path("name").takeIf(JsonNode::isTextual)?.asText() ?: return@mapNotNull null
            val type = parameter.path("type").takeIf(JsonNode::isTextual)?.asText() ?: return@mapNotNull null
            name to type
        }.distinctBy { it.first }.toMap()
    }

    /** 배열이 아니면 빈 것으로 읽는다. `ck_macro_parameter_names_array` 가 한쪽은 이미 막는다. */
    private fun readArray(stored: Json): List<JsonNode> =
        objectMapper.readTree(stored.asString()).takeIf(JsonNode::isArray)?.toList().orEmpty()
}
