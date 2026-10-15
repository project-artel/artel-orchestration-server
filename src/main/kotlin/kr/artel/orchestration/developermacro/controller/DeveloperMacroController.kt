package kr.artel.orchestration.developermacro.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.developermacro.dto.CheckDeveloperMacroRequest
import kr.artel.orchestration.developermacro.dto.DeveloperMacroCheckResponse
import kr.artel.orchestration.developermacro.dto.DeveloperMacroDetailResponse
import kr.artel.orchestration.developermacro.dto.DeveloperMacroListResponse
import kr.artel.orchestration.developermacro.dto.PutDeveloperMacroRequest
import kr.artel.orchestration.developermacro.service.DeveloperMacroService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 게임 개발자가 MCP 로 쓰는 macro(ARTEL-1088). `artel-macro-mcp` 가 사용자의 MCP access JWT 를 그대로 실어 부른다.
 * 브라우저 세션과 CLI 토큰으로도 같은 경로가 열린다.
 *
 * QA agent 가 등록한 macro(`ProjectMacroController`, build 의 `content_map` 소속)와 다른 자원이다. 그래서 주소가
 * `game-builds/{gameBuildId}` 아래가 아니라 프로젝트 바로 아래이고, 기준 build 는 본문의 `gameBuildId` 로 받는다.
 *
 * 클래스 단의 `@RequestMapping` 이 `/developer-macros` 까지 가지 않는 이유는 `:check` 때문이다. Spring 은 클래스 경로와
 * 메서드 경로를 `/` 로 이어 붙이므로 `":check"` 가 `/developer-macros/:check` 가 된다. 메서드마다 전체를 적는다.
 *
 * 메서드 이름에 `DeveloperMacro` 를 붙이는 이유는 OpenAPI operationId 다. `list`·`read`·`delete` 는 다른 컨트롤러에도
 * 있어 springdoc 이 `list_15` 같은 번호를 붙이고, 그 번호가 기존 operationId 의 번호까지 밀어 스냅샷이 흔들린다.
 */
@Tag(name = "Developer Macro", description = "게임 개발자가 MCP 로 검사하고 등록하는 macro")
@RestController
@RequestMapping("/api/projects/{projectId}")
class DeveloperMacroController(
    private val service: DeveloperMacroService,
) {

    @Operation(
        summary = "developer macro 목록",
        description = "프로젝트의 developer macro 를 이름 오름차순으로 읽는다. 원문(`source`)은 상세에 있다.",
    )
    @GetMapping("/developer-macros")
    suspend fun listDeveloperMacros(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
    ): DeveloperMacroListResponse = service.list(appUserId, projectId)

    @Operation(summary = "developer macro 상세", description = "목록 한 줄에 원문(`source`)을 더한 것이다.")
    @GetMapping("/developer-macros/{name}")
    suspend fun readDeveloperMacro(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "macro 이름(진입점 `def` 이름)", required = true) @PathVariable name: String,
    ): DeveloperMacroDetailResponse = service.read(appUserId, projectId, name)

    /**
     * 저장하지 않고 검사만 한다. 같은 프로젝트의 다른 developer macro 를 `from macros import` 로 가져올 수 있다.
     * 거절도 200 이고 `ok: false` 와 `rejection` 으로 온다.
     */
    @Operation(
        summary = "developer macro 검사",
        description = "agent-server 가 원문을 검사한다. 통과면 `{ok: true, parameters, docstring}`, 거절이면 " +
            "`{ok: false, rejection: {line, reason}}` 이고 둘 다 200 이다. 저장하지 않는다.",
    )
    @PostMapping("/developer-macros:check")
    suspend fun checkDeveloperMacro(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Valid @RequestBody request: CheckDeveloperMacroRequest,
    ): DeveloperMacroCheckResponse = service.check(appUserId, projectId, request)

    @Operation(
        summary = "developer macro 등록",
        description = "검사를 통과하면 이름으로 넣거나 갱신하고 저장된 행을 돌려준다. `version` 은 정의가 실제로 바뀔 " +
            "때만 오른다. 거절이면 400 `developer_macro_rejected` 이고 `fields` 에 `line`·`reason` 이 실린다.",
    )
    @PutMapping("/developer-macros/{name}")
    suspend fun putDeveloperMacro(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "macro 이름(진입점 `def` 이름)", required = true) @PathVariable name: String,
        @Valid @RequestBody request: PutDeveloperMacroRequest,
    ): DeveloperMacroDetailResponse = service.put(appUserId, projectId, name, request)

    @Operation(summary = "developer macro 삭제", description = "행을 지운다. 그 이름이 없으면 404 다.")
    @DeleteMapping("/developer-macros/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteDeveloperMacro(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "macro 이름(진입점 `def` 이름)", required = true) @PathVariable name: String,
    ) = service.delete(appUserId, projectId, name)
}
