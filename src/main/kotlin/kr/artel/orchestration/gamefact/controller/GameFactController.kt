package kr.artel.orchestration.gamefact.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.gamefact.dto.GameFactListResponse
import kr.artel.orchestration.gamefact.dto.GameFactResponse
import kr.artel.orchestration.gamefact.dto.GameRuleResponse
import kr.artel.orchestration.gamefact.dto.PutGameFactRequest
import kr.artel.orchestration.gamefact.dto.PutGameRuleRequest
import kr.artel.orchestration.gamefact.service.GameFactService
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
 * 게임 개발자의 코딩 에이전트가 MCP 로 선언하는 fact·rule(V174). `GameIdentityController` 처럼 MCP 서버가 사용자의 MCP
 * access JWT 를 그대로 실어 부르고, 브라우저 세션과 CLI 토큰으로도 같은 경로가 열린다.
 *
 * 이름은 등록에서 본문, 삭제에서 `name` 쿼리로 받는다. 메서드 이름에 자원 이름을 붙이는 이유는 OpenAPI operationId 다.
 */
@Tag(name = "Game Fact", description = "게임 개발자가 MCP 로 선언하는 fact 와 rule")
@RestController
@RequestMapping("/api/projects/{projectId}")
class GameFactController(
    private val service: GameFactService,
) {

    @Operation(summary = "fact·rule 목록", description = "프로젝트의 fact 와 rule 을 이름 오름차순으로 함께 읽는다.")
    @GetMapping("/game-facts")
    suspend fun listGameFacts(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
    ): GameFactListResponse = service.list(appUserId, projectId)

    @Operation(summary = "fact 등록", description = "이름으로 넣거나 갱신한다. 선언한 칸이 바뀌면 `version` 이 오른다.")
    @PutMapping("/game-facts")
    suspend fun putGameFact(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Valid @RequestBody request: PutGameFactRequest,
    ): GameFactResponse = service.putFact(appUserId, projectId, request)

    @Operation(summary = "fact 삭제", description = "행을 지운다. 없으면 404, 그 fact 를 말하는 rule 이 있으면 409 다.")
    @DeleteMapping("/game-facts")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteGameFact(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "fact 이름", required = true) @RequestParam name: String,
    ) = service.deleteFact(appUserId, projectId, name)

    @Operation(summary = "rule 등록", description = "이름으로 넣거나 갱신한다. 가리키는 fact 가 프로젝트에 없으면 400 이다.")
    @PutMapping("/game-rules")
    suspend fun putGameRule(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Valid @RequestBody request: PutGameRuleRequest,
    ): GameRuleResponse = service.putRule(appUserId, projectId, request)

    @Operation(summary = "rule 삭제", description = "행을 지운다. 없으면 404 다.")
    @DeleteMapping("/game-rules")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteGameRule(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "rule 이름", required = true) @RequestParam name: String,
    ) = service.deleteRule(appUserId, projectId, name)
}
