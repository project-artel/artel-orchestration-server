# 2026-08-05 — 내부 서버-투-서버 API 경로를 /internal/** 로 통일

- Date: 2026-08-05
- Jira: ARTEL-265
- Status: Draft

## Goal

인증 없이 열린 서버-투-서버 경로를 `/internal/` 접두사 하나로 모아, 신뢰 경계가
경로에 드러나게 한다. `SecurityConfig`의 permitAll 목록에서 내부용 항목들이
`/internal/**` 한 줄로 접힌다. 후속 포트 분리(ARTEL-266)가 이 한 줄 위에 쌓인다.

## Non-goals

- 내부 경로에 인증(공유 시크릿 헤더·mTLS) 추가.
- 포트 분리, 프록시 규칙, Docker 포트 매핑 — ARTEL-266.
- `/ws/sdk`의 신뢰 모델 변경.
- 옮기는 엔드포인트들의 요청·응답 계약 변경. **경로만 바뀐다.**
- 옛 경로 호환 alias. 이슈가 명시적으로 거부했다.

## Context / Constraints

### 발신자 확인 결과 (이슈의 Blocker)

워크스페이스 5개 레포(artel-agent-server, artel-sdk, artel-home, admin-page,
artel-orchestration-server) 전체를 다시 훑었다. 베이스 URL 조립 지점(`ORCHESTRATION_BASE_URL`,
`VITE_ORCHESTRATION_URL`, `HttpBaseUri`)에서 출발해 그 위에 붙는 경로 리터럴을 전부 열거했다.

| 경로 | 발신자 |
|---|---|
| `/api/orchestration/llm-usage` | **있음** — `artel-agent-server/app/llm/usage.py:37,212`. ARTEL-267이 이미 이 티켓에 링크되어 있다. |
| `/api/knowledge/**` | **없음.** Agent의 knowledge 접근은 REST가 아니라 QA WebSocket 세션 채널(`QaAgentInboundRouter`)이다. |
| `/api/test-case-spec/**` | **없음.** 컨트롤러 KDoc이 "Agent 서버가 보낸다"고 적었지만 그 발신부가 아직 존재하지 않는다 — agent-server에 CSV 생성도, orchestration으로 나가는 다른 outbound도 없다. |
| `/api/orchestration/action/{instanceId}` | **없음.** 이 레포의 WS 통합 테스트만 부른다. |

발신자가 없는 두 경로는 옮겨도 깨질 것이 없다. 발신자가 있는 하나는 ARTEL-267이 받는다.
확인하지 못한 곳이 하나 있다: Insomnia 컬렉션은 원격 레포(`project-artel/insomnia-api`)에
살고 로컬에 클론되어 있지 않다. 코드 발신자는 아니므로 진행을 막지 않는다.

### 이슈가 세지 못한 네 번째 경로 — `/api/orchestration/action/{instanceId}`

이슈는 "`/api/orchestration/**` = llm-usage"로 적었지만, 그 접두사 아래에는
`SdkController.sendAction`(`sdk/controller/SdkController.kt:28`)도 있다. Agent가
연결된 게임 인스턴스로 액션을 밀어 넣는, 명백한 내부 서버-투-서버 경로다
(KDoc: "이 경로는 Agent 서버가 부르고, 대상 지정에는 자격증명이 아니라 게임 인스턴스 id를 쓴다").

AC가 요구하는 대로 permitAll에서 `/api/orchestration/**`를 빼면 이 경로는
`anyExchange().authenticated()`에 걸려 401이 된다. `ArtelWebSocketIntegrationTest`가
쿠키 없이 이 경로를 POST하므로 테스트가 즉시 빨개진다 — AC의 "경로만 바꿨는데 테스트가
통과하지 못하면 경계가 새는 지점이다"가 정확히 이것을 잡아낸 것이다.

**결정: 이것도 `/internal/action/{instanceId}`로 옮긴다.** 대안 둘은 모두 나쁘다.

- permitAll에 `/api/orchestration/action/**`를 남긴다 → AC가 없애라고 한 두 갈래 목록이
  그대로 남는다. 이 티켓의 존재 이유가 사라진다.
- 그냥 인증 대상이 되게 둔다 → 발신자에게 JWT가 없으므로 조용히 죽은 엔드포인트가 된다.

