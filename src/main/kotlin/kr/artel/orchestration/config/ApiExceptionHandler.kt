package kr.artel.orchestration.config

import kr.artel.orchestration.project.service.DuplicateDocumentException
import kr.artel.orchestration.project.service.InvalidDocumentException
import kr.artel.orchestration.project.storage.DocumentStorageException
import kr.artel.orchestration.project.service.ProjectAccessDeniedException
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

    @ExceptionHandler(InvalidDocumentException::class)
    fun handleInvalidDocument(error: InvalidDocumentException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.badRequest().body(
            ApiErrorResponse(
                code = "invalid_document",
                message = error.message ?: "올바르지 않은 파일입니다."
            )
        )

    /** 같은 프로젝트에 동일 파일이 이미 있어 업로드를 막을 때. */
    @ExceptionHandler(DuplicateDocumentException::class)
    fun handleDuplicateDocument(error: DuplicateDocumentException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiErrorResponse(
                code = "duplicate_document",
                message = error.message ?: "이미 업로드된 파일입니다."
            )
        )

    /**
     * 저장소 장애·설정 누락은 요청 잘못이 아니다. 원인은 로그에 남기고, 클라이언트에게는
     * 다시 시도할 수 있는 일시적 오류로 알린다.
     */
    @ExceptionHandler(DocumentStorageException::class)
    fun handleStorage(error: DocumentStorageException): ResponseEntity<ApiErrorResponse> {
        logger.error("기획서 저장소 접근 실패", error)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
            ApiErrorResponse(
                code = "storage_unavailable",
                message = error.message ?: "기획서 저장소에 접근하지 못했습니다."
            )
        )
    }

    @ExceptionHandler(ProjectAccessDeniedException::class)
    fun handleAccessDenied(error: ProjectAccessDeniedException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ApiErrorResponse(
                code = "forbidden",
                message = error.message ?: "권한이 없습니다."
            )
        )

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
