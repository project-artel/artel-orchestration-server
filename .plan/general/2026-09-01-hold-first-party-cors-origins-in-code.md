# 2026-09-01 — first-party CORS origin 을 코드가 들고 있게 한다

- Date: 2026-09-01
- Jira: ARTEL-702
- Status: Draft

## Goal

배포의 `ARTEL_ALLOWED_ORIGINS` 값이 무엇이든 ARTEL 이 소유한 웹 호스트 셋(`artel.kr`,
`www.artel.kr`, `admin.artel.kr`)이 CORS 허용 목록에서 빠지지 않게 한다. 그러면 `https://admin.artel.kr` 이 `https://stage-orch.artel.kr`
을 부를 때 나는 CORS 실패가 사라지고, Jenkins Secret file 을 고치지 않아도 된다.

## Non-goals

- Jenkins Secret file 수정. 이 저장소에 없는 파일이고, 코드가 목록을 들면 필요 없다.
- admin-page 변경. `VITE_ORCHESTRATION_URL` 은 그대로 둔다.
- 인증, 쿠키, 보안 체인 변경.
- `https://*.artel.kr` 같은 하위 도메인 전체 패턴 도입.

## Context / Constraints

`AuthProperties.corsAllowedOrigins` 는 `allowedOrigins + frontendOrigin` 이다.
`allowedOrigins` 는 `application.yml` 이 `ARTEL_ALLOWED_ORIGINS` 에서 읽는 값이라,
환경변수가 있으면 기본값 네 개를 더하는 것이 아니라 통째로 대체한다.

2026-09-01 stage preflight 측정:

| Origin | 상태 |
|---|---|
| `https://home.stage.artel.kr` | 200, `Access-Control-Allow-Origin` 반향 |
| `https://artel.kr` | 403 |
| `https://admin.artel.kr` | 403 |

`https://artel.kr` 은 `application.yml` 기본값에 있는데도 403 이다. 기본값이 stage 에서
쓰이지 않는다는 뜻이고, 통과하는 것이 `frontend-url` 하나뿐이라는 것은 stage 의 `.env` 가
이 변수를 좁게 덮어썼다는 뜻이다.

ARTEL-295 는 기본값에 호스트를 한 줄 더 적어 같은 증상을 고쳤다. 대체 관계를 그대로 두었기
때문에 오버라이드가 생긴 순간 그 수정이 통째로 무효가 됐다.

제약:

- `allowCredentials` 가 true 라 `allowedOrigins` 에 `*` 를 쓸 수 없다. 그래서
  `SecurityConfig` 는 `allowedOriginPatterns` 를 쓴다. 설정 자리에는 배포마다 달라지는
  패턴이 들어올 수 있으므로 이 방식은 유지한다.
- 코드가 드는 목록은 호스트를 하나씩 적는다. 하위 도메인 전체를 한 패턴으로 묶으면 하위
  도메인을 하나 잃었을 때 그것이 곧 자격증명 실린 CORS origin 이 된다.

## Approach (Checklist)

- [ ] **Step 0: Recon** — `AuthProperties.kt:36-42`, `SecurityConfig.kt:corsConfigurationSource`,
      `application.yml:165-166`, `CorsAllowedOriginIntegrationTest.kt`
- [ ] **Step 1: Implementation**
  - `AuthProperties` 에 `FIRST_PARTY_ORIGINS` 상수 셋을 두고, `corsAllowedOrigins` 를
    `FIRST_PARTY_ORIGINS + allowedOrigins + frontendOrigin` 으로 바꾼다. 빈 문자열은 거른다.
  - `application.yml` 의 `allowed-origins` 기본값을 비우고, 이 값이 대체가 아니라 추가라는
    것을 주석에 적는다.
- [ ] **Step 2: Tests**
  - `AuthPropertiesCorsOriginTest` — stage 처럼 좁은 `allowedOrigins` 를 줘도
    `https://admin.artel.kr` 이 남는 것, 설정값이 그대로 더해지는 것, 빈 값이 걸러지는 것.
  - 기존 `CorsAllowedOriginIntegrationTest` 두 개를 그대로 통과시킨다. 이 테스트는 이제
    `allowed-origins` 가 빈 상태에서 도는 셈이라 바인딩까지 함께 확인한다.
- [ ] **Step 3: Rollout / Rollback**
  - `.env.example` 과 `docs/deployment.md` 에 `ARTEL_ALLOWED_ORIGINS` 의 의미를 적는다.
  - 배포는 develop 머지 후 stage 한 번. DB 변경도 마이그레이션도 없다.

## Validation

- **Commands to run:**
  - `./mvnw test -Dtest=AuthPropertiesCorsOriginTest`
  - `./mvnw test -Dtest=CorsAllowedOriginIntegrationTest`
- **Expected output:** 세 클래스 전부 통과. 배포 후 stage preflight 가
  `Access-Control-Allow-Origin: https://admin.artel.kr` 을 돌려주고, 목록 밖 origin 은 403 을 유지.

## Risks & Rollback

- **Risks:** 코드가 든 세 origin 은 설정으로 끌 수 없다. 그 호스트 중 하나를 잃으면
  자격증명 실린 CORS origin 이 되므로, 도메인을 정리할 때 이 목록도 같이 지워야 한다.
  배포마다 다른 프런트엔드는 목록에 넣지 않는다. stage 의 `home.stage.artel.kr` 은 그
  배포의 `frontend-url` 로 이미 허용되고, 코드에 적으면 operation 에서까지 열린다.
- **Rollback steps:** `git revert`. 설정 형식이 바뀌지 않아 이전 커밋으로 되돌려도 stage
  `.env` 를 손볼 필요가 없다.

## Open Questions

- 없음.
