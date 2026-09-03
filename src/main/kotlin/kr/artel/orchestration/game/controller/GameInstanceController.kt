package kr.artel.orchestration.game.controller

import kr.artel.orchestration.common.error.NotFoundException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.game.dto.GameInstanceListResponse
import kr.artel.orchestration.game.dto.GameInstanceResetResponse
import kr.artel.orchestration.game.dto.GameInstanceResponse
import kr.artel.orchestration.game.dto.ResetGameInstanceRequest
import kr.artel.orchestration.game.dto.UpdateGameInstanceRequest
import kr.artel.orchestration.game.service.GameInstanceResetService
import kr.artel.orchestration.game.service.GameInstanceService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 게임 인스턴스는 SDK 설치본 하나를 가리킨다.
 *
 * 생성 응답에 담긴 instanceKey를 Unity 온보딩 창에 붙여넣으면 그 실행본이 이 인스턴스로
 * 등록된다. 키는 목록에서도 계속 다시 읽을 수 있다.
 *
 * 컨트롤러는 얇게: 상태코드 매핑(서비스가 null이면 404)만 하고, 비즈니스는 [GameInstanceService].
 */
@Tag(name = "Game Instance", description = "프로젝트에 연결된 SDK 설치본 관리")
@RestController
@RequestMapping("/api/projects/{projectId}/game-instances")
class GameInstanceController(
    private val instanceService: GameInstanceService,
    private val resetService: GameInstanceResetService
) {
    // 생성 경로는 없다. 인스턴스는 SDK가 로그인 후 처음 등록할 때 생긴다.
    @Operation(summary = "게임 인스턴스 목록", description = "최근에 만든 것이 앞에 온다.")
    @GetMapping
    suspend fun list(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long
    ): GameInstanceListResponse =
        instanceService.list(appUserId, projectId) ?: throw projectNotFound()

    @Operation(
        summary = "게임 인스턴스 이름 수정",
        description = "이름만 바꿀 수 있다. 플랫폼은 SDK가 이미 그 위에서 돌고 있어 바꾸면 실제와 어긋난다."
    )
    @PatchMapping("/{instanceId}")
    suspend fun rename(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "게임 인스턴스 id", required = true) @PathVariable instanceId: Long,
        @Valid @RequestBody request: UpdateGameInstanceRequest
    ): GameInstanceResponse =
        instanceService.rename(appUserId, projectId, instanceId, request) ?: throw instanceNotFound()

    @Operation(
        summary = "게임 인스턴스 삭제",
        description = "삭제한 순간부터 그 instanceKey로는 등록도 연결도 되지 않는다."
    )
    @DeleteMapping("/{instanceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "게임 인스턴스 id", required = true) @PathVariable instanceId: Long
    ) {
        if (!instanceService.delete(appUserId, projectId, instanceId)) throw instanceNotFound()
    }

    /**
     * 붙어 있는 게임 하나를 초기화한다. QA 세션 밖에서 여는 첫 문이다(ARTEL-803) — 런을 시작하기
     * 전에 알려진 상태로 되돌리거나, 죽은 런이 이상하게 남긴 게임을 되돌리는 데 쓴다.
     *
     * 본문은 없어도 된다. 그때 `clearPlayerPrefs` 는 false 다.
     *
     * 상태코드 셋이 서로 다른 말을 한다:
     * - **404** — 인스턴스가 없거나 참여자가 아니다(존재 여부조차 알리지 않는다)
     * - **409** — 인스턴스는 있는데 붙어 있는 게임이 없다, 또는 그 인스턴스에서 QA 런(또는 시도)이
     *   진행 중이다 — 판단은 [GameInstanceResetService] 의 KDoc 에 있다
     * - **202** — 명령이 나갔다. 게임이 초기화를 끝냈다는 뜻은 아니다
     */
    @Operation(
        summary = "게임 인스턴스 초기화",
        description = "붙어 있는 게임에 reset_game 을 보낸다. clearPlayerPrefs 를 true 로 주면 " +
            "PlayerPrefs(저장소)도 함께 비운다. 붙어 있지 않으면 409, 그 인스턴스에서 QA 런이나 " +
            "시도가 진행 중이면 409.",
    )
    @PostMapping("/{instanceId}/reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun reset(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "게임 인스턴스 id", required = true) @PathVariable instanceId: Long,
        @RequestBody(required = false) request: ResetGameInstanceRequest?
    ): GameInstanceResetResponse =
        resetService.reset(appUserId, projectId, instanceId, request?.clearPlayerPrefs ?: false)
            ?: throw instanceNotFound()

    /** 참여자가 아닌 프로젝트는 존재 여부조차 알리지 않는다. */
    private fun projectNotFound() =
        NotFoundException("프로젝트를 찾을 수 없습니다.")

    private fun instanceNotFound() =
        NotFoundException("게임 인스턴스를 찾을 수 없습니다.")
}
