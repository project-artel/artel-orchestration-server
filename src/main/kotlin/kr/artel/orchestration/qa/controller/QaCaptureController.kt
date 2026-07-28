package kr.artel.orchestration.qa.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.artel.orchestration.qa.dto.QaCaptureTicketRequest
import kr.artel.orchestration.qa.dto.QaCaptureTicketResponse
import kr.artel.orchestration.qa.service.QaCaptureService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

/**
 * SDK가 화면 캡처를 올리기 전에 서명을 받아가는 지점.
 *
 * `/api/sdk/registrations`와 같은 자리에 있다. 게임을 실행하는 쪽에는 로그인 세션이 없어
 * 엔드유저 JWT로 막을 수 없고, 여기서의 권한은 "그 게임 인스턴스가 지금 QA 실행 중인가"로
 * 판단한다.
 */
@Tag(name = "SDK QA capture", description = "QA 화면 캡처 업로드 서명")
@RestController
@RequestMapping("/api/sdk/qa-captures")
class QaCaptureController(
    private val captureService: QaCaptureService
) {
    @Operation(
        summary = "QA 캡처 업로드 티켓 발급",
        description = "활성 QA try를 확인하고 업로드·다운로드 presigned URL을 발급한다. " +
            "이미지 바이트는 이 서버를 지나가지 않는다."
    )
    @PostMapping("/tickets")
    fun issueTicket(
        @Valid @RequestBody request: QaCaptureTicketRequest
    ): Mono<QaCaptureTicketResponse> = captureService.issueTicket(request)
}
