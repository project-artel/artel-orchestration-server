package kr.artel.orchestration.common.error

import org.springframework.http.HttpStatus

/**
 * 오류의 처리 성질 — HTTP status 매핑이 통하지 않는 WS/SSE에서 "이 예외를 어떻게 다룰지"를 분류한다.
 * - [FATAL]     : 세션/요청을 끝내야 하는 오류(WS면 연결 종료, 요청이면 실패 응답).
 * - [TRANSIENT] : 일시적 오류 — 로그만 남기고 계속(예: QA WS의 appendError 후 진행).
 *
 * HTTP 컨트롤러는 [ApiException.status]로 응답하므로 이 값을 안 봐도 되지만, WS/SSE 핸들러는
 * `if (e.severity == TRANSIENT) appendError else close` 처럼 이 분류로 종료/계속을 일관되게 정할 수 있다.
 */
enum class ErrorSeverity { FATAL, TRANSIENT }

/**
 * 서비스 전역 API 예외의 **공통 베이스**.
 *
 * 하위 예외는 HTTP status·wire code·심각도를 실어 던지기만 하면, 단일 @RestControllerAdvice
 * ([kr.artel.orchestration.config.ApiExceptionHandler])가 일관된 `ApiErrorResponse`로 매핑한다.
 * 도메인마다 @ExceptionHandler를 따로 둘 필요가 없다 — 핸들러는 이 베이스 하나만 잡으면 모든 하위를 포괄한다.
 *
 * - **일반 오류**: 아래 기성 예외([BadRequestException] 등)를 그대로 던진다.
 * - **도메인 특화 오류**: 각 도메인 패키지에서 적절한 기성 예외를 상속해 code/message만 특정한다
 *   (예: `class DuplicateDocumentException : ConflictException(code = "duplicate_document")`). 베이스가
 *   [ApiException]이라 advice가 자동으로 처리하고, 예외 정의는 그 도메인에 co-located된다.
 *
 * ⚠️ 취소(`kotlinx.coroutines.CancellationException`)는 오류가 아니므로 이 계층에 **넣지 않는다** —
 * catch에서 먼저 rethrow해야 코루틴이 정상 취소된다.
 */
abstract class ApiException(
    val status: HttpStatus,
    val code: String,
    message: String,
    val severity: ErrorSeverity = ErrorSeverity.FATAL,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** 400 — 요청 값이 잘못됨. */
open class BadRequestException(message: String, code: String = "invalid_request") :
    ApiException(HttpStatus.BAD_REQUEST, code, message)

/** 401 — 인증이 필요하거나 무효한 토큰. */
open class UnauthorizedException(message: String = "인증이 필요합니다.", code: String = "unauthorized") :
    ApiException(HttpStatus.UNAUTHORIZED, code, message)

/** 403 — 접근은 되나 그 동작에 필요한 권한이 없음. */
open class ForbiddenException(message: String = "권한이 없습니다.", code: String = "forbidden") :
    ApiException(HttpStatus.FORBIDDEN, code, message)

/** 404 — 없거나 접근 불가(존재 자체를 숨김). */
open class NotFoundException(message: String = "찾을 수 없습니다.", code: String = "not_found") :
    ApiException(HttpStatus.NOT_FOUND, code, message)

/** 409 — 현재 상태와 충돌(중복 등). */
open class ConflictException(message: String, code: String = "conflict") :
    ApiException(HttpStatus.CONFLICT, code, message)

/** 503 — 외부 의존(스토리지·Agent 등) 일시 장애. 서버 잘못이 아니라 재시도 가능한 오류(TRANSIENT). */
open class UpstreamUnavailableException(
    message: String,
    code: String = "upstream_unavailable",
    cause: Throwable? = null,
) : ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message, ErrorSeverity.TRANSIENT, cause)
