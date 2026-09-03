# 2026-09-03 — cli_token 테이블과 발급·목록·폐기 API 를 만든다

- Date: 2026-09-03
- Jira: ARTEL-780
- Status: Done

## Goal

폐기할 수 있는 자격증명을 만든다. 지금 이 서버가 내는 토큰은 전부 상태 없는 JWT 라 서명과 만료
말고는 아무 근거가 없고, 발급한 뒤에는 만료를 기다리는 것 외에 회수할 방법이 없다. `cli_token`
테이블을 두고, 발급·목록·폐기 세 엔드포인트를 열고, `artel_` 로 시작하는 bearer 를 그 테이블로
해석한다.

`RefreshTokenService.kt:31` 의 `ponytail:` 주석이 이 상태를 이미 적어 두었다 — "상태가 없어 개별
폐기가 불가능하다. 만료 전 로그아웃/탈취 회수가 필요해지면 저장소(테이블·Redis)에 jti 를 남기고
폐기 목록을 두는 쪽으로 올린다." 이번에 올리는 것은 refresh 토큰이 아니라 CLI 토큰이지만, 그
주석이 가리킨 방향(상태를 테이블에 둔다)이 이 작업이다.

## Non-goals

- refresh 토큰과 SDK 토큰을 상태 있는 토큰으로 바꾸는 것. `RefreshTokenService` 와 `JwtService`
  는 손대지 않는다. `ponytail:` 주석도 그대로 둔다 — 그 문장이 가리키는 대상은 여전히 refresh
  토큰이고, 이 작업이 그것을 해결하지 않는다
- `scope` 를 읽는 것. 컬럼은 만들지만 이번에 아무 코드도 읽지 않는다
- 토큰 회전, 만료 임박 알림, 사용 이력 테이블
- 콘솔 화면(ARTEL-781, home)과 CLI(ARTEL-782, artel-cli). 90일 기본값도 그 둘에 있고 이 API 에는
  없다
- `/api/sdk/**` 를 CLI 토큰에 여는 것. SDK 체인은 지금 그대로 `aud=artel-sdk` JWT 만 받는다

## Context / Constraints

### 마이그레이션 번호는 V74 가 아니라 V89 이다

이슈가 못박은 파일명 `V74__create_cli_token.sql` 은 쓸 수 없다. `V74` 는 이미
`src/main/resources/db/migration/V74__point_test_case_at_its_capability.sql` 이 가져갔고 `develop`
에 머지돼 있다.

