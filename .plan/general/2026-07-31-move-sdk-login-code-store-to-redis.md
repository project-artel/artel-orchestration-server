# 2026-07-31 — SDK 로그인 코드 저장소를 Redis로 이전

- Date: 2026-07-31
- Jira: ARTEL-225
- Status: Approved (fast/medium/heavy 리뷰 통과)

## Goal

`SdkLoginCodeStore`가 들고 있는 SDK 로그인 일회용 코드를 프로세스 메모리(`ConcurrentHashMap`)에서 Redis로 옮긴다. 발급(`POST /api/auth/sdk/codes`, 브라우저)과 교환(`POST /api/auth/sdk/token`, SDK)이 서로 다른 인스턴스로 가도 성립하게 만든다.

만료는 Redis 키 TTL이 강제하고, 소비는 `GETDEL` 한 번으로 원자적이다. 애플리케이션 쪽 만료 판정(`expiresAt` 비교)과 `purgeExpired()`는 없앤다.

## Non-goals

- **레플리카 증설.** 이 변경만으로 스케일아웃은 안 된다. `SessionManager`, `WebSocketQaAgentAdapter`, `TestScenarioStreamManager`, `QaLogStreamManager`, `ViewerSessionRegistry`가 전부 소켓과 함께 인스턴스에 고정된 상태로 남는다. 그쪽은 Redis 저장이 아니라 pub/sub 라우팅이 필요한 별개 문제다.
- Redis를 캐시·레이트리밋 등 다른 용도로 쓰는 일. 이번엔 키 한 종류만 쓴다.
- PKCE 정책 변경. 챌린지 방식(S256), 실패를 400 하나로 뭉개는 응답, 실패해도 코드를 소비하는 동작 전부 그대로다.
- SDK(C#) 클라이언트 변경. HTTP 계약이 그대로라 건드릴 것이 없다.
- Jenkinsfile 변경. Redis 컨테이너는 앱 배포 파이프라인이 아니라 인프라로 한 번 띄운다.

## Context / Constraints

- **논블로킹.** 스택이 WebFlux다. `spring-boot-starter-data-redis-reactive`(Lettuce)를 쓰고 `ReactiveStringRedisTemplate`으로 접근한다. 블로킹 `RedisTemplate`을 이벤트 루프에서 부르지 않는다.
- **소비는 원자적이어야 한다.** `GET` 후 `DEL`은 두 프로세스가 같은 코드를 동시에 교환하는 창을 남긴다. `ReactiveValueOperations.getAndDelete()`(Redis `GETDEL`, 6.2+)로 한 번에 끝낸다. 이미지도 그에 맞춰 6.2 이상을 쓴다.
- **코드 평문을 키로 두지 않는다.** Redis를 들여다볼 수 있는 쪽(운영자, `MONITOR`, 백업 덤프)이 유효한 로그인 코드를 그대로 읽게 된다. 키는 `artel:sdk:login-code:<base64url(SHA-256(code))>`다. 해시 함수는 이미 PKCE 검증에 쓰고 있는 것을 그대로 재사용한다.
- **`issue`/`consume`가 `suspend`가 된다.** Jira AC에 "시그니처는 바뀌지 않는다"고 적었는데 정확하지 않다. 파라미터와 반환 타입은 그대로지만 `suspend`가 붙는다. 두 호출부(`SdkAuthController.issueCode`, `exchange`)가 이미 `suspend fun`이라 호출 문장은 한 글자도 바뀌지 않는다. AC 문구는 이 플랜 확정 시 Jira에서 고친다.
- **`Clock` 주입이 필요 없어진다.** 만료를 Redis가 판정하므로 `Instant`를 다루지 않는다. 생성자에서 `Clock`을 뺀다.
- **테스트는 Docker에 의존한다.** 이미 그렇다 — `PostgresTestContainer`가 스위트 시작 전에 PostgreSQL을 띄운다. Redis도 같은 자리에 붙인다. Jira AC의 "Redis 없이도 테스트가 돈다"는 "개발자가 Redis를 미리 띄워둘 필요가 없다"는 뜻으로 읽고, Docker 요구는 기존과 동일하게 유지한다. 이 문구도 Jira에서 고친다.
- **Redis 장애는 400이 아니라 500이다.** `consume`이 `null`을 돌려주면 호출부가 "유효하지 않은 로그인 코드입니다" 400을 낸다. 연결 실패를 `null`로 삼키면 멀쩡한 코드를 든 사용자에게 코드가 틀렸다고 말하게 되고, 장애가 사용자 입력 오류로 위장된다. 그래서 Redis 예외는 잡지 않고 그대로 올려 500이 되게 둔다. `awaitSingleOrNull()`이 `null`을 주는 경우는 키가 없을 때뿐이고, 연결 실패는 예외로 온다.
- **기동은 Redis 없이도 된다.** Lettuce는 지연 연결이라 컨텍스트가 뜬다. 이것을 fail-fast로 바꾸지 않는다 — Redis에 의존하는 것은 SDK 로그인 하나뿐인데 그것 때문에 서버 전체가 못 뜨는 편이 더 나쁘다.
- **가용성이 나빠진다.** 지금은 외부 의존이 없어 프로세스만 살아 있으면 SDK 로그인이 된다. 옮기고 나면 Redis가 죽으면 SDK 로그인 전체가 죽는다. 단일 인스턴스 상태에서는 순수한 손해다. 이득은 레플리카를 늘릴 수 있게 되는 것뿐인데 그건 Non-goals다. 감수하고 진행하되 Risks에 남긴다.
- **배포 순서에 제약이 생긴다.** Redis 컨테이너가 `app-net`에 먼저 떠 있어야 이 이미지가 정상 동작한다. 인프라가 앱보다 앞선다.

## Approach (Checklist)

- [ ] **Step 0: Recon** — `auth/sdk/SdkLoginCodeStore.kt`, `auth/sdk/SdkAuthController.kt`, `auth/config/AuthProperties.kt`(`sdkLoginCodeTtl`), `support/PostgresTestContainer.kt`, `support/PostgresLauncherSessionListener.kt`, `src/test/resources/META-INF/services/…LauncherSessionListener` 확인. (완료)

- [ ] **Step 1: 의존성과 설정**
  - `pom.xml`: `spring-boot-starter-data-redis-reactive` 추가.
  - `src/main/resources/application.yml`에 아래를 그대로 넣는다. DB와 달리 `application-db.yml`로 빼지 않는다 — 그 파일은 optional import라 없으면 조용히 넘어가는데, Redis 설정이 조용히 빠지면 기본값 localhost로 붙어 원인을 알기 어려운 실패가 된다.

    ```yaml
    spring:
      data:
        redis:
          url: ${REDIS_URL:redis://localhost:6379}
    ```

    호스트/포트/비밀번호를 각각 받지 않고 URL 하나로 받는다. `spring.r2dbc.url`,
    `spring.flyway.url`이 이미 그 형식이라 설정 읽는 방식이 갈리지 않는다. 비밀번호는
    `redis://:password@host:port`로 URL 안에 실리므로 빈 문자열 기본값 문제도 사라진다.

    **경로는 쓰지 않는다.** `redis://host:6379/0`처럼 붙여도 Boot가 버린다 —
    `RedisConnectionConfiguration.parseUrl`은 scheme과 userInfo만 읽고,
    `PropertiesRedisConnectionDetails.getStandalone()`이 database를
    `spring.data.redis.database`에서만 가져온다(3.3.1 소스로 확인). `/0`은 기본값과 같아
    맞는 것처럼 보이지만 `/1`은 조용히 0번에 붙는다.

  - `.env.example`: `REDIS_URL` 한 줄.

- [ ] **Step 2: 저장소 구현** (`auth/sdk/SdkLoginCodeStore.kt`)
  - 생성자: `(redis: ReactiveStringRedisTemplate, properties: AuthProperties)`. `Clock` 제거.
  - `entries`, `Entry`, `purgeExpired()`, `expiresAt` 전부 삭제.
  - `suspend fun issue(userId: Long, codeChallenge: String): String`
    - 32바이트 난수 → base64url 코드 생성(기존 그대로).
    - `redis.opsForValue().set(keyOf(code), "$userId:$codeChallenge", properties.sdkLoginCodeTtl).awaitSingle()`.
    - 값 형식은 `<userId>:<codeChallenge>`. 첫 `:`를 경계로 삼는 근거는 **앞쪽**이다 — `userId`는 서버가 찍은 `Long`이라 `:`를 담을 수 없다. `codeChallenge`는 클라이언트가 준 값이고 형식 검증이 없어 `:`가 들어올 수 있지만, 뒤쪽을 통째로 가져오므로 원문 그대로 복원된다. 필드 둘에 JSON 직렬화를 들일 이유가 없다.
  - `suspend fun consume(code: String, codeVerifier: String): Long?`
    - `redis.opsForValue().getAndDelete(keyOf(code)).awaitSingleOrNull() ?: return null`.
    - 값을 갈라 `codeChallenge`가 `challengeOf(codeVerifier)`와 다르면 `null`. 이미 지워진 뒤라 verifier를 바꿔 재시도할 수 없다는 성질은 `GETDEL` 덕에 그대로 유지된다.
    - `userId`가 `Long`으로 파싱되지 않으면 `null`.
    - 만료 판정 없음. 키가 없으면 만료된 것이다.
    - **Redis 예외는 잡지 않는다.** `try`/`catch`로 `null`을 만들면 장애가 400 "코드가 틀렸다"로 위장된다. 그대로 올려 500이 되게 둔다.
  - `private fun keyOf(code: String) = KEY_PREFIX + sha256Base64Url(code)`, `KEY_PREFIX = "artel:sdk:login-code:"`.
  - `challengeOf`가 쓰던 SHA-256 로직을 `sha256Base64Url(String)` 하나로 모으고 PKCE 검증과 키 해시가 같이 쓴다.
  - KDoc 갱신: 인메모리 전제를 설명하던 `ponytail:` 주석을 지우고, 그 자리에 "레플리카를 실제로 늘리려면 소켓 레지스트리가 남아 있다"는 새 한계를 남긴다.

- [ ] **Step 3: 호출부**
  - `SdkAuthController`: `issue`/`consume`가 `suspend`가 되지만 두 호출부 모두 이미 `suspend fun` 안이라 코드 변경 없음. 컴파일로 확인만 한다.

- [ ] **Step 4: 테스트 인프라** (`src/test/kotlin/kr/artel/orchestration/support/`)
  - `RedisTestContainer.kt`: `GenericContainer`는 재귀 제네릭 타입이라 Kotlin에서 타입 추론이 안 된다. `PostgresTestContainer`와 같은 형태로 명시한다 — `private val container: GenericContainer<*> by lazy { GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379).also { it.start() } }`. JVM당 하나 띄우고 `REDIS_URL`을 시스템 프로퍼티로 내보낸다. `PostgresTestContainer`와 같은 구조·같은 수명(명시적 `stop()` 없음). 별도 Testcontainers 모듈은 필요 없다 — `GenericContainer`는 `org.testcontainers:postgresql`이 이미 끌어오는 코어에 있다.
  - **리스너는 기존 것을 넓힌다.** `PostgresLauncherSessionListener`를 `TestInfraLauncherSessionListener`로 이름만 바꾸고 `RedisTestContainer.startAndExportProperties()` 호출을 한 줄 더한다. `services` 파일의 등록도 그 이름으로 바꾼다.
    - 리스너를 하나 더 등록하는 쪽은 접었다. 두 리스너의 실행 순서를 `ServiceLoader`가 보장한다는 근거가 없는데, 새 리스너가 `DockerEnvironment.verify()`를 생략하려면 Postgres 리스너가 먼저 돌았다는 전제가 필요하다. 파일에 적히지 않은 순서 의존을 만들면서까지 아낄 것이 없다.
    - `DockerEnvironment.verify()`는 지금처럼 맨 앞에서 한 번만 부른다.
  - `application-test.yml`은 손대지 않는다. `application.yml`의 `${REDIS_URL:redis://localhost:6379}`가 그대로 살아 있고 컨테이너가 그 프로퍼티를 채운다 — DB가 쓰는 것과 정확히 같은 경로다.

- [ ] **Step 5: 테스트** (`src/test/kotlin/kr/artel/orchestration/auth/sdk/SdkLoginCodeStoreIntegrationTest.kt`)
  - 발급한 코드를 올바른 verifier로 교환하면 발급 대상 `userId`가 나온다.
  - verifier가 다르면 `null`이고, **같은 코드를 올바른 verifier로 다시 시도해도** `null`이다(실패해도 소비된다).
  - 존재하지 않는 코드는 `null`.
  - 같은 코드를 동시에 두 번 교환하면 정확히 한 번만 성공한다.
  - **인스턴스 분리:** 주입받은 `ReactiveStringRedisTemplate`과 `AuthProperties`로 `SdkLoginCodeStore(redis, properties)`를 **두 개 직접 생성**해(스프링 빈 하나를 공유하는 것이 아니라) 하나가 발급하고 다른 하나가 교환해도 성공한다. `SdkLoginCodeStore`는 상태 없는 생성자 두 인자짜리 컴포넌트라 `new` 두 번이면 인스턴스 두 개가 된다. 이 이슈의 본래 목적을 직접 검증하는 항목이다.
  - **TTL:** 발급 직후 `artel:sdk:login-code:*` 키가 정확히 하나 있고 그 `getExpire()`가 0보다 크며 `sdkLoginCodeTtl` 이하다. 실제 만료를 기다리지 않는다(`testing.md`가 sleep을 금한다).
  - `@BeforeEach`에서 접두사 키를 지운다. "테스트 시작 전에 비운다"는 관례로 두면 앞 테스트가 예외로 끊겼을 때 다음 테스트가 남은 키를 본다.

- [ ] **Step 6: 배포 문서** (`docs/deployment.md`)
  - 이 문서는 `.env`와 Jenkins Secret file 취급법만 다룬다. 인프라 런북으로 키우지 않는다. 새 절을 만들지 말고 문서 끝의 기존 `## Prerequisites`에 항목을 더한다:
    - `app-net`에 Redis(6.2 이상, `redis:7-alpine` 기준)가 떠 있어야 한다는 것과 그 `docker run` 한 줄.
    - **이미 등록된 `artel-orchestration-server-env-stage` / `-operation` Secret file을 `REDIS_URL=redis://artel-redis:6379`을 넣어 다시 업로드해야 한다.** 이게 이 이슈에서 빠뜨리면 운영이 깨지는 유일한 단계다. 문서의 "Registering a Secret file" 절차는 **새 환경을 등록할 때**만 도는 흐름이라, `.env.example`에 항목을 더하는 것만으로는 이미 올라가 있는 두 파일이 갱신되지 않는다. 갱신하지 않으면 `REDIS_URL`이 기본값 `redis://localhost:6379`로 떨어지는데 컨테이너 안에서 그것은 앱 자신이라, 기동은 정상이고 SDK 로그인만 전부 500이 된다.
    - **순서:** Redis 기동 → Secret file 재업로드 → 이 이미지 배포. Lettuce가 지연 연결이라 앞의 둘을 건너뛰어도 기동은 되고 SDK 로그인만 조용히 실패하므로, 순서를 어기면 부분 장애로 늦게 발견된다.
    - 영속화는 켜지 않는다. 담기는 것이 수명 5분짜리 일회용 코드뿐이라 재시작 시 사라져도 사용자가 로그인을 다시 누르면 끝이고, 디스크에 남기면 오히려 유효 코드 해시가 백업으로 새어나간다.
  - `REDIS_*`의 값 설명 자체는 `.env.example`에 두고 이 문서에 중복해서 적지 않는다.

- [ ] **Step 7: Rollout / Rollback** — 플래그 없음, 마이그레이션 없음. 되돌림은 `git revert` 하나. 저장 상태가 앱 밖에 있는 5분짜리 임시 데이터라 되돌려도 정리할 잔재가 없다.

## Validation

- **Commands to run:**
  - `./mvnw test -Dtest=SdkLoginCodeStoreIntegrationTest -DfailIfNoSpecifiedTests=false`
  - `./mvnw test` (전체 회귀. Redis 컨테이너가 모든 `@SpringBootTest` 컨텍스트에 붙으므로 인증 외 스위트도 영향을 받는지 확인해야 한다.)
  - `-o`(오프라인)를 붙이지 않는다. `spring-boot-starter-data-redis-reactive`와 Lettuce가 로컬 `~/.m2`에 없어서 오프라인 해석이 테스트 시작 전에 실패한다.
- **Expected output:** 신규 테스트 전부 통과, 기존 auth 스위트(`AuthLocaleIntegrationTest`, `OAuthUserServiceIntegrationTest`, `JwtServiceTest`, `SessionUserResolverTest`, `GitHubOAuthIdentityMapperTest`, `GameInstanceWebSocketAuthIntegrationTest`) 회귀 없음, `BUILD SUCCESS`.
- **실행 결과:**
  - `SdkLoginCodeStoreIntegrationTest` → `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`
  - 전체 → `Tests run: 233, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`
  - **막힌 것 하나:** `origin/develop`(00481fb)이 애초에 컴파일되지 않았다. `GameInstanceController.kt`가 `CreateGameInstanceRequest`를 import하는데 그 타입이 `bfe0de4`(ARTEL-219)에서 사라졌고 import만 남아 있었다. 파일 안에 그 심볼을 쓰는 곳도 없다. 검증을 하려면 지울 수밖에 없어 죽은 import 한 줄을 별도 커밋으로 제거했다. 이 이슈와는 무관하다.
  - 이 머신에서는 `DOCKER_HOST=unix://~/.colima/default/docker.sock`과 `TESTCONTAINERS_RYUK_DISABLED=true`가 있어야 스위트가 시작된다(colima). `DockerEnvironment`가 안내하는 그대로다.
- **수동 확인:** 로컬에서 `docker run --rm -p 6379:6379 redis:7-alpine` 띄우고 앱 기동 → 홈에서 SDK 로그인 → `redis-cli --scan --pattern 'artel:sdk:login-code:*'`로 발급 시점에 키가 생기고 교환 직후 사라지는지 본다.

## Risks & Rollback

- **Risks:**
  - **가용성 하락.** Redis가 죽으면 SDK 로그인이 전부 실패한다. 지금은 외부 의존이 없어 앱만 살아 있으면 됐다. 단일 인스턴스 배포에서는 이 변경이 순손해이며, 이득(레플리카 가능)은 소켓 레지스트리를 옮기기 전까지 실현되지 않는다. 감수한다.
  - **배포 순서 의존.** Redis 없이 새 이미지를 올리면 기동은 되지만(Lettuce는 지연 연결) SDK 로그인만 조용히 실패한다. 기동 실패가 아니라 부분 실패라 눈에 늦게 띈다. 배포 문서에 순서를 못 박는 것 외에 방어를 두지 않는다.
  - **`GETDEL` 버전 요구.** Redis 6.2 미만이면 `getAndDelete()`가 실패한다. 이미지를 `redis:7-alpine`으로 고정하고 배포 문서에 최소 버전을 적는다.
  - **테스트 시간·자원 증가.** 스위트가 컨테이너를 하나 더 띄운다. Redis는 가볍고 JVM당 한 번이라 영향은 작지만 0은 아니다.
  - **인증 없는 Redis.** `REDIS_URL`에 비밀번호를 빼고 `app-net` 안에서만 접근 가능하다는 전제에 기댄다. 같은 네트워크의 다른 컨테이너는 유효 코드 해시를 볼 수 있으나, 코드 원문이 아니라 해시라 그것만으로 교환은 불가능하다. 그래도 배포 시 비밀번호를 거는 쪽을 권장으로 적는다.
- **Rollback steps:** `git revert`. 인메모리로 돌아가고 남는 Redis 키는 TTL로 5분 안에 자연히 사라진다. Redis 컨테이너는 다음 이슈에서 쓸 것이므로 내리지 않아도 무해하다.

## Rejected feedback

- **기동 시 Redis 버전 프로브를 넣어 `GETDEL` 지원을 확인하자(fast #6).** 접는다. 배포 한 번의 실수를 잡으려고 런타임 코드를 늘리는 거래다. 이미지가 `redis:7-alpine`으로 고정돼 있고 배포 문서가 최소 버전을 적는다. 6.0에 붙이는 실수가 실제로 나면 그때 넣는다.
- **`spring.data.redis.port`/`password`의 타입 변환을 확인하자(fast #8).** 스프링 부트 relaxed binding의 기본 동작이고 이 레포가 `${DB_PORT:5432}`로 이미 같은 것을 하고 있다. 플랜에 정확한 YAML을 박아 두는 것으로 갈음한다.
- **Redis 장애 시 재시도나 폴백(fast #2).** 접는다. 5분짜리 로그인 코드에 재시도 계층을 두는 것보다 500을 내고 사용자가 로그인을 다시 누르는 편이 싸다. 인메모리 폴백은 지금 없애려는 그 상태를 되살리는 것이라 더 나쁘다.

## Open Questions

- 배포 환경 Redis에 비밀번호를 걸지, `app-net` 격리만으로 갈지. 구현은 `REDIS_URL`이 `redis://:password@host:port`를 그대로 받아 양쪽 다 되게 두므로 결정이 구현을 막지는 않는다. 배포 시점에 정하면 된다.
