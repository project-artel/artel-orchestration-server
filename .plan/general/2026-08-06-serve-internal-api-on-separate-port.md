# 2026-08-06 — 내부 API(/internal/**)를 별도 포트로 분리해 외부 노출을 차단

- Date: 2026-08-06
- Jira: ARTEL-266
- Status: Implemented

## Goal

ARTEL-265가 만든 `/internal/**` 접두사를 **8080이 아닌 별도 포트에서만** 서빙한다.
리버스 프록시(NPM)는 8080만 알고 내부 포트는 존재조차 모르므로, "프록시에서
`/internal/`을 막는다"는 레포 밖 규칙에 대한 의존이 사라진다.

- 8080: `/api/**`, `/oauth2/**`, `/login/oauth2/**`, `/ws/**`, swagger — `/internal/**`은 404
- 내부 포트(기본 8081): `/internal/**`만 — 나머지는 전부 404

## Non-goals

- 내부 경로 인증(공유 시크릿 헤더·mTLS). `app-net` 안을 신뢰하는 현재 전제를 유지한다.
- NPM 설정 변경. 레포 밖 인프라이며, 8080만 프록시하는 현재 설정이 그대로 맞다.
- actuator·헬스체크 정리(actuator 의존 자체가 없다).
- 엔드포인트의 요청·응답 계약 변경. **어느 포트에서 뜨는지만 바뀐다.**
- ARTEL-265가 옮긴 경로를 다시 건드리는 것.

## Context / Constraints

### 스택 PR

