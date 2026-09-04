# 2026-09-03 — 로그인한 사용자가 자기 SDK 토큰을 발급받는다

- Date: 2026-09-03
- Jira: ARTEL-788
- Status: Done

## Goal

`POST /api/auth/sdk-tokens` 를 연다. 로그인한 사용자가 브라우저 왕복 없이 자기 SDK 토큰을 받는
경로이고, 쿠키 세션과 `cli_token` 둘 다로 부를 수 있다.

CLI 가 쥔 것은 `cli_token` 이고 그것은 엔드유저 API 를 연다. 그런데 게임 안의 SDK 는 `/api/sdk`
아래를 부르며 그 체인은 `aud=artel-sdk` 만 받는다. 지금 SDK 토큰을 얻는 길은 브라우저 loopback
(`/api/auth/sdk/codes` → `/api/auth/sdk/token`) 하나뿐이라, 사람이 창을 열지 못하는 무인 실행에서는
CLI 가 게임에 건네줄 토큰을 만들 방법이 없다.

이 작업은 ARTEL-780 위에 쌓는다. `cli_token` 테이블, `artel_` bearer 필터, CLI 로그인 코드 교환은
이미 있고 건드리지 않는다.

## Non-goals

- 발급된 SDK 토큰의 목록·폐기. 상태 없는 JWT 라 개별 폐기가 없다
- 프로젝트 단위로 범위를 좁힌 SDK 토큰
- `/api/auth/sdk/codes` 와 `/api/auth/sdk/token` 의 계약 변경. 배포된 SDK 와 중계 페이지가 그
  길로 다닌다
- SDK 토큰을 상태 있는 토큰으로 바꾸는 것

## 결정

### 경로는 `/api/auth/sdk-tokens` 다

`/api/sdk` 아래는 쓸 수 없다. 그 체인은 `aud=artel-sdk` 만 받는데, 이 요청은 바로 그 토큰을 아직
못 가진 쪽이 부른다.

`/api/auth/sdk/tokens` 도 쓰지 않는다. `/api/auth/sdk/token` 과 글자 하나 차이인데 그쪽은
`SecurityConfig` 의 permitAll 목록에 있고 이쪽은 authenticated 라, 두 경로를 헷갈리는 것이 곧 인증
규칙을 헷갈리는 것이 된다. `/api/auth/cli-tokens` 와 나란한 이름이기도 하다.

`/api/auth/**` 아래라 `SecurityConfig` 의 `.authenticated()` 가 이미 덮는다. 쿠키 세션과 `artel_`
bearer 가 둘 다 그 체인에서 인증되므로 보안 설정에 더할 줄이 없다. 이 작업은 `SecurityConfig` 를
한 줄도 바꾸지 않는다.

### 토큰을 만드는 자리는 `SdkTokenIssuer` 하나다

`SdkAuthController.exchange` 가 하던 세 줄(`JwtService.issueSdkToken`,
`RefreshTokenService.issue`, `OAuthUserService.findProfile`)을 `SdkTokenIssuer.issueFor` 로 옮기고,
교환 경로와 새 발급 경로가 둘 다 그것을 부른다.

두 곳이 각자 만들면 audience 나 수명이 한쪽에서만 바뀌는 날이 오고, 그날 SDK 는 "어느 경로로 받은
토큰이냐"에 따라 다르게 동작한다. "발급된 토큰이 기존 SDK 토큰과 구별되지 않는다"를 단정이 아니라
구조로 만든다.

`exchange` 의 동작은 그대로다. 코드가 틀렸을 때도, 사용자가 지워졌을 때도 여전히 같은 400 이다.

### CLI 토큰으로 SDK 토큰을 발급하는 것은 허용한다

ARTEL-780 은 CLI 토큰으로 새 CLI 토큰을 만드는 것을 403(`cli_token_cannot_issue`)으로 막았다. 이
경로는 반대 판단이다. 근거는 `SdkTokenController.mint` 의 KDoc 에 적었고 요지는 셋이다.

- CLI 토큰은 브라우저 세션과 같은 경로 집합을 연다. SDK 토큰이 여는 것은 `/api/sdk` 아래 체인
  하나이고, 지금 그 아래는 인스턴스 등록·QA 캡처 티켓·근거 문서 티켓·프로젝트 목록 넷뿐이다.
  넓은 자격증명에서 좁은 자격증명이 나온다.
- CLI 토큰이 CLI 토큰을 못 만드는 이유는 범위가 아니라 폐기다. 같은 범위의 후계자를 찍어낼 수
  있으면 원본을 폐기해도 접근이 남는다.
- 폐기 문제가 여기서 완전히 사라지지는 않는다. 폐기된 CLI 토큰으로 그 전에 받아 둔 SDK 토큰은
  최대 30일(refresh 로 90일)까지 산다. 남는 것이 위의 좁은 경로 집합뿐이고 수명에 상한이 있어
  받아들인다.

### SDK 토큰 자신과 refresh 토큰은 401 이다

코드를 더하지 않아도 그렇다. 이 경로는 브라우저 체인에 있고 그 디코더는 `aud=artel-home` 만
받으므로, SDK 토큰(`artel-sdk`)도 refresh 토큰(`artel-refresh`)도 여기서 떨어진다. 열려 있으면
30일짜리 토큰이 스스로 후계자를 이어 발급해 만료가 사실상 사라진다. 테스트로 못박는다.

### 200 이지 201 이 아니다

남는 행이 없는 상태 없는 JWT 라 만들어지는 리소스가 없고, 같은 토큰을 내는
`/api/auth/sdk/token` 이 이미 200 이다. 행을 만드는 `/api/auth/cli-tokens` 가 201 인 것과 갈린다.

## 파일

- `auth/sdk/SdkTokenIssuer.kt` (신규) — SDK 토큰과 refresh 토큰이 나오는 유일한 자리
- `auth/sdk/SdkTokenController.kt` (신규) — `POST /api/auth/sdk-tokens`
- `auth/sdk/SdkAuthController.kt` — `exchange` 가 `SdkTokenIssuer` 를 부른다
- `auth/sdk/SdkTokenApiIntegrationTest.kt` (신규)

## 검증

`SdkTokenApiIntegrationTest` 아홉 개가 보는 것: 쿠키 세션 발급, `cli_token` 발급, 응답의 키 집합과
수명, 나온 토큰이 `/api/sdk/projects` 를 열고 그 사람의 프로젝트를 본다는 것, loopback 토큰과
클레임이 같다는 것, `/api/auth/sdk/token/refresh` 로 갱신된다는 것, 무인증 401, SDK 토큰 자신 401,
브라우저 refresh 토큰 401, 폐기된 CLI 토큰으로는 더 이상 발급되지 않는다는 것.

ARTEL-780 의 네 클래스(`CliTokenApiIntegrationTest`, `CliTokenExchangeApiIntegrationTest`,
`CliTokenPrincipalTest`, `SdkLoginCodeStoreIntegrationTest`)와 `AuthRefreshIntegrationTest`,
`JwtServiceTest`, `RefreshTokenServiceTest` 를 함께 돌려 아래 stack 이 그대로인지 본다.

`OpenApiDocumentationIntegrationTest` 는 `develop` 에서 이미 깨져 있다 — `#255` 로 `appUserId` 가
계약에 들어갔다. 이 작업이 고치지 않고, 더 나빠지지도 않는다(실패는 여전히 그 한 개다).
