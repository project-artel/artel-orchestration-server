package kr.artel.orchestration.sdk.controller

import kr.artel.orchestration.sdk.dto.CommandDto
import kr.artel.orchestration.sdk.dto.SdkIdRegistrationRequest
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.sdk.service.SdkIdVerificationService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

/**
 * 외부에서 sdkId를 등록하거나 특정 연결된 클라이언트로 명령을 보낼 때 사용하는 HTTP REST 컨트롤러
 */
@RestController
@RequestMapping("/api")
class OrchestrationController(
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
     * 특정 활성화된 웹소켓 클라이언트 세션에 동적으로 테스트 명령어(Command)를 푸시하는 엔드포인트
     */
    @PostMapping("/orchestration/command/{sdkId}")
    fun sendCommand(
        @PathVariable sdkId: String,
        @RequestBody command: CommandDto
    ): Mono<ResponseEntity<String>> {
        return sessionManager.sendCommand(sdkId, command)
            .map { ResponseEntity.ok("테스트 명령어 전송 완료") }
            .onErrorResume { error ->
                Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).body(error.message))
            }
    }
}