즉 옮기는 경로는 셋이 아니라 **넷**이다. 이 편차는 PR 본문과 Jira 코멘트에 남긴다.

### 그대로 두는 것

- `/api/projects/{projectId}/test-case-spec/download` (`TestCaseSpecController`) — 이름이
  비슷하지만 엔드유저용 인증 경로다. 손대지 않는다. 오히려 이 티켓의 동기 중 하나가
  이 둘의 이름 충돌이었으므로, 옮기고 나면 접두사만 봐도 구분된다.
- permitAll의 나머지: OAuth, swagger, `/ws/sdk`, `/api/auth/sdk/token`,
  `/api/auth/refresh`, `/api/auth/sdk/token/refresh`.

## Approach (Checklist)

- [x] **Step 0: Recon** — 발신자 워크스페이스 전수 조사, `/api/orchestration/**` 아래
      실제 엔드포인트 열거, 영향 받는 테스트·문서 목록화.
- [ ] **Step 1: 컨트롤러 매핑 4건** — `@RequestMapping` 문자열 교체.
  - `llmusage/controller/LlmUsageController.kt:20` → `/internal/llm-usage`
  - `knowledge/controller/KnowledgeController.kt:21` → `/internal/knowledge`
  - `testcase/controller/TestCaseSpecIngestController.kt:24` → `/internal/test-case-spec`
  - `sdk/controller/SdkController.kt:19,28` → 클래스 매핑 `/api` → `/internal`,
    메서드 매핑 `/orchestration/action/{instanceId}` → `/action/{instanceId}`.
    이 클래스에 다른 엔드포인트가 없음을 확인했으므로 클래스 매핑 교체가 다른 경로를
    끌고 가지 않는다.
- [ ] **Step 1b: 경로를 잘못 적은 KDoc 3곳** — 경로가 바뀌면 곧바로 거짓이 되는 문장들이다.
      새 문장 하나를 재사용하지 않고 각 컨트롤러의 맥락에 맞게 고친다.
  - `LlmUsageController.kt:16` — "SecurityConfig가 `/api/orchestration` 하위를 permitAll로 연다"
  - `KnowledgeController.kt:16` — "내부 경로(api/orchestration 하위, permitAll)". 이미
    지금도 틀렸다(실제 경로는 `/api/knowledge`). 옮기면 두 겹으로 틀리므로 함께 고친다.
  - `TestCaseSpecIngestController.kt:16-17` — "knowledge 조회 경로와 같은 취급으로
    SecurityConfig에서 permitAll이다" + 사용자용 다운로드 경로 대비 문장.
- [ ] **Step 2: SecurityConfig** — permitAll에서 세 줄(`/api/orchestration/**`,
      `/api/knowledge/**`, `/api/test-case-spec/**`)과 딸린 주석을 빼고 `/internal/**`
      한 줄로 대체. 주석은 "이 접두사 아래는 전부 내부 서버-투-서버"라는 규약을 적는다.
      나머지 항목은 손대지 않는다.
- [ ] **Step 3: 기존 테스트 경로 상수 갱신** — `LlmUsageIntegrationTest:114`,
      `KnowledgeIntegrationTest:104`, `TestCaseSpecHttpIntegrationTest:135`,
      `ArtelWebSocketIntegrationTest:399,486`, `OpenApiDocumentationIntegrationTest:28`.
- [ ] **Step 4: 신규 경계 테스트** — `auth/InternalPathSecurityIntegrationTest`(신규).
      **옛 경로 4개가 인증 없이 401이라는 것만 단언한다.** 이것이 이 변경이 만들어내는
      유일하게 새로운 동작이다. 나머지 두 가지는 이미 덮여 있어 다시 쓰지 않는다:
  - "`/internal/**`이 무인증으로 통한다"는 Step 3에서 경로를 갈아끼운 기존 통합 테스트
    5종이 **더 강하게** 증명한다 — 401이 아님이 아니라 실제 2xx 성공까지 본다.
  - "download가 인증을 요구한다"는 `TestCaseSpecHttpIntegrationTest`의
    `다운로드는 인증 없이 열리지 않는다`가 이미 401을 단언한다. 복제하지 않는다.

      기대 상태코드는 **정확히 401**이다("401 또는 404"가 아니다). `authorizeExchange`는
      `DispatcherHandler`가 라우팅하기 전에 도는 `WebFilter`라, 핸들러가 사라진 경로여도
      `anyExchange().authenticated()`에 먼저 걸려 엔트리포인트가 401을 낸다. 느슨하게
      `401 또는 404`로 단언하면 누군가 옛 경로 하나를 permitAll에 되돌려 놓아도
      404로 통과해버릴 수 있다.