이 브랜치는 `develop`이 아니라 ARTEL-265 브랜치
(`refactor/내부-서버-투-서버-api-경로를-internal-로-통일-ARTEL-265`, PR #82) 위에서 딴다.
265가 머지되기 전에는 `/internal/**` 자체가 없다. PR base도 265 브랜치이며, 265가
머지되면 base를 `develop`으로 바꾼다.

### WebFlux에서 두 번째 포트는 설정 한 줄이 아니다

서블릿 스택의 `management.server.port`에 해당하는 것이 없다. reactor-netty의
`HttpServer` 인스턴스 하나는 소켓 하나만 바인딩하므로, **두 번째 포트를 열려면 두 번째
서버 인스턴스를 반드시 띄워야 한다.** 이슈가 적은 선택지 중 "단일 서버 유지 + 필터"는
엄밀히는 성립하지 않는다 — 두 번째 서버 없이는 애초에 두 번째 포트가 없다. 실제 선택지는
"두 서버가 요청을 어떻게 가르느냐"이다.

### 선택지 평가

**(A) 별도 Netty 서버 + 별도 ApplicationContext(부모-자식)**
가장 강한 격리. Boot가 actuator 별도 포트를 이 방식으로 구현한다. 하지만 자식 컨텍스트가
자기 빈 그래프를 갖게 되어 R2DBC 커넥션 풀·Redis·S3 클라이언트가 두 벌이 되거나, 부모에서
끌어오는 배선을 손으로 짜야 한다. 컨트롤러 4개를 위한 대가로 과하다. **기각.**

**(B) 단일 핸들러 체인 + 요청의 로컬 포트를 보는 `WebFilter`**
`exchange.request.localAddress.port`를 읽어 접두사와 대조한다. 싸지만 문제가 둘이다.

1. **필터가 빠지면 조용히 열린다.** 이슈가 지적한 그대로다. 경계가 "요청을 검사하는
   런타임 규칙" 하나에 걸려 있고, 그 규칙을 지우는 diff는 아무것도 깨뜨리지 않은 것처럼
   보인다(테스트가 있다면 잡히지만, 테스트도 같이 지우면 끝이다).
2. **`localAddress`가 없는 경로가 있다.** `@AutoConfigureWebTestClient`로 컨텍스트에
   바인딩한 요청에는 실제 소켓이 없어 `localAddress`가 null이다. 그러면 필터는
   "모르면 통과"(= 조용히 열림)나 "모르면 차단"(= 265의 기존 테스트 전부 빨감) 중
   하나를 골라야 한다. 어느 쪽도 좋지 않다. **기각.**

**(C) 컨테이너·프록시 레벨에서만 차단** — 이 티켓이 없애려는 바로 그 의존. **기각.**

### 채택 — (D) 두 서버, 두 핸들러 체인, 한 ApplicationContext

같은 `ApplicationContext`에서 `WebHttpHandlerBuilder`로 **서로 다른 두 개의
`HttpHandler`를 조립**한다. 요청을 검사해서 가르는 것이 아니라, **어느 서버가 그 커넥션을
받았는지**로 갈린다 — 라우팅 결정이 조립 시점에 고정된다.

- **공개 체인(8080)**: Boot가 만드는 `httpHandler` 빈. 여기에 Boot의 확장점인
  `WebHttpHandlerBuilderCustomizer` 빈으로 "`/internal/**`이면 404" 필터를 맨 앞에 끼운다.
  (`HttpHandlerAutoConfiguration$AnnotationConfig.httpHandler`가 이 커스터마이저를
  `build()` 직전에 적용한다 — 클래스 시그니처로 확인함.)
- **내부 체인(8081)**: `WebHttpHandlerBuilder.applicationContext(ctx)`로 새로 조립하고
  "`/internal/**`이 아니면 404" 필터를 맨 앞에 끼운 뒤, 별도 reactor-netty `HttpServer`에
  물린다.

두 필터 중 어느 것도 **`WebFilter` 빈이 아니다.** 빈으로 두면 두 체인 모두에 들어가
서로를 무력화한다. 각 체인의 조립 코드에서만 붙는다. 이 제약은 코드 주석으로 못 박는다
(리뷰 지적: 누가 무심코 `@Component`를 붙이면 경계가 조용히 무너진다).

`WebHttpHandlerBuilderCustomizer`는 Boot **내부 클래스가 아니라 공개 확장점**이다
(`org.springframework.boot.autoconfigure.web.reactive`). 다만 널리 쓰이는 API는 아니므로,
사용처에 "Boot 3.3.1의 `HttpHandlerAutoConfiguration`이 `build()` 직전에 적용한다"는
근거를 주석으로 남긴다. Boot 업그레이드 때 이 가정이 깨지면 신규 통합 테스트가 즉시 잡는다
(공개 포트의 `/internal/**`이 404가 아니게 되므로).

이 선택의 성질:

- **(B)와 달리 요청을 검사하지 않는다.** 포트 번호를 읽는 코드가 없으므로
  `localAddress`가 null인 경로(컨텍스트 바인딩 테스트)도 영향받지 않는다. 265가 남긴
  `@AutoConfigureWebTestClient` 테스트들은 그대로 통과한다.
- **(A)와 달리 컨텍스트가 하나다.** 두 체인 모두 같은 `WebFilter` 빈들(Security 체인,
  `ForwardedHeaderTransformer`), 같은 `DispatcherHandler`, 같은 `@RestControllerAdvice`
  (`ApiExceptionHandler`)를 쓴다. 나중에 누가 전역 필터를 추가해도 두 포트에 똑같이
  적용된다 — 체인이 갈라져 조용히 어긋나는 일이 없다.
- **남는 약점(정직하게):** 두 필터 자체는 여전히 코드다. 지우면 경계가 무너진다.
  (B)와 다른 점은 *지웠을 때 무슨 일이 나는지*다. 내부 체인 필터를 지우면 내부 포트가
  `/api/**`까지 열린다(외부에 안 뜨므로 실피해는 없음). 공개 체인 필터를 지우면 8080이
  `/internal/**`을 다시 연다 — 이것이 유일한 실질 회귀 경로이고, **AC가 요구하는 신규
  통합 테스트가 실제 소켓으로 이 한 가지를 정확히 고정한다.**
- **실질적인 외부 차단 근거는 코드가 아니라 배포 토폴로지다.** `Jenkinsfile`의
  `docker run`에 `-p`가 없고 컨테이너는 `app-net`에만 붙는다. 즉 8081은 호스트에 게시되지
  않는다. 코드의 포트 분리는 그 토폴로지를 **리뷰 가능한 형태로 코드에 새기는 것**이고,
  실제 차단은 "`-p`를 추가하지 않는다"가 지킨다. 이 사실을 `docs/deployment.md`에 남긴다.

### ARTEL-265 워커가 남긴 경고 — 리버스 프록시(NPM)

265 PR의 미해결 리스크: NPM이 `/api/`만 orchestration으로 포워딩하고 있다면 265 배포 후
`/internal/`은 **프록시에서 404**가 난다. 스테이지의 내부 호출(agent-server → orchestration)이
프록시를 경유한다면 265 단독 배포가 사용량 전송을 깨뜨린다.

**266은 이 문제를 없애는 방향이다.** 내부 호출이 `http://artel-orchestration-server-stage:8081`
같은 컨테이너 이름 + 내부 포트로 가면 프록시를 아예 거치지 않는다. 다만 순서가 중요하다.

- 265만 먼저 배포되고 agent-server가 여전히 공개 호스트(`https://stage-orch.artel.kr`)를
  베이스로 쓰면, NPM의 location 규칙에 따라 사용량 전송이 404가 될 수 있다.
- 266까지 배포하고 `.env`의 `ORCHESTRATION_BASE_URL`을 `app-net` 내부 주소 + 8081로
  바꾸면 프록시 의존이 사라진다.

따라서 **265와 266은 가능하면 한 번에 배포**하고, `.env` 갱신을 같은 창에서 끝낸다.
이 순서와 검증 절차를 `docs/deployment.md`에 남긴다. NPM 설정 자체는 건드리지 않는다.

### agent-server 쪽 필수 동반 배포 — ARTEL-267

이슈는 "`ORCHESTRATION_BASE_URL`이 포트를 포함하므로 값만 바뀐다 — agent-server 코드
변경 없음"이라고 적었다. **이 문장은 ARTEL-267이 이미 나가 있을 때만 참이다.**
agent-server의 `develop`은 아직 `app/llm/usage.py:37`에서
`USAGE_PATH = "/api/orchestration/llm-usage"`를 쓴다. 265가 그 경로를 없앴고 266은
그 경로를 내부 포트에서도 404로 만든다. 267 브랜치
(`refactor/llm-사용량-전송-경로를-internal-llm-usage-로-변경-ARTEL-267`)에는 이미
`USAGE_PATH = "/internal/llm-usage"`가 들어 있다.

즉 배포 단위는 **265 + 266 + 267 + `.env` 갱신**이 한 창이다. 하나라도 빠지면 사용량
전송만 조용히 죽는다. 다만 그 셋만으로 충분하다는 것도 확인했다 — agent-server에서
`orchestration_base_url`을 쓰는 곳은 `app/llm/usage.py` 하나뿐이고, `/internal/**`을
부르는 다른 발신자는 없다.

### `ORCHESTRATION_BASE_URL`

stage/operation의 Jenkins Secret file(.env)에 있다. 값이 포트를 포함하므로 **ARTEL-267이
나간 뒤에는** agent-server 코드 변경 없이 값만 바뀐다(바로 위 절 참고 — 267 전에는 값만
바꿔서는 안 된다). 빠뜨리면 사용량 전송만 조용히 실패하고(`usage.py` 버퍼는
재시도하지 않으므로 유실) 컨테이너는 정상으로 보인다. 이 레포에는 그 변수가 없다
(agent-server 쪽 `.env`다) — 그래서 **`docs/deployment.md`의 배포 체크리스트에만** 남긴다.

## Approach (Checklist)

- [x] **Step 0: Recon** — 265 diff·PR #82, `SecurityConfig`, `application.yml`,
      `Dockerfile`, `Jenkinsfile`, `docs/deployment.md`, `/internal`을 부르는 테스트 전수,
      Boot 3.3.1 `HttpHandlerAutoConfiguration`의 확장점 확인.

- [x] **Step 1: 설정 프로퍼티** — `config/InternalApiProperties.kt`(신규).
      `artel.internal-api.port`, 기본 8081. `application.yml`에
      `${ARTEL_INTERNAL_API_PORT:8081}`로 노출.

- [x] **Step 2: 경계 필터 — 구현은 하나, 극성만 둘** — `config/InternalApiConfig.kt`(신규, 파일 하나).
      두 필터는 서로의 거울상이므로 **손으로 두 번 쓰지 않는다**(리뷰 지적: 따로 쓰면 한쪽만
      고쳐지는 드리프트가 난다). 팩터리 하나를 두고 극성만 뒤집어 두 번 인스턴스화한다.

      ```kotlin
      private val INTERNAL = PathPatternParser.defaultInstance.parse("/internal/**")
      fun prefixGate(blockWhenMatches: Boolean): WebFilter
      ```

      - 매칭 대상은 `exchange.request.path.pathWithinApplication()`이고 패턴 파서는
        Security의 `PathPatternParserServerWebExchangeMatcher`와 같은
        `PathPatternParser`다 — `/internal/**`의 의미론이 permitAll 목록과 어긋나지 않는다.
        별도 패턴 동치성 단위 테스트는 두지 않는다(Rejected feedback 참고).
      - 차단 시 **404 + 빈 본문**으로 응답을 종료한다. `NotFoundException`을 던지지 않는다:
        `@RestControllerAdvice`는 `DispatcherHandler` 안에서만 돈다. `WebFilter`에서 던진
        `ApiException`은 advice에 닿지 않고 500이 된다. 이 이탈 사유를 코드 주석에
        남긴다(`.agents/docs/error-handling.md`).
      - 본문을 비우는 이유: "왜 없는지"를 알려주지 않는 것이 목적이다. 존재하지 않는
        경로와 구분되지 않아야 한다.
      - 파일 상단에 **"이 필터들에 `@Component`/`@Bean`을 붙이지 말 것"** 경고 주석.
        빈이 되는 순간 두 체인 모두에 들어가 서로를 무력화한다.

- [x] **Step 3: 공개 체인에 필터 끼우기** — `WebHttpHandlerBuilderCustomizer` 빈.
      `builder.filters { it.add(0, prefixGate(blockWhenMatches = true)) }`. Security보다
      앞이라 `/internal/**`이 8080에서 401이 아니라 404가 된다(정보 노출도 없음).

- [x] **Step 4: 내부 서버** — `config/InternalApiServer.kt`(신규), `SmartLifecycle`.
      `start()`에서 `WebHttpHandlerBuilder.applicationContext(ctx)` +
      `prefixGate(blockWhenMatches = false)`로 `HttpHandler`를 조립하고
      `HttpServer.create().port(port).handle(ReactorHttpHandlerAdapter(handler)).bindNow()`.
      `stop()`에서 `disposeNow()`.
      - **`@ConditionalOnWebApplication(type = REACTIVE)`가 필수다.** 이것이 없으면
        `webEnvironment = NONE` 테스트 12종이 전부 컨텍스트 기동 단계에서 죽는다.
        `WebFluxAutoConfiguration`이 `@ConditionalOnWebApplication(type = REACTIVE)`라
        NONE 컨텍스트에는 `webHandler` 빈이 없고,
        `WebHttpHandlerBuilder.applicationContext(ctx)`가 `NoSuchBeanDefinitionException`을
        던진다. 해당 12개(확인함): `KnowledgeEmbeddingBackfillIntegrationTest`,
        `TestCaseHierarchyIntegrationTest`, `QaRunConfigPersistenceIntegrationTest`,
        `TestCaseSpecIngestIntegrationTest`, `KnowledgeVectorSearchIntegrationTest`,
        `KnowledgeStatsIntegrationTest`, `TestCaseEmbeddingBackfillIntegrationTest`,
        `QaStatsIntegrationTest`, `KnowledgeSearchRouterIntegrationTest`,
        `TestCaseVectorSearchIntegrationTest`, `KnowledgeMutationInboundIntegrationTest`,
        `KnowledgeEventIntegrationTest`.
      - **phase = `2147481599`**(= `Integer.MAX_VALUE - 2048`), Boot의
        `WebServerStartStopLifecycle.getPhase()`와 **같은 값**이다(바이트코드로 확인함).
        같은 단계에 두어야 두 서버가 같이 뜨고 같이 내려간다 — 한쪽만 살아 있는 구간을
        만들지 않는다. 상수를 코드에 적을 때 이 확인 사실을 주석으로 남긴다.
        `isAutoStartup = true`.
      - 바인딩된 실제 포트를 `val port: Int` 프로퍼티로 노출한다(`server.port()`).
        서버가 안 떠 있으면 `IllegalStateException` — 조용히 -1을 돌려주지 않는다.

- [x] **Step 5: 테스트 프로파일** — `src/test/resources/application-test.yml`에
      `artel.internal-api.port: 0`. 스위트에 Spring 컨텍스트가 여럿 뜨므로 고정 포트면
      바인드 충돌이 난다. 0이면 각 컨텍스트가 빈 포트를 잡는다.
      컨텍스트 종류별 결과: `NONE` 컨텍스트는 Step 4의 조건 때문에 내부 서버가 아예 없고,
      `MOCK`(기본 `@SpringBootTest`) 컨텍스트는 **실제로 0번 포트에 소켓을 하나 연다**
      (공개 서버는 없는데 내부 서버만 있는 상태 — 문제는 없지만 알고 있어야 한다).
      **측정 결과(수행함):** 265 커밋(`92d71bf`)을 별도 워크트리에 그대로 체크아웃해 잰
      기준선이 `Total time: 01:36 min`(348 tests), 이 브랜치가 `01:37 min`(354 tests)다.
      컨텍스트마다 Netty 서버가 하나 늘었지만 차이는 1초로 노이즈 수준이고, 테스트도 6개
      늘었다. `@ConditionalOnProperty`로 껐다 켜는 복잡도를 넣을 이유가 없다.

      부수 소득: 그 기준선 실행에서 `JwtServiceTest`와 `RefreshTokenServiceTest`가 동시에
      실패했다. **이 브랜치와 무관한 기존 flaky 결함**임이 확정되어 ARTEL-271로 분리했다.

- [x] **Step 6: 265가 남긴 실소켓 테스트 3종을 내부 포트로 옮긴다.**
      이들은 `RANDOM_PORT`로 실제 8080을 두드리므로 이 변경으로 404가 되는 것이 **정상**이다.
      바뀌는 것은 "어느 포트로 부르는가"뿐, 단언은 그대로다.

      **배선 패턴은 셋이 동일하게 쓴다**(리뷰 지적: 파일마다 다르게 즉흥 배선하지 말 것).
      새 베이스 클래스는 만들지 않는다 — 세 줄짜리 패턴에 상속 계층을 세우는 것이 더 비싸다.

      ```kotlin
      @Autowired private lateinit var internalApiServer: InternalApiServer
      private fun internalClient() = WebClient.create("http://localhost:${internalApiServer.port}")
      ```

      - `TestCaseSpecHttpIntegrationTest:135` — `post`/`postExpectingError` 헬퍼만
        내부 클라이언트로 바꾼다. **같은 파일의 `다운로드는 인증 없이 열리지 않는다`
        (`/api/projects/{id}/test-case-spec/download` → 401, 127행)는 공개 포트에 그대로
        둔다.** 두 테스트가 `client` 프로퍼티 하나를 공유하고 있으므로 통째로 갈아끼우면
        그 401 단언이 404로 바뀌어 조용히 무의미해진다.
      - `KnowledgeIntegrationTest:104` — `/internal/knowledge`
      - `ArtelWebSocketIntegrationTest:399,486` — `/internal/action/{id}`
        (WS 핸드셰이크는 공개 포트 그대로, 액션 POST만 내부 포트로)

      `LlmUsageIntegrationTest`는 `@AutoConfigureWebTestClient`(컨텍스트 바인딩)라
      어느 포트도 거치지 않는다 — **손대지 않는다.** 이것이 (D)를 고른 이유 중 하나다.
      역할 분담은 이렇게 굳힌다: **기존 통합 테스트들은 핸들러/계약을 본다**(어느 포트에서
      뜨는지와 무관), **신규 `InternalApiPortIntegrationTest` 하나만 포트 경계를 본다.**
      같은 엔드포인트를 두 각도에서 보는 것이지 중복이 아니다.

- [x] **Step 7: 신규 통합 테스트** — `config/InternalApiPortIntegrationTest`(신규),
      `RANDOM_PORT`. AC가 요구한 것을 **실제 소켓**으로 단언한다.
      - 공개 포트의 `/internal/llm-usage` → 404 (401이 아님을 정확히 단언)
      - 내부 포트의 `/api/projects` → 404 (401이 아님 — Security에 닿기 전에 끊긴다)
      - 내부 포트의 `/internal/llm-usage` → 204 (정상 동작)
      - 내부 포트의 `/oauth2/authorization/github`, `/ws/sdk` → 404
      느슨한 "404 또는 401"로 단언하지 않는다. 401이 나온다는 것은 요청이 Security까지
      갔다는 뜻이고, 그것은 경계가 한 겹 밀린 것이다.

      접두사 오매칭(`/internalfoo`)은 **HTTP로 단언하지 않는다.** 게이트가 맞게 동작해도
      틀리게 동작해도 결과가 똑같이 404라(그 경로에 핸들러가 없으므로) 상태 코드로는
      구분되지 않는 케이스다. 이것은 테스트가 아니라 구현으로 막는다 —
      `startsWith("/internal")`이 아니라 `PathPattern`을 쓰고, 그 이유를 주석에 남긴다.

- [x] **Step 8: `Dockerfile`** — `EXPOSE 8080 8081`. `EXPOSE`는 문서일 뿐 포트를 게시하지
      않는다는 사실을 주석으로 남긴다(그것이 이 티켓의 안전 전제와 직결된다).

- [x] **Step 9: `docs/deployment.md`** — 새 절 "Ports".
      - 두 포트의 역할
      - **내부 포트를 프록시·호스트 매핑에 노출하지 않는다** — `docker run`에 `-p`를
        추가하지 않는 것이 외부 차단의 실질 근거. `EXPOSE`는 문서일 뿐 게시가 아니다.
      - NPM은 8080만 프록시한다(변경 없음)
      - **배포 체크리스트**(순서 있는 목록으로):
        1. stage/operation `.env`의 `ORCHESTRATION_BASE_URL`을 `app-net` 내부 주소 +
           내부 포트(`http://artel-orchestration-server-<env>:8081`)로 바꿔 Secret file 재업로드
        2. orchestration 배포(265+266을 같은 창에서)
        3. **agent-server의 ARTEL-267 배포** — `USAGE_PATH`가 `/internal/llm-usage`로
           바뀐 빌드. 이것이 빠지면 사용량 전송이 옛 경로로 404가 난다
        4. `docker port <container>` 출력이 **비어 있는지** 확인 — 8081이 보이면 즉시 중단
        5. 공개 호스트에서 `/internal/llm-usage`가 404인지
        6. `app-net`의 다른 컨테이너에서 내부 포트로 같은 요청이 204인지 —
           **여기까지 통과해야 배포 완료로 본다.** `llm_usage` 행 증가를 기다려 확인하는
           것은 실패를 늦게 알게 되는 방식이라 최종 확인용으로만 둔다
        7. agent-server 재기동 후 `llm_usage`에 새 행이 쌓이는지
      - 265와의 배포 순서 및 NPM location 규칙에 대한 주의

- [x] **Step 10: `.env.example`** — `ARTEL_INTERNAL_API_PORT=8081` + 게시 금지 주석.

- [x] **Step 11: `.agents/docs/project.md`의 "API 표면과 신뢰 경계" 절.**
      이 절은 develop 체크아웃에 **커밋되지 않은 채** 남아 있다. ARTEL-265의 PR #82 diff에는
      `.agents/docs/project.md`가 없음을 확인했으므로 265와 충돌하지 않는다 — 266이 커밋한다.
      다른 워크트리의 미커밋 상태에 의존하지 않도록 **최종 문안을 여기에 못 박는다**(리뷰 지적).

      `## Architecture`와 `## Commands` 사이에 아래 문안을 **그대로** 넣는다. 규칙 3은
      develop 체크아웃의 원문("`/internal/**`은 리버스 프록시가 넘기는 공개 호스트에
      노출하지 않는다")에서 바뀐 것이다 — 266 이후로는 프록시가 넘기고 말고의 문제가
      아니라 8080에 그 경로가 아예 없다.

      ```markdown
      ## API 표면과 신뢰 경계

      이 앱은 공개 API와 무인증 내부 API를 함께 서빙한다. 둘을 가르는 것은 경로
      접두사이고, 그 접두사는 **서로 다른 포트**에 실린다.

      - `/api/**` — 엔드유저 API. JWT 인증 대상. 공개 포트(8080)
      - `/internal/**` — 서버-투-서버. agent-server가 부르며 인증이 없다.
        내부 포트(기본 8081)에만 존재한다
      - `/ws/sdk`, `/ws/viewer` — WebSocket. 공개 포트. `/ws/sdk`는 핸드셰이크가 쿼리
        파라미터로 토큰을 실어 `SdkWebSocketHandler`가 직접 검증한다
      - `/oauth2/**`, `/login/oauth2/**`, `/v3/api-docs/**`, `/swagger-ui/**` —
        로그인 흐름과 문서. 공개 포트

      규칙 셋:

      1. 새 서버-투-서버 라우트는 `/internal` 아래에 붙인다. `SecurityConfig`의 permitAll
         목록에 개별 경로를 추가하지 않는다 — `/internal/**` 한 줄이 이미 그것을 덮는다.
      2. 무인증 라우트를 `/api/` 아래 두지 않는다. 그렇게 하면 permitAll 목록이 다시
         갈라지고, 그 경로가 공개 포트에 실려 인터넷에 노출된다.
      3. `/internal/**`은 8080에 존재하지 않는다. 별도 내부 포트에서만 서빙되고, 리버스
         프록시는 8080만 알기에 그 경로를 넘길 수단이 없다. 반대로 내부 포트에는
         `/api/**`·`/oauth2/**`·`/ws/**`가 없다. 이 분리는 같은 ApplicationContext에서
         조립한 두 개의 `HttpHandler` 체인이 강제한다(`config/InternalApiServer.kt`,
         `config/InternalApiConfig.kt`). 실질적인 외부 차단은 내부 포트를 호스트에
         게시하지 않는 배포 구성이 맡으며 `docs/deployment.md`가 근거를 담는다.

      경로가 비슷해 헷갈리는 쌍이 있다. `/internal/test-case-spec/**`은 Agent가 명세를
      밀어 넣는 무인증 경로이고, `/api/projects/{projectId}/test-case-spec/download`은
      사용자 다운로드로 인증 대상이다. 이름이 겹쳐도 신뢰 모델은 정반대이며, 이제 뜨는
      포트조차 다르다.
      ```

      **develop 체크아웃의 원본 파일은 건드리지 않는다.**

- [x] **Step 12: `docs/api-documentation.md`** — swagger/`/v3/api-docs`는 공개 포트에만
      있고 내부 포트에는 없다는 한 줄. `/internal/action/{instanceId}` 항목에 포트 표기.

- [x] **Step 13: 검증** — `./mvnw test` 전체(`-o` 금지).

## Validation

- **Commands to run:** `.worktrees/ARTEL-266`에서 `./mvnw test`
- **Expected output:**
  - 전체 그린.
  - `LlmUsageIntegrationTest` 등 컨텍스트 바인딩 테스트가 **무수정**으로 통과 — 경계가
    요청 검사가 아니라 체인 조립으로 갈렸다는 증거.
  - 신규 `InternalApiPortIntegrationTest`가 두 포트의 404/2xx를 실소켓으로 고정.
- **수동(stage, 배포 후):**
  - `curl -i https://stage-orch.artel.kr/internal/llm-usage` → 404
  - `app-net`의 다른 컨테이너에서
    `curl -i -X POST http://artel-orchestration-server-stage:8081/internal/llm-usage -d '{"records":[]}' -H 'Content-Type: application/json'` → 204
  - `docker port <container>` 출력이 비어 있는지 — 비어 있어야 한다
  - `llm_usage`에 새 행이 쌓이는지. 안 쌓이면 원인은 둘 중 하나다 — `.env`의
    `ORCHESTRATION_BASE_URL`을 안 바꿨거나, agent-server의 ARTEL-267이 아직 안 나갔거나

## Risks & Rollback

- **Risks:**
  - **`.env` 미갱신.** 가장 가능성 높은 실패. 컨테이너는 정상으로 보이고 사용량 전송만
    조용히 죽는다(버퍼가 재시도하지 않아 그 구간은 유실). 체크리스트로만 막을 수 있다.
  - **ARTEL-267 미배포.** 같은 증상이고 같은 크기의 위험이다. agent-server의
    `USAGE_PATH`가 옛 경로면 `.env`를 아무리 맞춰도 404다. 체크리스트 3번 항목.
  - **누군가 `-p 8081:8081`을 추가.** 그 순간 외부 차단이 무너진다. 코드로는 막을 수
    없으며 `docs/deployment.md`의 명시적 금지와 리뷰에 의존한다.
  - **265 미머지 상태의 스택 PR.** 265가 리뷰에서 뒤집히면 이 PR의 토대가 흔들린다.
  - **NPM location 규칙.** 265 배포와 266 배포 사이에 창이 생기면 그 구간에서 내부 호출이
    프록시를 통해 404가 날 수 있다. 두 배포를 붙이고 `.env`를 같은 창에서 바꾼다.
  - **컨텍스트마다 Netty 서버 하나 추가.** 포트 0을 쓰므로 충돌은 없고, 측정 결과
    스위트 시간 차이는 1초(노이즈 수준)였다 — Step 5 참고. 실질 리스크가 아닌 것으로 확인.
  - **기존 flaky 테스트 2종(ARTEL-271).** 265 기준선에서도 재현되는 무관한 결함이지만,
    이 PR의 CI가 약 12% 확률로 빨갛게 될 수 있다. 그 실패는 이 변경 탓이 아니다.
  - **Boot 내부 확장점 의존.** `WebHttpHandlerBuilderCustomizer`는 Boot의 공개 API지만
    널리 쓰이지는 않는다. Boot 업그레이드 시 확인 대상 — 신규 테스트가 회귀를 잡는다.
- **Rollback steps:** `git revert`. 스키마 변경이 없고 런타임에 남는 상태가 없다.
  되돌리면 `/internal/**`이 다시 8080에 뜨므로, agent-server `.env`의
  `ORCHESTRATION_BASE_URL`도 함께 되돌려야 한다.

## Rejected feedback

- **"두 필터의 `/internal/**` 매칭이 Security의 매처와 동치인지 단위 테스트로 증명하라"**
  (fast) — 기각. 양쪽 다 같은 `PathPatternParser`로 같은 문자열을 파싱한다. 그 동치성을
  단언하는 테스트는 우리 코드가 아니라 Spring을 테스트하는 것이고, 파서가 바뀌면 그 테스트도
  같이 통과하게 바뀐다. 실제로 지켜야 할 성질(8080에 `/internal`이 없다)은 Step 7이 본다.
- **"`WebHttpHandlerBuilderCustomizer`가 Boot 내부 API라 위험하니 폴백을 두라"**
  (fast) — 절반 기각. 이것은 `autoconfigure`의 공개 확장점이지 내부 클래스가 아니다.
  폴백 경로를 미리 짜 두는 것은 쓰이지 않을 코드를 두는 것이라 하지 않는다. 대신 근거
  주석을 남기고, 회귀는 Step 7이 잡게 한다.
- **"Step 11(project.md)을 이 티켓에서 빼라"** (medium) — 기각. 그 절은 지금 어느 브랜치에도
  없고 265 PR에도 없다. 266이 그 절의 규칙 3을 실제로 뒤집으므로, 틀린 문안을 남에게
  넘기는 대신 여기서 고쳐 커밋하는 것이 맞다. 다만 "다른 워크트리에서 가져온다"는
  지시는 지적대로 재현 불가능하므로, 최종 문안을 Step 11에 직접 적어 자족적으로 만들었다.

## Open Questions

- 스테이지의 agent-server가 orchestration을 부를 때 공개 호스트를 쓰는가, `app-net`
  컨테이너 이름을 쓰는가? 전자면 265 단독 배포가 위험하고, 266까지 가면 후자로 바뀐다.
  `.env` 실값은 Jenkins Credentials 안이라 이 레포에서 확인할 수 없다.
