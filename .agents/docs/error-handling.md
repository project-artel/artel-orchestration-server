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
{ "code": "invalid_document", "message": "기획서는 PDF 파일만 올릴 수 있습니다.", "fields": {} }
```

`fields`는 DTO 검증 실패일 때만 채워진다.

> **보안 규약 — 서버 오류 원문은 클라이언트로 내보내지 않는다.**
> - **5xx**: 예외 message를 **절대 클라이언트로 보내지 않는다**. 내부 구조·원인(호스트·드라이버·
>   경로·스택 조각)이 새어나갈 수 있기 때문. 응답 message는 **상태별 일반 문구**("요청을 처리할 수
>   없습니다.")로 대체하고, 원문은 원인(cause)까지 error 로그로만 남긴다.
> - **4xx**: 예외의 message는 **우리가 쓴 도메인 안내**(예: "기획서는 PDF만 올릴 수 있습니다.")라
>   클라이언트로 그대로 준다 — 서버 내부가 아니라 요청에 대한 안내다.
>   ⚠️ **전제 규약: 4xx 예외는 잡은 예외의 raw message(`e.message`)를 감싸지 않는다.** 도메인
>   상수나 요청 입력만 담는다. raw를 4xx에 실으면 이 경로로 내부 정보가 샌다.
> - **프레임워크 `ResponseStatusException`**: reason에 경로 등이 담길 수 있어 신뢰하지 않는다 —
>   상태 무관 일반 문구로 대체한다(우리 코드는 이제 타입 예외를 쓰므로 이 경로는 프레임워크 전용).
>
> 클라이언트는 구체 카피를 `code`로도 매핑할 수 있다.

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
- **5xx** message는 클라이언트로 안 나가고 로그로만 간다 — 원인을 담아도 된다(cause에 원 예외를
  실으면 스택은 그쪽으로 남는다). **4xx** message는 클라이언트로 그대로 나가니 사용자용 도메인
  안내만 쓰고, 잡은 예외의 raw message(`e.message`)를 절대 넣지 않는다.
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
