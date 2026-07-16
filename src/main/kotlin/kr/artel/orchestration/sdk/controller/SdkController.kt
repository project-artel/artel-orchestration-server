package kr.artel.orchestration.sdk.controller

import kr.artel.orchestration.sdk.dto.SdkIdRegistrationRequest
import kr.artel.orchestration.sdk.dto.ActionResponseDto
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.sdk.service.SdkIdVerificationService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

/**
 * 외부에서 sdkId를 등록하거나 특정 연결된 클라이언트로 명령(액션)을 보낼 때 사용하는 HTTP REST 컨트롤러
 */
@RestController
@RequestMapping("/api")
class SdkController(
    private val sdkIdVerificationService: SdkIdVerificationService,
    private val sessionManager: SessionManager
) {

    /**
     * 외부 도구(React, 대시보드 등)에서 특정 클라이언트의 접속을 사전에 승인하기 위해 sdkId를 등록하는 엔드포인트
     */
    @PostMapping("/sdkId")
    fun registerSdkId(@RequestBody request: SdkIdRegistrationRequest): ResponseEntity<String> {
        val success = sdkIdVerificationService.registerSdkId(request.sdkId)
        return if (success) {
            ResponseEntity.ok("sdkId 등록 완료")
        } else {
            ResponseEntity.status(HttpStatus.CONFLICT).body("이미 등록된 sdkId입니다.")
        }
    }

    /**
     * Agent 서버로부터 특정 클라이언트용 액션(Action) 목록을 수신하여 전달하는 엔드포인트
     */
    @PostMapping("/orchestration/action/{sdkId}")
    fun sendAction(
        @PathVariable sdkId: String,
        @RequestBody action: ActionResponseDto
    ): Mono<ResponseEntity<String>> {
        return sessionManager.sendAction(sdkId, action)
            .map { ResponseEntity.ok("액션 명령어 전송 완료") }
            .onErrorResume { error ->
                Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body(error.message))
            }
    }
}
