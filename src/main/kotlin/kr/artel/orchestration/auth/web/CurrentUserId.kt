package kr.artel.orchestration.auth.web

/**
 * 인증된 요청의 `app_user.id`를 컨트롤러 파라미터로 받는다.
 *
 * 토큰을 사용자 id로 바꾸는 규칙은 [CurrentUserIdArgumentResolver] 한 곳에만 있다.
 * 컨트롤러는 `Jwt`를 보지 않으므로 토큰 표현이 바뀌어도 시그니처가 흔들리지 않는다.
 *
 * 파라미터 타입은 non-null `Long`이다 — 사용자가 없으면 값이 아니라 401이다.
 * 미인증을 정상 분기로 다루는 엔드포인트(로그인 상태 조회 등)는 이것이 아니라
 * `@AuthenticationPrincipal jwt: Jwt?`를 그대로 쓴다.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentUserId
