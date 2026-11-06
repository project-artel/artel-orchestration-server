package kr.artel.orchestration.qa.controller

import kr.artel.orchestration.common.error.UnauthorizedException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.qa.dto.QaCaptureTicketRequest
import kr.artel.orchestration.qa.dto.QaCaptureTicketResponse
import kr.artel.orchestration.qa.service.QaCaptureService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * SDK가 화면 캡처를 올리기 전에 서명을 받아가는 지점.
 *
 * `/api/sdk/registrations`와 같은 자리에 있고, 같은 SDK 토큰으로 통과한다. 권한은 두 겹이다.
 * 토큰의 사용자가 그 인스턴스의 프로젝트 참여자여야 하고, 그 인스턴스가 지금 QA 실행 중이어야
 * 한다. 실행 중이 아니면 서비스가 409로 막는다.
 */
@Tag(name = "SDK QA capture", description = "QA 화면 캡처 업로드 서명")
@RestController
@RequestMapping("/api/sdk/qa-captures")
class QaCaptureController(
    private val captureService: QaCaptureService,
    private val sessionUserResolver: SessionUserResolver
) {
    @Operation(
        summary = "QA 캡처 업로드 티켓 발급",
        description = "활성 QA try를 확인하고 업로드·다운로드 presigned URL을 발급한다. " +
            "이미지 바이트는 이 서버를 지나가지 않는다."
    )
    @PostMapping("/tickets")
    suspend fun issueTicket(
        @AuthenticationPrincipal jwt: Jwt?,
        @Valid @RequestBody request: QaCaptureTicketRequest
    ): QaCaptureTicketResponse {
        val session = jwt?.let(sessionUserResolver::resolve)
            ?: throw UnauthorizedException()
        return captureService.issueTicket(request, session.userId)
    }
}
