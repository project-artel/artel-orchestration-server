# 2026-08-11 — 컨트롤러에 흩어진 requireUser를 인자 리졸버로 통합

- Date: 2026-08-11
- Jira: ARTEL-312
- Status: Reviewed (self-review, fast/medium/heavy roles — 서브에이전트 미사용)

## Goal

인증된 요청의 `appUserId`를 컨트롤러가 **파라미터로 받는다**. JWT를 사용자 id로 바꾸는
규칙은 리졸버 한 곳에만 존재한다.

현재는 14개 컨트롤러가 아래 세 조각을 각각 들고 있다(호출 68회).

1. 생성자에 `SessionUserResolver` 주입
2. 메서드마다 `@AuthenticationPrincipal jwt: Jwt` 파라미터
3. 파일 끝에 동일한 `private fun requireUser(jwt: Jwt): Long`

WebFlux 어노테이션 컨트롤러도 MVC와 같은 `HandlerMethodArgumentResolver` 확장점을 갖는다
(`org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver`,
`WebFluxConfigurer.configureArgumentResolvers`로 등록). 세 조각을 그 확장점 하나로 접는다.

## Non-goals

- 컨트롤러마다 중복된 `parseId` 정리 — 같은 종류의 중복이지만 별개 결정이다.
- `/internal/` 라우트, WebSocket(`/ws/sdk`), SDK 체인(`/api/sdk/**`) 변경.
- `Jwt?`(nullable)로 받아 미인증을 정상 분기로 다루는 컨트롤러
  (`AuthController`, `SdkAuthController`, `SdkProjectController`, `QaCaptureController`,
  `SdkRegistrationController`) — 이들은 "없으면 401"이 아니라 "없으면 다른 응답"이라
  의미가 다르다.
- 인증·인가 정책 자체의 변경.
- `.worktrees/` 하위 사본.

## Context / Constraints

- 미인증 요청은 `SecurityConfig`의 `anyExchange().authenticated()`가 컨트롤러 이전에
  401로 끊는다. 따라서 `requireUser`가 실제로 던지는 경우는 **서명은 유효하지만 `sub`가
  사용자 id 형식이 아닌 토큰**뿐이다(`github:42` 같은 구형 토큰). 이 401 경로를 유지한다.
- `SessionUserResolver`의 토큰 해석 규칙은 그대로 둔다. 호출 지점만 옮긴다.
- 오류는 `common/error`의 타입 예외(`UnauthorizedException`)로 던진다.
- Kotlin non-null `Long`은 JVM 원시 `long`으로 컴파일된다. `supportsParameter`에서
  `Long::class.java`와 비교하면 매치되지 않으므로 **타입 비교를 하지 않는다**.
- springdoc이 `/v3/api-docs`를 만들 때 커스텀 파라미터를 쿼리 파라미터로 문서화하면
  Insomnia 컬렉션까지 오염된다. `@AuthenticationPrincipal`은 springdoc이 기본으로
  무시하지만 새 어노테이션은 무시 목록에 없다 — 명시적으로 등록해야 한다.
- 앱은 `HttpHandler` 체인을 둘 조립하지만(`InternalApiConfig`) `DispatcherHandler`를
  공유하므로 리졸버는 한 번만 등록하면 두 포트에 모두 적용된다.

## Approach (Checklist)

- [x] **Step 0: Recon**
  - 대상 14개 파일과 호출 68건 확인.
  - `SecurityConfig`가 두 체인(SDK/브라우저)을 나누고 둘 다 `JwtAuthenticationToken`을
    만든다는 것 확인. principal은 `Jwt`.
  - 기존 테스트가 이미 401/404 경로를 덮는지 확인
    (`IssueHttpIntegrationTest`가 세션 없는 요청 401을 검증).

