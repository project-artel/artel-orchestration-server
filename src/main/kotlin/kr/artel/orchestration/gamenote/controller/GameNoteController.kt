package kr.artel.orchestration.gamenote.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.gamenote.dto.GameNotesResponse
import kr.artel.orchestration.gamenote.dto.UpdateGameNotesRequest
import kr.artel.orchestration.gamenote.service.GameNoteService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 프로젝트별 게임 노트. 참여자가 아니면 둘 다 404 다. */
@Tag(name = "Game notes", description = "planner 에게 주는 프로젝트별 게임 노트와 genre module")
@RestController
@RequestMapping("/api/projects/{projectId}/game-notes")
class GameNoteController(
    private val gameNoteService: GameNoteService
) {
    @Operation(
        summary = "게임 노트 최신 버전",
        description = "저장한 적이 없으면 `version: 0`, 빈 `body` 와 `genres`, null `updatedAt` · `updatedBy` 다."
    )
    @GetMapping
    suspend fun getGameNotes(
        @CurrentUserId userId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long
    ): GameNotesResponse = gameNoteService.get(userId, projectId)

    @Operation(
        summary = "게임 노트 저장",
        description = "새 버전을 만들어 돌려준다. 본문과 장르 집합이 최신과 같으면 버전을 올리지 않는다. " +
            "`baseVersion` 이 최신이 아니면 409 `game_notes_conflict`, 본문이 20000 자를 넘거나 장르가 틀리면 400 이다."
    )
    @PutMapping
    suspend fun putGameNotes(
        @CurrentUserId userId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Valid @RequestBody request: UpdateGameNotesRequest
    ): GameNotesResponse = gameNoteService.save(userId, projectId, request)
}
