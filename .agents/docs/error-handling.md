# Error Handling

## Why

오류는 **타입 하나**로 던지고, **advice 한 곳**이 HTTP 응답으로 매핑한다. 던지는
쪽은 "무슨 오류인지"(타입)만 알면 되고, "어떻게 응답할지"(상태코드·body·로깅)는
`ApiExceptionHandler`가 전담한다. 새 오류가 필요해도 핸들러를 건드리지 않는다 —
`ApiException` 하위로 선언만 하면 기존 배선에 자동으로 올라탄다.

## The module

`common/error/ApiException.kt` 하나가 예외 계층의 단일 소스다.

```
RuntimeException
  └─ ApiException(status, code, message, severity, cause)   // 추상 베이스
       ├─ BadRequestException          → 400  code="invalid_request"
       ├─ UnauthorizedException        → 401  code="unauthorized"
       ├─ ForbiddenException           → 403  code="forbidden"
       ├─ NotFoundException            → 404  code="not_found"
       ├─ ConflictException            → 409  code="conflict"
       └─ UpstreamUnavailableException → 503  code="upstream_unavailable" (TRANSIENT)
```

응답 body는 항상 같은 모양이다:

```json
{ "code": "not_found", "message": "찾을 수 없습니다.", "fields": {} }
```

`fields`는 DTO 검증 실패일 때만 채워진다.

> **보안 규약 — message는 클라이언트로 나가지 않는다.**
> `throw NotFoundException("프로젝트를 찾을 수 없습니다.")`처럼 예외에 실은 message는 **서버
> 로그 전용**이다(5xx는 원인까지 error, 4xx는 debug). 응답 body의 `message`는 예외 message가
> 아니라 **상태별 일반 문구**(예: 404 → "찾을 수 없습니다.")로, 어떤 내부 정보도 담지 않는다.
> 상태와 무관하게(4xx 포함) 이 규약이 적용된다 — 클라이언트는 사람용 카피를 `code`로 매핑한다.
> 따라서 message에는 사용자에게 보여줄 구체 문구가 아니라, **디버깅에 쓸 원인**을 담아도 된다.

## How to throw

### 일반 오류 — 기성 6종을 그대로 던진다

상태코드를 손으로 지정하지 않는다. 타입이 곧 상태코드다.

```kotlin
// 인증 없음
?: throw UnauthorizedException()

// 없거나 접근 불가
?: throw NotFoundException("프로젝트를 찾을 수 없습니다.")

// 요청 값이 잘못됨
throw BadRequestException("기획서는 PDF 파일만 올릴 수 있습니다.")
```

### 도메인 특화 오류 — 기성 예외를 상속해 그 도메인에 co-located

의미가 있는 code/message가 반복되면, 해당 도메인 패키지에서 6종 중 하나를 상속한다.

```kotlin
// project/service/ProjectDocumentService.kt
class DuplicateDocumentException(message: String) :
    ConflictException(message, code = "duplicate_document")

// project/service/ProjectService.kt
class ProjectAccessDeniedException(message: String) : ForbiddenException(message)

// project/storage/DocumentStorage.kt — 외부 의존 실패는 cause를 넘겨 원인을 로그에 남긴다
class DocumentStorageException(message: String, cause: Throwable) :
    UpstreamUnavailableException(message, code = "storage_unavailable", cause = cause)
```

베이스가 `ApiException`이라 advice가 자동으로 처리하고, 예외 정의는 던지는 코드
옆에 산다. **핸들러에 `@ExceptionHandler`를 추가하지 않는다.**

## Rules

- 애플리케이션 코드에서 `ResponseStatusException`을 새로 쓰지 않는다. 타입 예외를
  쓴다. (프레임워크가 던지는 `ResponseStatusException` 폴백 핸들러는 남겨두지만,
  우리 코드가 그것을 던지지는 않는다.)
- 도메인 오류마다 `@ExceptionHandler`를 새로 만들지 않는다. `ApiException` 하위로
  선언하면 단일 `handleApi`가 잡는다.
- 5xx는 원인이 자동으로 error 로그에 남는다(4xx는 소음이라 안 남긴다). 그래서
  외부 의존 실패는 `cause`를 꼭 넘긴다.
- `message`는 클라이언트로 나가지 않고 로그로만 간다(위 보안 규약). 그래도 스택/쿼리 전체를
  담기보다 원인을 짧게 적는다 — cause에 원 예외를 실으면 스택은 그쪽으로 남는다.
- 예상 못 한 오류는 던지지 말고 그냥 전파한다 — `handleUnexpected`가 500 +
  일반 메시지로 막고 원인만 로그에 남긴다.

## Coroutine caveat — CancellationException

코루틴에서 broad `catch (e: Exception)`은 `CancellationException`까지 삼켜, 요청
취소(클라이언트 끊김)가 정상 취소되지 않는다. **넓은 catch 앞에서 먼저 rethrow한다.**

```kotlin
try {
    ...
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e                       // 취소는 오류가 아니다 — 반드시 먼저 전파
} catch (e: Exception) {
    ...
}
```

`CancellationException`은 오류가 아니므로 `ApiException` 계층에 넣지 않는다.

## severity (WS/SSE)

`ApiException.severity`(FATAL/TRANSIENT)는 HTTP에서는 쓰이지 않는다(status로 충분).
WS/SSE 핸들러가 "치명적이면 연결 종료, 일시적이면 로그만 남기고 계속"을 일관되게
정할 자리로 둔 것이다. 아직 소비처가 없으니, 실제로 그 분기가 필요한 핸들러가
생길 때 연결한다 — 미리 배선하지 않는다.
