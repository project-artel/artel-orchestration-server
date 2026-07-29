package kr.artel.orchestration.config

import kr.artel.orchestration.common.error.ApiException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.bind.support.WebExchangeBindException
import org.springframework.web.server.ResponseStatusException

/**
 * API 오류 응답을 한 형태로 맞춘다.
 *
 * `{"code": ..., "message": ..., "fields": ...}` 형태는 SecurityConfig가 401에 직접 쓰고 있던
 * 모양을 그대로 넓힌 것이다. 클라이언트가 상태 코드마다 다른 본문을 파싱하지 않아도 된다.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private val logger = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    /** 요청 DTO 검증 실패. 어느 필드가 왜 틀렸는지 함께 준다. */
    @ExceptionHandler(WebExchangeBindException::class)
    fun handleValidation(error: WebExchangeBindException): ResponseEntity<ApiErrorResponse> {
        val fields = error.fieldErrors.associate {
            it.field to (it.defaultMessage ?: "올바르지 않은 값입니다.")
        }
        return ResponseEntity.badRequest().body(
            ApiErrorResponse(
                code = "invalid_request",
                message = "요청 값을 확인해 주세요.",
                fields = fields
            )
        )
    }

    /**
     * 모든 도메인 예외의 단일 매핑. [ApiException]을 상속한 일반·특화 예외를 하나로 포괄한다
     * (예외마다 @ExceptionHandler를 추가할 필요 없음 — 새 도메인 오류는 ApiException 하위로 선언만 하면 된다).
     * 5xx(외부 장애·서버 오류)만 원인을 로그에 남긴다(4xx 클라이언트 오류는 소음이라 안 남긴다).
     */
    @ExceptionHandler(ApiException::class)
    fun handleApi(error: ApiException): ResponseEntity<ApiErrorResponse> {
        if (error.status.value() >= 500) logger.error("API 오류 [{}]", error.code, error)
        return ResponseEntity.status(error.status).body(
            ApiErrorResponse(
                code = error.code,
                message = error.message ?: "요청을 처리할 수 없습니다."
            )
        )
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleStatus(error: ResponseStatusException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(error.statusCode).body(
            ApiErrorResponse(
                code = error.statusCode.value().toErrorCode(),
                message = error.reason ?: "요청을 처리할 수 없습니다."
            )
        )

    /**
     * 예상하지 못한 오류는 내부 사정을 그대로 흘리지 않는다. 원인은 로그에만 남긴다.
     */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(error: Exception): ResponseEntity<ApiErrorResponse> {
        logger.error("처리하지 못한 오류", error)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiErrorResponse(
                code = "internal_error",
                message = "요청을 처리하지 못했습니다."
            )
        )
    }

    private fun Int.toErrorCode(): String = when (this) {
        HttpStatus.UNAUTHORIZED.value() -> "unauthorized"
        HttpStatus.FORBIDDEN.value() -> "forbidden"
        HttpStatus.NOT_FOUND.value() -> "not_found"
        HttpStatus.CONFLICT.value() -> "conflict"
        else -> "error"
    }
}

/**
 * @property fields 필드별 오류. 검증 실패가 아니면 비어 있다
 */
data class ApiErrorResponse(
    val code: String,
    val message: String,
    val fields: Map<String, String> = emptyMap()
)
