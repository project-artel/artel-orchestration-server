package kr.artel.orchestration.contentmap.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.contentmap.dto.MacroDetailResponse
import kr.artel.orchestration.contentmap.dto.MacroListResponse
import kr.artel.orchestration.contentmap.service.MacroViewService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 브라우저가 **등록된 macro 를 읽는** 자리(ARTEL-943).
 *
 * 주소가 `ProjectContentMapController` 와 같은 자리에 선다 — macro 는 빌드의 `content_map` 에
 * 매달린 자원이므로 `/api/projects/{projectId}/game-builds/{gameBuildId}/` 아래가 제자리다.
 * 컨트롤러를 따로 두는 것은 저쪽이 지도 한 장을 통째로 내는 타입 하나에 묶여 있고, 여기는 목록과
 * 상세 둘로 갈리기 때문이다.
 *
 * **쓰는 길이 여기 없다.** macro 를 만들거나 고치거나 지우는 것은 agent 의 WebSocket frame 뿐이고
 * (`MacroWriteFrames`), 사람이 손으로 정의를 적는 길은 만들지 않는다. 저장된 정의는 agent 가
 * 실제로 돌려 본 것이라야 다시 불렀을 때 돈다.
 *
 * 경로의 `projectId` 가 빌드의 프로젝트와 맞는지도 본다. 검사는 `MacroViewService` 안에 있다 —
 * 컨트롤러에 두면 다음 진입점이 빠뜨릴 수 있고, `ProjectContentMapController` 가 같은 자리에
 * 같은 이유를 적어 두었다.
 */
@Tag(name = "Macro", description = "빌드에 등록된 macro 를 읽는다")
@RestController
@RequestMapping("/api/projects/{projectId}/game-builds/{gameBuildId}/macros")
class ProjectMacroController(
    private val view: MacroViewService,
) {

    /**
     * 이 빌드에 등록된 macro 전부. 이름 오름차순.
     *
     * **`source` 를 싣지 않는다.** 한 건이 최대 20,000자라 목록에 실으면 빌드 하나가 수백 KB 가
     * 된다. 원문을 읽는 것은 [read] 의 몫이다.
     *
     * 404 는 **빌드가 없거나 경로의 `projectId` 가 그 빌드의 것과 다를 때**뿐이다. 등록된 macro 가
     * 없는 빌드는 빈 `items` 로 답한다 — 빌드는 존재하고 접근도 되며, 화면은 "아직 등록된 macro 가
     * 없다" 를 그려야 한다.
     */
    @Operation(
        summary = "등록된 macro 목록",
        description = "이 빌드에 등록된 macro 를 이름 오름차순으로 읽는다. `source` 는 싣지 않는다 — " +
            "원문은 상세 조회에 있다. 등록된 것이 없으면 빈 `items` 다.",
    )
    @GetMapping
    suspend fun list(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "게임 빌드 id", required = true) @PathVariable gameBuildId: Long,
    ): MacroListResponse =
        view.list(appUserId, projectId, gameBuildId)
            ?: throw NotFoundException("게임 빌드를 찾을 수 없습니다.")

    /**
     * macro 하나의 상세. 목록 한 줄에 **원문**을 더한 것이다.
     *
     * `definition`(JSON tree)은 싣지 않는다. 그것은 실행할 때마다 다시 파싱하지 않기 위한 캐시이고,
     * 사람이 읽고 diff 하는 것은 원문이다. 텍스트가 원본이고 JSON 이 파생이라는 결정이 조회에서도
     * 그대로다.
     *
     * 404 셋을 가르지 않는다 — 빌드에 접근할 수 없다 · 이 빌드에 지도가 없다 · 그 macro 가 이
     * 빌드에 없다. 가려 주면 id 를 훑어 남의 빌드에 무엇이 있는지 알아낼 수 있다.
     */
    @Operation(
        summary = "등록된 macro 상세",
        description = "macro 하나를 원문까지 읽는다. 목록 항목에 `source` 를 더한 모양이고, " +
            "실행용 JSON tree 는 싣지 않는다.",
    )
    @GetMapping("/{macroId}")
    suspend fun read(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "게임 빌드 id", required = true) @PathVariable gameBuildId: Long,
        @Parameter(description = "macro id", required = true) @PathVariable macroId: Long,
    ): MacroDetailResponse =
        view.read(appUserId, projectId, gameBuildId, macroId)
            ?: throw NotFoundException("macro 를 찾을 수 없습니다.")
}
