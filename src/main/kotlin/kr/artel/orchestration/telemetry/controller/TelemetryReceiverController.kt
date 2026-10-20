package kr.artel.orchestration.telemetry.controller

import kotlinx.coroutines.reactor.awaitSingleOrNull
import kr.artel.orchestration.auth.service.UNKNOWN_CLIENT_ADDRESS
import kr.artel.orchestration.common.error.PayloadTooLargeException
import kr.artel.orchestration.telemetry.config.TelemetryProperties
import kr.artel.orchestration.telemetry.service.TelemetryReceiverLimiter
import kr.artel.orchestration.telemetry.service.TelemetryReceiverService
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

/**
 * 직접 설치한 서버가 보내는 telemetry report 를 받는다. 세션 없이 부르는 공개 경로다(`SecurityConfig`).
 *
 * `/internal` 아래에 두지 않는다. `/internal` 은 내부 포트에만 있고, 보내는 쪽은 인터넷 너머의 다른 설치다.
 * 인증이 없는 대신 세 가지로 막는다. 클라이언트 주소마다 한 시간 30번(429), 본문 256 KB(413), report 타입 검증(400).
 * 순서도 그렇다 — 본문을 읽기 전에 횟수를 센다.
 *
 * 보낸 쪽 주소는 횟수를 세는 데만 쓰고 저장하지 않는다.
 */
@RestController
@RequestMapping("/api/telemetry/reports")
class TelemetryReceiverController(
    private val receiverService: TelemetryReceiverService,
    private val limiter: TelemetryReceiverLimiter,
    private val properties: TelemetryProperties
) {
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun receive(exchange: ServerWebExchange) {
        limiter.acquire(clientAddressOf(exchange))
        receiverService.receive(readBody(exchange))
    }

    /** 그 설치가 보낸 report 를 모두 지운다. 관리 화면에서 consent 를 끄며 "보낸 데이터 지우기" 를 고르면 온다. */
    @DeleteMapping("/{installId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun forget(@PathVariable installId: String, exchange: ServerWebExchange) {
        limiter.acquire(clientAddressOf(exchange))
        receiverService.forget(installId)
    }

    /**
     * 본문을 상한까지만 읽는다. `Content-Length` 가 상한을 넘으면 읽지 않고 413 이고, 길이를 밝히지 않은 본문은 상한에
     * 닿는 순간 413 이다. `@RequestBody` 로 받으면 전역 codec 상한(2 MB)까지 메모리에 올린 뒤에야 크기를 볼 수 있다.
     */
    private suspend fun readBody(exchange: ServerWebExchange): ByteArray {
        val maxBytes = properties.receiver.maxBodyBytes
        if (exchange.request.headers.contentLength > maxBytes) throw ReportTooLargeException(maxBytes)
        val joined = try {
            DataBufferUtils.join(exchange.request.body, maxBytes).awaitSingleOrNull()
        } catch (error: DataBufferLimitException) {
            throw ReportTooLargeException(maxBytes)
        } ?: return ByteArray(0)
        return try {
            ByteArray(joined.readableByteCount()).also { joined.read(it) }
        } finally {
            DataBufferUtils.release(joined)
        }
    }

    /** `PasswordAuthController.clientAddressOf` 와 같은 규칙이다. 주소를 모르면 한 묶음으로 센다. */
    private fun clientAddressOf(exchange: ServerWebExchange): String =
        exchange.request.remoteAddress?.hostString?.takeIf { it.isNotBlank() } ?: UNKNOWN_CLIENT_ADDRESS
}

class ReportTooLargeException(maxBytes: Int) :
    PayloadTooLargeException("telemetry report 는 ${maxBytes / 1024} KB 이하여야 합니다.")