- [ ] **Step 1: Implementation**
  - `auth/web/CurrentUserId.kt` — 파라미터 어노테이션.
  - `auth/web/CurrentUserIdArgumentResolver.kt` — `SessionUserResolver`에 위임하고
    실패 시 `UnauthorizedException`.
  - `config/WebFluxArgumentResolverConfig.kt` — `WebFluxConfigurer`로 등록.
  - `config/OpenApiConfig.kt` — springdoc 무시 목록에 `CurrentUserId` 추가.
  - 컨트롤러 14개 전환: `@AuthenticationPrincipal jwt: Jwt` → `@CurrentUserId appUserId: Long`,
    `requireUser(jwt)` → `appUserId`, `private fun requireUser` 제거, 쓰이지 않게 된
    `SessionUserResolver` 주입 제거, 죽은 import 제거.

- [ ] **Step 2: Tests**
  - `auth/web/CurrentUserIdArgumentResolverIntegrationTest` — 유효 세션은 id를 받고,
    `sub`가 사용자 id 형식이 아니면 401, 세션이 없으면 401.
  - `OpenApiDocumentationIntegrationTest`에 `appUserId`가 쿼리 파라미터로 새지 않는지
    검증 추가.
  - 기존 HTTP 통합 테스트(이슈/프로젝트/문서/테스트케이스)로 회귀 확인.

- [ ] **Step 3: Rollout / Rollback**
  - 순수 리팩터링이라 플래그·마이그레이션 없음. 되돌리려면 커밋 revert.

## Validation

- **Commands to run:**
  - `./mvnw -q -Dtest='CurrentUserIdArgumentResolverIntegrationTest' test`
  - `./mvnw -q -Dtest='IssueHttpIntegrationTest,OpenApiDocumentationIntegrationTest' test`
  - `./mvnw test` (전체 회귀)
- **Expected output:** 전부 통과. 특히 세션 없는 요청은 여전히 401, 남의 프로젝트 접근은
  여전히 404.

## Risks & Rollback

- **Risks:**
  - **컴파일은 통과하지만 인증이 빠지는 실수.** 파라미터를 지우면서 `requireUser` 호출을
    다른 값으로 바꿔치면 잡히지 않는다. → 전환 후 `grep`으로 `requireUser`·
    `@AuthenticationPrincipal` 잔여를 0으로 확인하고, 각 컨트롤러의 서비스 호출 인자
    위치를 diff에서 눈으로 확인한다.
  - **springdoc 오염.** 새 어노테이션이 무시 목록에 없으면 모든 인증 엔드포인트에
    `appUserId` 필수 쿼리 파라미터가 생긴다. → 계약 테스트로 막는다.
  - **SSE/`Flow` 반환 non-suspend 핸들러.** 리졸버가 `Mono`를 반환하므로 suspend 여부와
    무관하게 동작해야 한다. → 통합 테스트에 SSE 엔드포인트를 포함한다.
- **Rollback steps:** `git revert` 한 커밋.

## Review notes

반영한 지적:

- **검증 명령의 실재 확인.** `project.md`의 Commands 표가 TODO라 명령을 추정하면 안 된다.
  `./mvnw`와 Docker(테스트컨테이너) 가용성을 먼저 확인하고 실제로 돌린 명령만 보고한다.
- **springdoc API 실재 확인.** springdoc 2.6.0의
  `SpringDocUtils.getConfig().addAnnotationsToIgnore(...)`를 쓴다.
- **파라미터 이름 고정.** 컨트롤러 파라미터명은 `appUserId`로 통일한다. 기존 코드가
  지역변수로 이미 쓰던 이름이라 diff가 작아진다.

반려한 지적:

- "리졸버와 어노테이션을 `auth/config`에 넣어 새 패키지를 만들지 말자" — `auth/config`는
  설정 클래스 자리다. 인자 리졸버는 설정이 아니라 웹 계층 부품이라 `auth/web`에 둔다.
  등록만 `config/`에서 한다(`R2dbcConfig`, `OpenApiConfig`와 같은 자리).
- "DRY를 더 밀어 `parseId`도 같이 정리하자" — 별개 결정이고 이 브랜치의 blast radius를
  키운다. Non-goal로 남긴다.

## Open Questions

- `KnowledgeStatsController`는 아직 develop에 없다(다른 브랜치 진행 중). 머지 순서에 따라
  그쪽에서 같은 전환이 한 번 더 필요하다 — PR 본문에 남긴다.
