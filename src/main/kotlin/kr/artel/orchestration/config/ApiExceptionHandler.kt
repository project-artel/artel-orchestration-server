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
 *
 * **보안 규약**: 서버가 만든 오류 메시지(예외의 message)는 상태와 무관하게 클라이언트로 내보내지
 * 않는다. 내부 구조·원인(호스트·드라이버·스택 조각 등)이 4xx로도 새어나갈 수 있기 때문이다.
 * 클라이언트에는 기계 판별용 [ApiErrorResponse.code]와 상태별 **일반 문구**만 준다.
 * 구체 원인은 서버 로그에만 남긴다(클라이언트는 code로 자체 카피를 매핑한다).
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private val logger = LoggerFactory.getLogger(ApiExceptionHandler::class.java)

    /**
     * 요청 DTO 검증 실패. 어느 필드가 왜 틀렸는지(제약 조건 안내 = 요청에 대한 도메인 정보)를
     * 함께 준다 — 서버 내부가 아니라 클라이언트 요청에 대한 피드백이라 4xx 안내로 안전하다.
     */
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
     *
     * 클라이언트로 나가는 message 규약:
     * - **5xx**: 서버가 만든/감싼 원문은 **절대 내보내지 않는다**(내부 구조·원인 유출 방지) → 일반 문구.
     *   원문 message는 원인(cause)까지 error 로그로만 남긴다.
     * - **4xx**: 예외의 message(우리가 쓴 **도메인 안내**, 예: "기획서는 PDF만…")를 그대로 준다.
     *   전제 규약: 4xx 예외는 **잡은 예외의 raw message를 감싸지 않는다**(도메인 상수/입력만). 이 규약을
     *   지키는 한 4xx로는 내부 정보가 새지 않는다. 진단이 필요하면 debug 로그를 본다.
     */
    @ExceptionHandler(ApiException::class)
    fun handleApi(error: ApiException): ResponseEntity<ApiErrorResponse> {
        val serverError = error.status.value() >= 500
        if (serverError) {
            logger.error("API 오류 [{}]: {}", error.code, error.message, error)
        } else {
            logger.debug("API 오류 [{}]: {}", error.code, error.message)
        }
        val clientMessage =
            if (serverError) genericMessageFor(error.status.value())
            else error.message ?: genericMessageFor(error.status.value())
        return ResponseEntity.status(error.status).body(
            ApiErrorResponse(code = error.code, message = clientMessage)
        )
    }

    /**
     * 프레임워크가 던지는 ResponseStatusException 폴백. reason에 경로 등 내부 정보가 담길 수
     * 있어 클라이언트로 흘리지 않고, 상태별 일반 문구로 대체한다.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleStatus(error: ResponseStatusException): ResponseEntity<ApiErrorResponse> {
        if (error.statusCode.value() >= 500) logger.error("상태 오류 [{}]", error.statusCode.value(), error)
        return ResponseEntity.status(error.statusCode).body(
            ApiErrorResponse(
                code = error.statusCode.value().toErrorCode(),
                message = genericMessageFor(error.statusCode.value())
            )
        )
    }

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

    /**
     * 상태 코드만 보고 만드는 일반 문구. 서버가 만든 구체 message를 대신해 클라이언트에 나가는
     * 유일한 사람용 텍스트라, 어떤 내부 정보도 담지 않는다(구체 원인은 code와 로그로).
     */
    private fun genericMessageFor(status: Int): String = when (status) {
        HttpStatus.BAD_REQUEST.value() -> "요청 값을 확인해 주세요."
        HttpStatus.UNAUTHORIZED.value() -> "인증이 필요합니다."
        HttpStatus.FORBIDDEN.value() -> "권한이 없습니다."
        HttpStatus.NOT_FOUND.value() -> "찾을 수 없습니다."
        HttpStatus.CONFLICT.value() -> "요청이 현재 상태와 충돌합니다."
        else -> "요청을 처리할 수 없습니다."
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
