package kr.artel.orchestration.game.controller

import kr.artel.orchestration.common.error.NotFoundException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.game.dto.GameBuildListResponse
import kr.artel.orchestration.game.dto.GameBuildResponse
import kr.artel.orchestration.game.dto.UpdateGameBuildRequest
import kr.artel.orchestration.game.service.GameBuildService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 게임 빌드는 SDK가 보고한 버전이다. 만들거나 지우는 엔드포인트는 없다.
 *
 * 생성은 SDK 등록이 대신하고, 삭제는 다음 등록에서 같은 행이 다시 생기므로 의미가 없다.
 *
 * 컨트롤러는 얇게: 상태코드 매핑(서비스가 null이면 404)만 하고, 비즈니스는 [GameBuildService].
 */
@Tag(name = "Game Build", description = "SDK가 보고한 게임 버전 조회·수정")
@RestController
@RequestMapping("/api/projects/{projectId}/game-builds")
class GameBuildController(
    private val buildService: GameBuildService
) {
    @Operation(summary = "게임 빌드 목록", description = "최근에 보고된 것이 앞에 온다.")
    @GetMapping
    suspend fun list(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long
    ): GameBuildListResponse =
        buildService.list(appUserId, projectId) ?: throw projectNotFound()

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

    private fun projectNotFound() =
        NotFoundException("프로젝트를 찾을 수 없습니다.")

    private fun buildNotFound() =
        NotFoundException("게임 빌드를 찾을 수 없습니다.")
}