- [ ] **Step 5: 문서** — `docs/api-documentation.md:23`의 옛 경로 표기.
      **`V24__create_llm_usage.sql:6`의 주석은 건드리지 않는다.** 이미 머지·적용된
      마이그레이션이라 주석 한 줄만 고쳐도 Flyway 체크섬이 바뀌어 적용된 DB의
      `validate`가 깨지고, 레포의 `scripts/check-flyway-migrations.sh`가 CI에서 잡는다.
      이력 기록이지 살아 있는 API 문서가 아니므로 낡은 경로 표기를 감수한다.
- [ ] **Step 6: 검증** — `./mvnw test` 전체.

## Validation

- **Commands to run:** `./mvnw test` (워크트리 `.worktrees/ARTEL-265`에서, 오프라인 `-o` 없이)
- **Expected output:** 전체 그린. 특히
  - 경로 상수만 바꾼 기존 통합 테스트 5종이 그대로 통과 — 계약이 안 바뀌었다는 증거.
  - 신규 경계 테스트가 옛 경로 4개의 폐기와 download의 인증 유지를 동시에 단언.

## Risks & Rollback

- **Risks:**
  - **배포 순서.** orchestration을 먼저 배포하고 agent-server(ARTEL-267)를 바로 뒤따르게
    한다. 반대로 하면 agent-server가 아직 없는 경로로 쏜다. 두 배포 사이 창에서 사용량
    전송이 404로 실패하고, `usage.py`의 버퍼는 재시도하지 않으므로 그 구간 기록은 유실된다.
    텔레메트리라 허용 가능한 손실로 본다(이슈 Constraints의 판단).
  - **리버스 프록시(NPM)가 `/api/`만 통과시키고 있다면** `/internal/`은 프록시에서 404가
    난다. 이 티켓은 프록시를 건드리지 않는다(non-goal) — 다만 ARTEL-266이 이 경로를
    외부에서 차단하는 것이 목적이므로, 배포 시점에 프록시가 `/internal/`을 어떻게 다루는지
    확인이 필요하다. **미해결 리스크로 PR에 남긴다.**
  - **Insomnia 컬렉션**(원격 `project-artel/insomnia-api`)에 옛 경로가 박혀 있으면 수동
    호출이 404가 된다. 코드 경로가 아니라 사람이 쓰는 도구다. `insomnia-sync`로 갱신 가능.
  - **네 번째 경로 이동이 AC 범위 밖**이라는 점. 리뷰에서 되돌리라는 결정이 나올 수 있다.
- **Rollback steps:** 경로 문자열만 되돌리는 `git revert`. 스키마·계약 변경이 없어
  런타임 상태가 남지 않는다. 단, 되돌릴 때 agent-server(ARTEL-267)도 함께 되돌려야 한다.

## Rejected feedback

- "옛 경로 401 단언을 새 클래스 대신 기존 테스트들에 한 메서드씩 나눠 넣어라" — 리뷰어도
  강한 주장은 아니라고 했다. 네 경로의 폐기는 하나의 계약("이 접두사는 더 이상 없다")이라
  한곳에서 읽히는 편이 낫다. Spring 컨텍스트 캐싱 덕에 같은 모양의 `@SpringBootTest`
  클래스를 하나 더 두는 비용도 작다.

## Open Questions

- Insomnia 컬렉션에 `/api/knowledge`·`/api/test-case-spec` 요청이 들어 있는가? 로컬에
  클론이 없어 확인 불가. 있다면 `insomnia-sync`로 후속 갱신.
- 리버스 프록시(NPM)의 현재 location 규칙이 `/api/`만인지 `/`인지. ARTEL-266 착수 시 확정.
