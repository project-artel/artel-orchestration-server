package kr.artel.orchestration.game.controller

import kr.artel.orchestration.common.error.NotFoundException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.game.dto.GameBuildListResponse
import kr.artel.orchestration.game.dto.GameBuildSummaryListResponse
import kr.artel.orchestration.game.dto.GameFunctionCatalogResponse
import kr.artel.orchestration.game.dto.GameBuildResponse
import kr.artel.orchestration.game.dto.DeclareGameBuildRequest
import kr.artel.orchestration.game.dto.UpdateGameBuildRequest
import kr.artel.orchestration.game.service.GameBuildService
import kr.artel.orchestration.game.service.GameFunctionCatalogService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 게임 빌드는 SDK가 보고한 버전이다. 게임을 실행하기 전에는 버전만으로 선언해 만들 수 있다(ARTEL-1447). 지우는 엔드포인트는 없다 —
 * 다음 등록에서 같은 행이 다시 생기므로 의미가 없다.
 *
 * 컨트롤러는 얇게: 상태코드 매핑(서비스가 null이면 404)만 하고, 비즈니스는 [GameBuildService].
 */
@Tag(name = "Game Build", description = "SDK가 보고한 게임 버전 조회·수정")
@RestController
@RequestMapping("/api/projects/{projectId}/game-builds")
class GameBuildController(
    private val buildService: GameBuildService,
    private val functionCatalogService: GameFunctionCatalogService
) {
    @Operation(summary = "게임 빌드 목록", description = "최근에 보고된 것이 앞에 온다.")
    @GetMapping
    suspend fun list(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long
    ): GameBuildListResponse =
        buildService.list(appUserId, projectId) ?: throw projectNotFound()

    @Operation(
        summary = "게임 빌드 요약 목록",
        description = "빌드마다 인스턴스 수와 연결 수, cheat · behavior 수, QA run 수와 가장 최근 run 을 붙인다. " +
            "순서는 빌드 목록과 같다."
    )
    @GetMapping("/summary")
    suspend fun summary(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long
    ): GameBuildSummaryListResponse =
        buildService.summaries(appUserId, projectId) ?: throw projectNotFound()

    @Operation(
        summary = "게임 빌드 선언",
        description = "Unity Player Settings 의 버전으로 development build 를 찾고, 없으면 만든다. 만들었으면 201, 이미 있으면 " +
            "200 이다. 나중에 SDK 가 같은 버전으로 등록하면 이 빌드에 붙는다."
    )
    @PostMapping
    suspend fun declare(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Valid @RequestBody request: DeclareGameBuildRequest
    ): ResponseEntity<GameBuildResponse> {
        val declared = buildService.declare(appUserId, projectId, request.version) ?: throw projectNotFound()
        return ResponseEntity.status(if (declared.created) HttpStatus.CREATED else HttpStatus.OK).body(declared.build)
    }

    @Operation(
        summary = "게임 빌드 설명 수정",
        description = "label과 notes만 바꿀 수 있다. version은 SDK가 관찰한 값이라 수정 대상이 아니다."
    )
    @PatchMapping("/{buildId}")
    suspend fun update(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "게임 빌드 id", required = true) @PathVariable buildId: Long,
        @Valid @RequestBody request: UpdateGameBuildRequest
    ): GameBuildResponse =
        buildService.update(appUserId, projectId, buildId, request) ?: throw buildNotFound()

    @Operation(
        summary = "빌드가 등록한 cheat · behavior 목록",
        description = "SDK 가 마지막으로 보낸 `GAME_FUNCTION_CATALOG` 의 내용이다. 목록을 보낸 적 없는 빌드는 404 가 아니라 " +
            "revision 0, receivedAt null, 빈 목록 둘로 답한다."
    )
    @GetMapping("/{buildId}/game-functions")
    suspend fun gameFunctions(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "게임 빌드 id", required = true) @PathVariable buildId: Long
    ): GameFunctionCatalogResponse =
        functionCatalogService.catalogOf(appUserId, projectId, buildId) ?: throw buildNotFound()

    private fun projectNotFound() =
        NotFoundException("프로젝트를 찾을 수 없습니다.")

    private fun buildNotFound() =
        NotFoundException("게임 빌드를 찾을 수 없습니다.")
}
