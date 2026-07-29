package kr.artel.orchestration.sdk.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import kr.artel.orchestration.sdk.dto.ActionResponseDto
import kr.artel.orchestration.sdk.service.SessionManager
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * 연결된 게임 인스턴스로 명령(액션)을 보낼 때 사용하는 HTTP REST 컨트롤러
 *
 * 인스턴스 등록은 여기가 아니라 `/api/sdk/registrations`에서 한다. 이 경로는 Agent 서버가
 * 부르고, 대상 지정에는 자격증명이 아니라 게임 인스턴스 id를 쓴다.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "SDK orchestration", description = "Agent action delivery to connected game instances")
class SdkController(
    private val sessionManager: SessionManager
) {

    /**
     * Agent 서버로부터 특정 클라이언트용 액션(Action) 목록을 수신하여 전달하는 엔드포인트
     */
    @PostMapping("/orchestration/action/{instanceId}")
    @Operation(
        summary = "Deliver agent actions to a game instance",
        description = "Forwards an agent action list to the connected game instance identified by instanceId."
    )
    suspend fun sendAction(
        @Parameter(description = "Game instance id", required = true)
        @PathVariable instanceId: String,
        @RequestBody action: ActionResponseDto
    ): ResponseEntity<String> {
        return try {
            sessionManager.sendAction(instanceId, action)
            ResponseEntity.ok("액션 명령어 전송 완료")
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(error.message)
        }
    }
}
