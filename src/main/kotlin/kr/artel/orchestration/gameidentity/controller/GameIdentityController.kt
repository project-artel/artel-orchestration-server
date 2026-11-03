package kr.artel.orchestration.gameidentity.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.gameidentity.dto.GameIdentityListResponse
import kr.artel.orchestration.gameidentity.dto.GameIdentityResponse
import kr.artel.orchestration.gameidentity.dto.PutGameIdentityRequest
import kr.artel.orchestration.gameidentity.service.GameIdentityService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 게임 개발자의 코딩 에이전트가 MCP 로 선언하는 게임 값의 뜻(V173). `DeveloperMacroController` 처럼 MCP 서버가 사용자의
 * MCP access JWT 를 그대로 실어 부르고, 브라우저 세션과 CLI 토큰으로도 같은 경로가 열린다.
 *
 * 이름(`Type.member`)은 등록에서 본문, 삭제에서 `name` 쿼리로 받는다. 기준 build 는 본문의 `gameBuildId` 다.
 *
 * 메서드 이름에 `GameIdentity` 를 붙이는 이유는 OpenAPI operationId 다(`DeveloperMacroController` 참고).
 */
@Tag(name = "Game Identity", description = "게임 개발자가 MCP 로 선언하는 게임 값의 뜻")
@RestController
@RequestMapping("/api/projects/{projectId}/game-identities")
class GameIdentityController(
    private val service: GameIdentityService,
) {

    @Operation(summary = "게임 값 선언 목록", description = "프로젝트의 선언을 이름 오름차순으로 읽는다.")
    @GetMapping
    suspend fun listGameIdentities(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
    ): GameIdentityListResponse = service.list(appUserId, projectId)

    @Operation(
        summary = "게임 값 선언 등록",
        description = "이름으로 넣거나 갱신하고 저장된 행을 돌려준다. 선언한 칸(`kind`·`base`·`meaning`·`changesWhen`·" +
            "`identifies`·`role`)이 바뀌면 `version` 이 오른다. 같은 선언을 다시 보내면 그대로다.",
    )
    @PutMapping
    suspend fun putGameIdentity(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Valid @RequestBody request: PutGameIdentityRequest,
    ): GameIdentityResponse = service.put(appUserId, projectId, request)

    @Operation(summary = "게임 값 선언 삭제", description = "행을 지운다. 그 이름이 없으면 404 다.")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteGameIdentity(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "선언 이름(`Type.member`)", required = true) @RequestParam name: String,
    ) = service.delete(appUserId, projectId, name)
}