`develop` 의 최고 번호는 `V88__invite_by_app_user.sql` 이며 `a44c5a1`(#255)로 들어왔다. 열린 PR 은
`#237`(ARTEL-681) 하나뿐이고 마이그레이션을 더하지 않으므로, 이 브랜치는 `develop` 에서 그대로
자르고 번호는 **`V89`** 를 쓴다. 파일명은 `V89__create_cli_token.sql` 이다.

`V86` 과 `V87` 은 비어 있지만 그 자리를 쓰지 않는다. `V88` 이 이미 적용된 DB 에 더 낮은 번호를
넣으면 순서가 어긋나고, 아래에 적은 그대로 `check-flyway-migrations.sh` 가 걸러낸다.

이 계획을 처음 쓸 때 읽은 checkout 에서는 최고 번호가 `V85` 였고 그래서 한때 `V86` 으로 적혀
있었다. 작성 중에 `#255` 가 머지되면서 기준이 움직였다. 브랜치를 자르기 직전에 번호를 다시
확인한다 — 이 값은 계획을 쓴 시점이 아니라 브랜치를 자른 시점의 `develop` 이 정한다.

번호를 낮춰 끼워 넣는 선택지는 없다. `develop` 에 이미 적용된 번호보다 낮은 마이그레이션은
`scripts/check-flyway-migrations.sh` 가 exit 1 로 떨어뜨리고, 운영 DB 는 `V74` 를 다른 checksum
으로 이미 기록해 두었다. 이슈의 `V74` 는 계약이 아니라 착오다(Open Questions 참조).

### 자격증명 종류는 접두사가 정한다

`artel_` 로 시작하는 bearer 는 `cli_token` 조회로 가고, 그 밖의 bearer 는 지금처럼 JWT 로
해석한다. "JWT 로 디코드해 보고 실패하면 넘어간다"는 방식은 접는다 — 그러면 만료된 세션 JWT 도
디코드에 실패하므로 그 토큰까지 DB 조회로 흘러가 매 요청에 질의가 하나씩 붙고, 401 의 원인이
"서명이 틀렸다"인지 "테이블에 없다"인지도 구분되지 않는다.

접두사 판단은 한 곳에만 둔다. 새 파일 `auth/cli/CliTokenBearer.kt` 에

```kotlin
const val CLI_TOKEN_PREFIX = "artel_"
fun ServerHttpRequest.cliTokenOrNull(): String?
```

를 두고, 뒤에 나오는 두 converter 가 이 함수 하나를 반대 방향으로 쓴다.

`AuthProperties.kt:13` 의 쿠키 이름이 `artel_access_token` 이라 접두사가 겹쳐 보이지만 충돌은
없다. 쿠키는 이름으로 찾고 CLI 토큰은 `Authorization` 헤더 값의 접두사로 찾는다.

### `artel_` bearer 를 알아보는 자리 — 이 작업에서 가장 날카로운 지점

**자리:** 브라우저 체인에 `SecurityWebFiltersOrder.AUTHENTICATION` **앞으로** 끼우는
`AuthenticationWebFilter` 하나다. `SecurityConfig.securityWebFilterChain`
(`SecurityConfig.kt:129`) 안에서 `http.addFilterBefore(...)` 로 붙인다. 그 필터의 부품은 셋이다.

- converter: `cliTokenOrNull()` 이 값을 주면 `BearerTokenAuthenticationToken`, 아니면 `Mono.empty()`
- manager: 새 `CliTokenAuthenticationManager`(`ReactiveAuthenticationManager` 구현, `@Component`)
- failure handler: 기존 `jsonAuthenticationEntryPoint()`(`SecurityConfig.kt:327`)를 감싼
  `ServerAuthenticationEntryPointFailureHandler`

**필터를 `@Bean` 으로 내지 않는다.** `AuthenticationWebFilter` 는 `WebFilter` 라, 빈으로 두면
Spring WebFlux 가 security 체인 밖의 전역 `WebFilter` 목록에도 집어넣어 두 번 돈다. `SecurityConfig`
의 private 함수가 만들어 그 자리에서 `addFilterBefore` 로 넘긴다.

**기존 converter 두 개와 어떻게 겹치지 않나.** `AuthenticationWebFilter` 는 SecurityContext 가 이미
차 있는지 보지 않는다 — converter 가 값을 주면 무조건 다시 인증한다. 그래서 앞 필터가 성공해도
뒤의 리소스 서버 필터가 같은 `Authorization` 헤더를 또 집어 JWT 로 디코드하고 401 을 낸다. 막는
방법은 하나다: `cookieTokenConverter`(`SecurityConfig.kt:316-325`)가 `artel_` bearer 를 **명시적으로
거절**한다.

```kotlin
private fun cookieTokenConverter(properties: AuthProperties): ServerAuthenticationConverter {
    val headerConverter = ServerBearerTokenAuthenticationConverter()
    return ServerAuthenticationConverter { exchange ->
        // artel_ bearer 는 CLI 토큰 필터가 이미 처리했다. 여기서 걸러내지 않으면 같은 헤더를
        // JWT 로 한 번 더 해석해 401 이 된다.
        if (exchange.request.cliTokenOrNull() != null) Mono.empty()
        else headerConverter.convert(exchange).switchIfEmpty(Mono.defer { /* 쿠키, 지금 그대로 */ })
    }
}
```

쿠키로 **떨어뜨리지 않고** 곧장 `Mono.empty()` 인 것이 중요하다. `artel_` bearer 와 세션 쿠키를
함께 실은 요청에서 쿠키로 넘어가면, 앞 필터가 CLI 토큰 주인으로 인증해 둔 것을 뒤 필터가 쿠키
주인으로 덮어쓴다. 헤더에 `artel_` 이 있으면 그 요청은 CLI 토큰 요청이고, 그것으로 끝이다.

`ServerBearerTokenAuthenticationConverter` 자체는 손대지 않는다. `cookieTokenConverter` 안에서만
쓰이고(`SecurityConfig.kt:317`), 그 바깥의 SDK 체인은 리소스 서버 기본 converter 를 그대로 쓴다 —
`artel_` bearer 를 `/api/sdk/**` 에 내밀면 `sdkJwtDecoder` 가 `JwtException` 으로 떨어뜨려 401 이다.
"SDK 토큰은 `/api/sdk/**` 만 연다"의 반대 방향(CLI 토큰은 `/api/sdk/**` 를 못 연다)도 코드 추가
없이 성립한다.

`trackerSetupSecurityWebFilterChain`(`SecurityConfig.kt:99`)도 같은 `cookieTokenConverter` 를
부르므로 함께 바뀐다. 그 경로는 GitHub 이 되돌린 브라우저 redirect 라 `Authorization` 헤더가 아예
없어 동작 차이가 없다.

### principal 은 `Jwt` 다 — 새 타입을 만들지 않는다

이것이 이 이슈의 핵심 결정이다. `CurrentUserIdArgumentResolver.kt:61` 이
`context.authentication?.principal as? Jwt` 로 캐스팅하고, 실패하면 401 을 던진다. `@CurrentUserId`
는 25 개 파일이 쓰고, `@AuthenticationPrincipal Jwt` 는 컨트롤러 8 개(`AuthController.kt:83,95,117,142,153`,
`KnowledgeGraphViewController.kt:41`, `KnowledgeDetailController.kt:36`, `KnowledgeStatsController.kt:48`,
`QaCaptureController.kt:39`, `SdkRegistrationController.kt:41`, `SdkProjectController.kt:35`,
`SdkAuthController.kt:50`)가 쓴다.

그래서 `CliTokenAuthenticationManager` 는 **`Jwt` 를 직접 만들어** `JwtAuthenticationToken` 에
담는다. `Jwt` 는 서명 검증의 산물이 아니라 값 객체일 뿐이고, `SessionUserResolver.kt:216` 이
읽는 것은 `jwt.subject` 하나다. `subject` 에 `app_user.id` 를 넣으면 두 resolver 가 그대로 통한다.

```kotlin
// auth/cli/CliTokenPrincipal.kt
fun cliTokenPrincipal(row: CliTokenEntity): Jwt =
    Jwt.withTokenValue("cli-token:${row.id}")   // 원문이 아니다 — 아래 참조
        .header("typ", "cli-token")             // headers 가 비면 Jwt 생성자가 거절한다
        .subject(row.appUserId.toString())
        .claim(CREDENTIAL_CLAIM, CREDENTIAL_CLI)
        .issuedAt(row.createdAt)
        .also { builder -> row.expiresAt?.let(builder::expiresAt) }
        .build()
```

`tokenValue` 에 토큰 원문을 넣지 않는다. principal 은 로그, 오류 속성, `@AuthenticationPrincipal`
덤프를 타고 어디로든 나가는 값이라, 원문을 실으면 "원문은 저장하지 않는다"를 지켜 놓고 그것을
로그에 흘리는 꼴이 된다. `cli-token:{id}` 는 그 자체로는 아무것도 열지 못하는 손잡이다.

이 결정의 대가를 적어 둔다: 서명된 JWT 가 아닌 것이 `Jwt` 타입으로 흐른다. 대신 얻는 것은
컨트롤러 33 곳을 건드리지 않는 것이다. 다른 두 가지는 접는다.

- **새 principal 타입(`CliTokenPrincipal`)을 두고 `CurrentUserIdArgumentResolver` 를 넓힌다** — 접는다.
  resolver 한 곳만 고치면 `@CurrentUserId` 25 곳은 살지만 `@AuthenticationPrincipal Jwt` 8 개
  컨트롤러가 죽는다. nullable 로 받는 자리는 401, non-null 로 받는 자리는 NPE 500 이다. `/api/auth/me`
  가 그중 하나라 CLI 가 "내가 누구인지"조차 물을 수 없게 된다
- **`jwtDecoder` 빈을 합성 decoder 로 감싸 `artel_` 이면 DB 를 읽게 한다** — 접는다. 필터 순서 문제가
  없어 코드는 가장 짧지만, `@Primary` 인 그 빈(`SecurityConfig.kt:219`)을 tracker 체인
  (`SecurityConfig.kt:114`)도 `it.jwt { }` 로 집어 가므로 CLI 토큰이 GitHub 설치 복귀 경로까지
  자동으로 열린다. 자격증명이 여는 범위를 체인이 아니라 빈 주입이 정하게 되는 배치다

### 별도 `SecurityWebFilterChain` 이 아니라 브라우저 체인이다

체인은 `securityMatcher` 로 **경로**를 갈라 고른다(`SecurityConfig.kt:74`, `105`). CLI 토큰이 여는
것은 경로 집합이 아니라 자격증명 종류이고, 여는 경로는 브라우저 세션과 같다 —
`/api/projects/**`, `/api/qa-runs/**`, `/api/auth/me` 전부다. 별도 체인을 두려면 그 경로 목록을
전부 옮겨 적거나 브라우저에서 빼앗아 와야 하고, 둘 중 무엇을 해도 `authorizeExchange` 규칙
(`SecurityConfig.kt:147-178`)이 두 벌로 갈라져 어긋난다. 그래서 체인은 하나, 필터만 하나 더다.

SDK 체인은 반대다. 그쪽은 audience 로 **경로를 좁히는** 것이 목적이라 체인이 맞고, 그 배치는
그대로 둔다.

### 발급·목록·폐기의 자격증명

세 엔드포인트는 `/api/auth/**` 아래라 `SecurityConfig.kt:170` 의 `.authenticated()` 가 이미
덮는다. 규칙 추가가 없다.

여기에 하나를 더 건다. **`POST /api/auth/cli-tokens` 는 CLI 토큰으로는 부를 수 없다(403).**
CLI 토큰이 CLI 토큰을 찍어낼 수 있으면 폐기가 의미를 잃는다 — 새어나간 토큰 하나로 새 토큰을
만들어 두면 원본을 폐기해도 접근이 남는다. 판단은 principal 의 `cred` claim 하나로 한다
(`jwt.getClaimAsString(CREDENTIAL_CLAIM) == CREDENTIAL_CLI`). 세션 JWT 에는 이 claim 이 없다.

목록과 폐기는 CLI 토큰으로도 연다. 노트북 토큰이 샌 것을 알아챈 사람이 손에 쥔 것이 CLI 뿐일 수
있고, 폐기를 막으면 그 사람이 회수할 길이 없다. 목록은 원문을 내지 않으므로 잃을 것이 없다.

이 제약은 못박힌 계약에 없다. Open Questions 에 남긴다.

### 토큰 만들기와 해시는 `EmailVerificationService` 를 그대로 따른다

계약(`artel_` + 32 바이트의 base64url, 전체 문자열의 SHA-256)은
`EmailVerificationService.kt:156-166` 이 이미 쓰고 있는 모양과 같다 — `TOKEN_BYTES = 32`
(`:30`), `Base64.getUrlEncoder().withoutPadding()`, SHA-256 을 hex 64자로. 컬럼도
`V85__create_email_verification.sql:52` 의 `token_hash CHAR(64) NOT NULL` 과 unique index 를 그대로
따른다. 토큰 문자열은 `artel_` 6자 + base64url 43자 = 49자다.

해시하는 대상은 접두사를 포함한 **전체 문자열**이다. 계약이 그렇게 못박았고, 그래야 조회 키가
사용자가 붙여 넣는 값과 정확히 같아 서버가 문자열을 자를 일이 없다.

## Approach (Checklist)

- [x] **Step 0: Recon** — `SecurityConfig.kt`, `AuthProperties.kt`, `AuthCookies.kt`,
      `JwtService.kt`, `RefreshTokenService.kt`, `SessionUserResolver.kt`, `CurrentUserId.kt`,
      `CurrentUserIdArgumentResolver.kt`, `AuthController.kt`, `auth/sdk/*`,
      `EmailVerificationEntity.kt` + `EmailVerificationRepository.kt` + `EmailVerificationService.kt`,
      `V85__create_email_verification.sql`, `WebFluxArgumentResolverConfig.kt`,
      `common/error/ApiException.kt` 를 읽었다. `develop` 의 마이그레이션 최고 번호(`V88`)와 열린
      PR(#237, 마이그레이션 없음)을 확인했다

- [x] **Step 1: 테이블**
  - `src/main/resources/db/migration/V89__create_cli_token.sql`

    ```sql
    CREATE TABLE IF NOT EXISTS cli_token (
        id BIGSERIAL PRIMARY KEY,
        app_user_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
        name VARCHAR(100) NOT NULL,
        token_hash CHAR(64) NOT NULL,
        scope VARCHAR(64) NOT NULL DEFAULT 'full',
        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
        last_used_at TIMESTAMP WITH TIME ZONE,
        expires_at TIMESTAMP WITH TIME ZONE,
        revoked_at TIMESTAMP WITH TIME ZONE
    );
    CREATE UNIQUE INDEX IF NOT EXISTS uk_cli_token_token_hash ON cli_token (token_hash);
    CREATE INDEX IF NOT EXISTS idx_cli_token_owner ON cli_token (app_user_id, created_at DESC);
    ```

  - `expires_at` 은 nullable 이다. 계약이 "만료 없음"을 허용하고 그 상태의 표현이 NULL 이다
  - `scope` 는 `NOT NULL DEFAULT 'full'` 이고 서비스가 `'full'` 을 써넣는다. 이번에 아무도 읽지
    않지만 nullable 로 두지 않는 이유는, 좁은 scope 가 생기는 날 기존 행이 NULL(= 무슨 뜻인지
    아무도 모르는 값)이 아니라 `full`(= 전부 열린다)이라고 말하고 있어야 하기 때문이다
  - SQL 주석은 `V85__create_email_verification.sql` 과 같은 밀도로 한국어로 쓴다 — 특히 원문을
    저장하지 않는 이유와 `expires_at` 이 NULL 일 수 있는 이유

- [x] **Step 2: entity 와 repository**
  - `auth/entity/CliTokenEntity.kt` — `EmailVerificationEntity.kt` 와 같은 모양
    (`@Table("cli_token")`, `@Id val id: Long? = null`, 나머지는 `@Column`). R2DBC 는 연관관계를
    모르므로 사용자 참조는 `appUserId: Long` 값이다
  - `auth/repository/CliTokenRepository.kt` — `CoroutineCrudRepository<CliTokenEntity, Long>` 에
    `@Query` 넷을 얹는다. `EmailVerificationRepository` 와 같은 모양이고, `Specification` 이나
    `R2dbcEntityTemplate` 로 가지 않는다(레포 안에 선례가 없다)

    ```kotlin
    // 살아 있는 토큰 하나. 해시가 unique 라 최대 한 건이다. 없는 토큰·폐기된 토큰·만료된 토큰을
    // 하나의 결과(null)로 묶는다 — 셋을 갈라 답하면 토큰을 찍어 보는 쪽에 단서가 된다.
    @Query("""
        SELECT * FROM cli_token
        WHERE token_hash = :tokenHash
          AND revoked_at IS NULL
          AND (expires_at IS NULL OR expires_at > :now)
    """)
    suspend fun findUsableByTokenHash(tokenHash: String, now: Instant): CliTokenEntity?

    @Query("SELECT * FROM cli_token WHERE app_user_id = :appUserId ORDER BY created_at DESC, id DESC")
    fun findAllByOwner(appUserId: Long): Flow<CliTokenEntity>

    // 남의 토큰을 폐기할 수 없다. 조건을 서비스가 아니라 UPDATE 에 두어야 빠뜨렸을 때 남의 행이
    // 조용히 폐기되지 않는다 — ProjectRepository 의 KDoc 이 적어 둔 배치와 같다.
    @Modifying
    @Query("""
        UPDATE cli_token SET revoked_at = :revokedAt
        WHERE id = :id AND app_user_id = :appUserId AND revoked_at IS NULL
    """)
    suspend fun revoke(id: Long, appUserId: Long, revokedAt: Instant): Int

    // last_used_at 을 앞으로 민다. Kotlin 쪽에서도 한 번 거르지만 WHERE 를 남겨 두는 이유는,
    // 이 메서드를 나중에 다른 곳에서 부를 때도 갱신 주기가 지켜지게 하기 위해서다.
    @Modifying
    @Query("""
        UPDATE cli_token SET last_used_at = :now
        WHERE id = :id AND (last_used_at IS NULL OR last_used_at < :staleBefore)
    """)
    suspend fun touchLastUsed(id: Long, now: Instant, staleBefore: Instant): Int
    ```

  - `revoke` 가 0 을 돌려주는 경우는 셋이다: 없는 id, 남의 토큰, 이미 폐기된 토큰. 셋 다 404 로
    답한다. 계약이 "남의 토큰은 404" 라고만 했지만 셋을 가르면 어느 id 가 존재하는지를 알려 주게
    되고, `EmailVerificationRepository.kt:44-58` 의 `consume` 이 같은 이유로 이미 하나로 묶는다.
    같은 토큰을 두 번 DELETE 하면 두 번째는 404 다 — idempotent 하지 않다는 것을 KDoc 에 적는다

- [x] **Step 3: 서비스**
  - `auth/cli/CliTokenBearer.kt` — `CLI_TOKEN_PREFIX`, `ServerHttpRequest.cliTokenOrNull()`,
    `CREDENTIAL_CLAIM`, `CREDENTIAL_CLI`
  - `auth/cli/CliTokenPrincipal.kt` — `cliTokenPrincipal(row): Jwt` (위 코드). 순수 함수라 단위
    테스트가 붙는다
  - `auth/cli/CliTokenService.kt` — `@Service`, 의존은 `CliTokenRepository` 와 `Clock` 뿐이다

    ```kotlin
    suspend fun issue(userId: Long, name: String, expiresInDays: Int?): IssuedCliToken
    fun list(userId: Long): Flow<CliTokenEntity>
    suspend fun revoke(userId: Long, id: Long)          // 0 행이면 NotFoundException
    suspend fun authenticate(rawToken: String): Jwt?    // 필터가 부른다
    ```

    - `issue` 는 `newToken()` 으로 `artel_` + base64url(32바이트)를 만들고 전체 문자열의 SHA-256
      hex 를 저장한다. 원문은 `IssuedCliToken` 을 타고 201 응답으로만 나간다
    - `expiresInDays` 검증: `null` 이면 `expires_at` NULL, 값이 있으면 `1..365`. 벗어나면 400
      (`BadRequestException(code = "invalid_expires_in_days")`). 상한을 두는 이유는 3650 을 적어
      사실상 영구 토큰을 만들면서 만료가 있는 척하는 것을 막기 위해서다. 영구가 필요하면 `null`
      을 명시적으로 적어야 한다
    - `name` 은 `AuthController.normalizeNickname`(`AuthController.kt:164`)과 같은 모양으로
      trim 하고, 비었거나 100자를 넘으면 400
    - `authenticate` 는 해시로 한 건을 찾고, 없으면 null, 있으면 `touch` 뒤
      `cliTokenPrincipal(row)` 를 돌려준다
  - `auth/cli/CliTokenAuthenticationManager.kt` — `@Component`,
    `ReactiveAuthenticationManager`. `mono { }` 로 suspend 를 다리 놓고, 실패는
    `InvalidBearerTokenException` 으로 던진다(이미 `AuthenticationException` 이라 새 예외 클래스가
    필요 없고, 필터의 failure handler 가 401 로 옮긴다)

- [x] **Step 4: `last_used_at` 갱신 주기**
  - 상수 `LAST_USED_RESOLUTION: Duration = Duration.ofMinutes(5)`
  - `authenticate` 안에서 방금 읽은 행으로 먼저 거른다 —
    `row.lastUsedAt == null || row.lastUsedAt < now - LAST_USED_RESOLUTION` 일 때만
    `touchLastUsed` 를 부른다. 5분 안의 두 번째 요청은 질의를 아예 내지 않는다
  - **요청을 막는다.** `touchLastUsed` 를 `authenticate` 안에서 await 한다. 별도 scope 로 띄우면
    WebFlux 에서는 요청 context 가 취소되는 순간 쓰기가 조용히 사라질 수 있고, "마지막 사용
    시각이 갱신된다"는 테스트가 sleep 없이는 쓸 수 없게 된다. 비용은 5분에 한 번, primary key
    한 건의 UPDATE 다
  - 대가를 적어 둔다: `last_used_at` 은 5분 해상도다. "이 토큰을 마지막으로 언제 썼나"에는
    답하지만 "몇 번 썼나"에는 답하지 않는다. 화면이 그리는 값이 그 질문이므로 충분하다

- [x] **Step 5: 필터 배선**
  - `SecurityConfig.securityWebFilterChain` 에 `cliTokenAuthenticationManager` 를 파라미터로
    받고, private 함수가 만든 `AuthenticationWebFilter` 를
    `.addFilterBefore(filter, SecurityWebFiltersOrder.AUTHENTICATION)` 로 붙인다
  - 필터에 `setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance())` 를
    명시한다. 체인이 `SecurityConfig.kt:146` 에서 같은 것을 고른 이유(세션에 남은 principal 로
    요청이 통과하는 것을 막는다)가 이 필터에도 그대로 적용된다
  - `cookieTokenConverter`(`SecurityConfig.kt:316`)에 위의 거절 분기를 더한다. 이 함수가 tracker
    체인과 브라우저 체인 둘 다에 쓰이는 것을 KDoc 에 적는다
  - `sdkSecurityWebFilterChain`(`SecurityConfig.kt:67`)은 손대지 않는다

- [x] **Step 6: DTO 와 컨트롤러**
  - `auth/cli/CliTokenDtos.kt`

    ```kotlin
    data class CreateCliTokenRequest(
        @field:NotBlank @field:Size(max = 100) val name: String,
        // null 은 "만료 없음"이다. 값이 없는 것과 null 을 Jackson 이 가르지 못하는 문제는 아래.
        @field:JsonProperty(required = true) val expiresInDays: Int?
    )
    data class CreatedCliTokenResponse(
        val id: String, val name: String, val token: String,
        val createdAt: Instant, val expiresAt: Instant?
    )
    data class CliTokenResponse(
        val id: String, val name: String, val createdAt: Instant,
        val lastUsedAt: Instant?, val expiresAt: Instant?, val revokedAt: Instant?
    )
    ```

    `id` 는 문자열이다 — 레포 관례이고 64비트 정밀도가 브라우저에서 깎이지 않는다.
    `CliTokenResponse` 에는 `token` 필드가 없다. 원문이 나가는 응답은
    `CreatedCliTokenResponse` 하나뿐이라는 것이 타입으로 강제된다
  - `auth/cli/CliTokenController.kt` — `@RestController @RequestMapping("/api/auth/cli-tokens")`.
    `AuthController` 에 얹지 않는다: 그 클래스는 이미 의존 일곱을 들고 있고, CLI 토큰은 자기
    서비스와 자기 DTO 를 가진 별개 리소스다. 같은 `/api/auth` 아래 컨트롤러가 둘이어도 경로가
    겹치지 않아 문제가 없다
  - 세 핸들러 모두 `@AuthenticationPrincipal jwt: Jwt` 로 받고 `sessionUserResolver.resolve(jwt)`
    로 사용자 id 를 얻는다(`AuthController.kt:95` 와 같은 모양). `@CurrentUserId` 가 아니라 `Jwt`
    를 받는 이유는 `POST` 가 `cred` claim 을 봐야 하기 때문이다
  - `POST` 는 201, `GET` 은 200, `DELETE` 는 204(`@ResponseStatus`)

- [x] **Step 7: Tests** — 아래 `## Validation` 과 함께 본다
  - 새 파일 `src/test/kotlin/kr/artel/orchestration/auth/cli/CliTokenPrincipalTest.kt`
    (`auth/service/SessionUserResolverTest.kt` 옆 성격의 순수 단위 테스트)
    - `SessionUserResolver().resolve(cliTokenPrincipal(row))?.userId` 가 `row.appUserId` 다 —
      minted `Jwt` 가 기존 resolver 두 개를 그대로 통과한다는 것이 이 작업의 축이라 여기서 못박는다
    - `tokenValue` 에 토큰 원문이 들어 있지 않다
    - `cred` claim 이 `cli` 다
    - `expiresAt` 이 null 인 행에서도 `Jwt` 가 만들어진다
  - 새 파일 `src/test/kotlin/kr/artel/orchestration/auth/cli/CliTokenApiIntegrationTest.kt`
    (`auth/AuthRefreshIntegrationTest.kt` 와 같은 스타일 — `@ActiveProfiles("test")`,
    `@SpringBootTest(webEnvironment = RANDOM_PORT)`, `WebClient`, `@BeforeEach` 에서 FK 순서대로
    직접 비우기)
    - `발급 응답만 원문을 싣고 목록은 싣지 않는다` — 201 body 의 `token` 이 `^artel_[A-Za-z0-9_-]{43}$`
      이고, 같은 토큰의 `GET` 응답에 `token` 키가 없다
    - `저장된 것은 해시뿐이다` — `cli_token` 행의 `token_hash` 가 원문과 다르고 64자다
    - `CLI 토큰이 @CurrentUserId 경로와 @AuthenticationPrincipal 경로를 함께 연다` —
      `Authorization: Bearer <token>` 으로 `GET /api/projects`(=`@CurrentUserId`)와
      `GET /api/auth/me`(=`@AuthenticationPrincipal Jwt`)가 둘 다 발급자 id 로 답한다. 한 테스트에
      둘을 함께 두는 이유는 principal 결정이 깨지면 둘 중 하나만 깨지는 일이 실제로 가능하기 때문이다
    - `폐기하면 그 다음 요청이 401 이다` — DELETE 직후 같은 토큰으로 재요청. 캐시가 없다는 사실이
      곧 이 테스트다
    - `만료된 토큰은 401 이다` — 발급 뒤 `expires_at` 을 과거로 직접 UPDATE 한다(clock 을 흔들지
      않는다)
    - `남의 토큰을 폐기하면 404 이고 그 토큰은 계속 통한다` — 뒷절이 없으면 404 를 돌려주면서
      실제로는 폐기해 버리는 구현도 통과한다
    - `목록은 자기 토큰만 낸다`
    - `expiresInDays 가 null 이면 expiresAt 이 null 이고 그 토큰이 통한다`
    - `expiresInDays 가 0 이거나 366 이면 400 이다`
    - `CLI 토큰으로는 새 토큰을 발급할 수 없고 목록과 폐기는 된다` — 403 / 200 / 204
    - `last_used_at 은 첫 요청에 채워지고 5분 안의 두 번째 요청으로는 바뀌지 않는다`
    - `artel_ 토큰은 /api/sdk/** 를 열지 않는다` — `GET /api/sdk/projects` 가 401
    - 회귀: `세션 쿠키와 Authorization 헤더의 JWT 가 그대로 통한다`. `cookieTokenConverter` 를
      고치므로 두 방향을 함께 본다. 지금 `CurrentUserIdArgumentResolverIntegrationTest.kt:133,138`
      은 쿠키로만 요청하므로 헤더 bearer 경로는 어느 테스트도 지나지 않는다 — 그 절반을 여기서 만든다

- [x] **Step 8: 문서와 스냅샷**
  - `docs/api/openapi.json` 은 손으로 고치지 않는다. `OpenApiSnapshotTest` 가 파일을 다시 쓰므로
    `./mvnw test` 가 갱신하고 그 diff 를 함께 커밋한다

## Validation

- **Commands to run:**
  - `./scripts/check-flyway-migrations.sh` — `V89` 를 다른 브랜치가 먼저 가져갔는지
  - `./scripts/verify-flyway-upgrade.sh` — `develop` 의 마이그레이션 위에 얹어 `validate`
  - `./mvnw test -Dtest='CliTokenPrincipalTest,CliTokenApiIntegrationTest,CurrentUserIdArgumentResolverIntegrationTest,AuthRefreshIntegrationTest,InternalPathSecurityIntegrationTest,SdkRegistrationIntegrationTest,OpenApiDocumentationIntegrationTest,OpenApiSnapshotTest'`
    (Testcontainers 가 PostgreSQL 을 띄우므로 docker 필요)
- **Expected output:** 앞의 둘은 exit code `0`. 테스트는 전부 통과하고, `docs/api/openapi.json` 에
  `/api/auth/cli-tokens` 세 operation 이 생긴 diff 가 남는다. 전체 스위트는 이 변경과 무관하게 이미
  깨져 있으므로 돌리지 않는다

## Risks & Rollback

- **Risks:**
  - `cookieTokenConverter` 를 고치는 것이 이 작업의 유일한 광역 변경이다. 이 함수는 브라우저 체인과
    tracker 체인의 **모든** 요청을 지난다. 분기를 잘못 쓰면 세션 로그인 전체가 401 이 된다. 그래서
    Step 7 의 회귀 테스트(쿠키 + 헤더 bearer)가 선택이 아니다
  - `AuthenticationWebFilter` 를 `@Bean` 으로 내면 전역 `WebFilter` 목록에도 들어가 두 번 돈다.
    private 함수로 만드는 이유가 이것이고, 리뷰에서 "빈으로 빼자"는 제안이 나오면 여기를 가리킨다
  - `Jwt` 를 직접 만들어 principal 로 쓰는 것이 앞으로 "이 principal 은 서명된 토큰이다"라고
    가정하는 코드를 깨뜨릴 수 있다. 지금 그런 코드는 없다 — `SessionUserResolver` 는 `subject` 만,
    `CurrentUserIdArgumentResolver` 는 타입만 본다. `cred` claim 이 두 종류를 가르는 자리이므로,
    나중에 자격증명 종류를 봐야 하는 코드는 새 타입이 아니라 그 claim 을 본다
  - `artel_` bearer 요청마다 DB 조회가 하나 붙는다. 쿠키 세션에는 붙지 않는다. `uk_cli_token_token_hash`
    로 한 건을 찾는 조회라 비용은 낮지만, CLI 가 폴링을 돌리면 그만큼 늘어난다. 캐시는 이번에 두지
    않는다(아래)
  - 마이그레이션 번호가 `V89` 라는 것이 이슈 본문과 다르다. 리뷰에서 반드시 지적되므로 PR 본문에
    이유를 적는다
- **Rollback steps:** `git revert` 하나다. 마이그레이션은 새 테이블 하나와 index 둘이라 남아 있어도
  아무 코드가 읽지 않는다. 급하면 `UPDATE cli_token SET revoked_at = now() WHERE revoked_at IS NULL`
  로 발급된 토큰을 전부 죽이는 것만으로 이전 동작이 된다

## Decisions

2026-09-03 에 정한 것들.

- **principal 은 새 타입이 아니라 직접 만든 `Jwt` 다.** `subject` 에 `app_user.id` 를 넣으면
  `SessionUserResolver` 와 `CurrentUserIdArgumentResolver` 가 그대로 통하고, 컨트롤러 33 곳을
  건드리지 않는다
- **`artel_` 을 알아보는 자리는 브라우저 체인에 끼운 `AuthenticationWebFilter` 하나다.** 별도
  체인도, `jwtDecoder` 합성도 접었다. 이유는 `## Context / Constraints` 에 적었다
- **`cookieTokenConverter` 가 `artel_` bearer 를 거절하고, 쿠키로 넘어가지도 않는다.** 헤더에
  `artel_` 이 있으면 그 요청은 CLI 토큰 요청이다
- **폐기는 즉시 효력을 갖는다. 캐시를 두지 않기 때문이다.** 요청마다 `findUsableByTokenHash` 가
  `revoked_at IS NULL` 과 `expires_at` 을 함께 걸어 한 건을 찾는다. 캐시를 두게 되면 그것은
  token hash 를 키로 하는 TTL 캐시가 될 텐데, 그 순간 폐기는 TTL 만큼 늦게 듣는다. 폐기 시점에
  키를 지우는 것으로 메우려면 캐시가 인스턴스마다 따로가 아니어야 하므로 Redis 가 필요해진다 —
  즉 캐시를 두는 결정은 곧 Redis 를 쓰는 결정이다. 조회 하나가 문제가 될 만큼 늘어나기 전에는
  하지 않는다
- **`last_used_at` 은 5분 해상도다.** 요청마다 쓰지 않고, 방금 읽은 행의 값이 5분보다 오래됐을
  때만 UPDATE 를 낸다. 그 UPDATE 는 요청을 막는다(await)
- **`POST /api/auth/cli-tokens` 는 CLI 토큰으로 부를 수 없다(403).** 목록과 폐기는 열어 둔다
- **`revoke` 는 없는 id·남의 토큰·이미 폐기된 토큰을 404 하나로 답한다.** 두 번째 DELETE 는
  204 가 아니라 404 다
- **`expiresInDays` 상한은 365 다.** `null` 이 영구를 뜻하므로, 만료가 있는 척하는 큰 숫자를 막는다

## Open Questions

- **마이그레이션 번호.** 못박힌 계약이 `V74__create_cli_token.sql` 이라고 적었지만 `V74` 는
  `V74__point_test_case_at_its_capability.sql` 이 이미 쓰고 있고 `develop` 의 최고 번호는 `V85`
  다. 이 계획은 `V89__create_cli_token.sql` 로 간다. 파일명은 다른 레포가 참조하는 값이 아니므로
  home 과 artel-cli 에는 영향이 없다. 계약 문서를 고쳐 두는 편이 좋다
- **`POST` 를 CLI 토큰으로 막는 것이 계약에 없다.** ARTEL-782(artel-cli)가 `artel token create` 를
  CLI 토큰만으로 되게 하려 했다면 그쪽이 깨진다. CLI 는 브라우저 로그인 흐름
  (`/api/auth/sdk/codes` 와 같은 모양)을 이미 갖고 있거나 콘솔에서 첫 토큰을 받아 오므로 막힐
  일이 없다고 보지만, artel-cli 쪽 계획과 맞춰 확인이 필요하다
- **`expiresInDays` 의 "REQUIRED" 를 서버가 강제할 수 있는가 — 강제된다.** creator 파라미터에
  `@JsonProperty(required = true)` 를 두고 기본값을 없애면 Jackson 이 키 없는 요청을 파싱 단계에서
  실패시키고, WebFlux 가 그것을 400 으로 옮긴다.
  `CliTokenApiIntegrationTest.rejects a request that omits expiresInDays` 가 이것을 못박는다.
  기본값(`= null`)을 두면 jackson-module-kotlin 이 없는 키를 그 값으로 채워 `required` 에 닿지
  않으므로 두 가지가 함께 필요하다. 전역 `FAIL_ON_MISSING_CREATOR_PROPERTIES` 는 켜지 않았다
- **`scope` 의 첫 값.** `'full'` 로 정했다. home 이나 artel-cli 가 이미 다른 문자열을 전제하고
  있으면 맞춘다 — 이번에 아무도 읽지 않아 지금 바꾸는 비용이 가장 싸다

## Outcome

2026-09-03 에 구현했다. 계획과 다르게 간 곳은 둘이다.

- **마이그레이션 번호는 계획대로 `V89` 다.** 브랜치를 자른 뒤 `check-flyway-migrations.sh` 를 다시
  돌려 `V89` 가 비어 있는 것을 확인했고, `verify-flyway-upgrade.sh` 가 `develop` 의 마이그레이션
  71 개를 적용한 위에 이 브랜치의 한 개를 얹어 `validate` 까지 통과했다
- **`CreateCliTokenRequest.expiresInDays` 의 어노테이션은 `@field:JsonProperty` 가 아니라
  `@JsonProperty` 다.** use-site target 이 `field` 면 creator 파라미터가 아니라 필드에 붙어
  `required` 가 닿지 않는다. 위 Open Question 의 답이 "강제된다"로 나온 것이 이 배치 덕분이다
- **테스트 이름은 영어다.** `auth` 패키지의 이웃(`AuthRefreshIntegrationTest`,
  `CurrentUserIdArgumentResolverIntegrationTest`)이 영어로 적혀 있어 그쪽을 따랐다

`OpenApiDocumentationIntegrationTest.keeps the session-derived user id out of the contract` 는
이 브랜치에서 실패하지만 원인이 이 작업에 없다. `docs/api/openapi.json` 은 `develop`(`a44c5a1`,
#255)이 머지된 시점에 이미 `appUserId` 를 네 곳에 담고 있고, 이 브랜치의 diff 는 그 단어를 한 번도
더하지 않는다.
