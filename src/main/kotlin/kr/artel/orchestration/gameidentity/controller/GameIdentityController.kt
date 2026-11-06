package kr.artel.orchestration.gameidentity.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.auth.mcp.isMcpAccess
import kr.artel.orchestration.gameidentity.dto.*
import kr.artel.orchestration.gameidentity.service.GameIdentityService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*

@Tag(name = "Game Identity", description = "게임 값 선언과 build별 revision")
@RestController
@RequestMapping("/api/projects/{projectId}/game-identities")
class GameIdentityController(private val service: GameIdentityService) {
    @Operation(summary = "게임 값 선언 목록", description = "build를 지정하면 선택된 revision을 읽는다. includeDeleted=true는 tombstone도 포함한다.")
    @GetMapping
    suspend fun listGameIdentities(@CurrentUserId appUserId: Long, @PathVariable projectId: Long,
        @RequestParam(required = false) gameBuildId: Long?,
        @RequestParam(defaultValue = "false") includeDeleted: Boolean): GameIdentityListResponse =
        service.list(appUserId, projectId, gameBuildId, includeDeleted)

    @Operation(summary = "게임 값 선언 변경 이력", description = "프로젝트와 이름별 revision을 최신순으로 읽는다. 삭제 기록도 포함한다.")
    @GetMapping("/history")
    suspend fun gameIdentityHistory(@CurrentUserId appUserId: Long, @PathVariable projectId: Long,
        @RequestParam name: String): GameIdentityListResponse = service.history(appUserId, projectId, name)

    @Operation(summary = "게임 값 선언 등록", description = "build의 expectedVersion을 확인해 revision을 선택한다. 최초 등록은 0이며 충돌은 409다.")
    @PutMapping
    suspend fun putGameIdentity(@CurrentUserId appUserId: Long, @PathVariable projectId: Long,
        @AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody request: PutGameIdentityRequest): GameIdentityResponse =
        service.put(appUserId, projectId, request, if (jwt.isMcpAccess()) "MCP" else "WEB")

    @Operation(summary = "게임 값 선언 삭제", description = "build의 expectedVersion을 확인하고 tombstone revision을 기록한다.")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteGameIdentity(@CurrentUserId appUserId: Long, @PathVariable projectId: Long,
        @AuthenticationPrincipal jwt: Jwt, @RequestParam name: String,
        @RequestParam gameBuildId: Long, @RequestParam expectedVersion: Int) =
        service.delete(appUserId, projectId, name, gameBuildId, expectedVersion, if (jwt.isMcpAccess()) "MCP" else "WEB")
}
