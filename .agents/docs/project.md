# Project Context

Fill this document during project initialization. Agents must verify commands against repository configuration before running them.

## Overview

- Product: artel-orchestration-server
- Primary users: TODO
- Core domain: TODO
- Runtime environment: TODO

## Architecture

- Entry points: TODO
- Main modules: TODO
- Dependency direction: TODO
- External systems: GitHub repository `project-artel/artel-orchestration-server`; Jira project `ARTEL` via the `mcp-atlassian` MCP server; Insomnia collection repository `project-artel/insomnia-api`
- Persistent data: TODO

## API 표면과 신뢰 경계

이 앱은 공개 API와 무인증 내부 API를 함께 서빙한다. 둘을 가르는 것은 경로 접두사이고,
그 접두사는 **서로 다른 포트**에 실린다.

- `/api/` 하위 — 엔드유저 API. JWT 인증 대상. 공개 포트(8080)
- `/internal/` 하위 — 서버-투-서버. agent-server가 부르며 인증이 없다.
  내부 포트(기본 8081)에만 존재한다
- `/ws/sdk`, `/ws/viewer` — WebSocket. 공개 포트. `/ws/sdk`는 핸드셰이크가 쿼리
  파라미터로 토큰을 실어 `SdkWebSocketHandler`가 직접 검증한다
- `/oauth2/`, `/login/oauth2/`, `/v3/api-docs/`, `/swagger-ui/` 하위 — 로그인 흐름과
  문서. 공개 포트

규칙 셋:

1. 새 서버-투-서버 라우트는 `/internal` 아래에 붙인다. `SecurityConfig`의 permitAll
   목록에 개별 경로를 추가하지 않는다 — `/internal` 한 줄이 이미 그것을 덮는다.
2. 무인증 라우트를 `/api/` 아래 두지 않는다. 그렇게 하면 permitAll 목록이 다시
   갈라지고, 그 경로가 공개 포트에 실려 인터넷에 노출된다.
3. `/internal` 경로는 8080에 존재하지 않는다. 별도 내부 포트에서만 서빙되므로, 공개
   호스트 설정을 잘못 만져도 그 경로가 노출될 수 없다. 다만 리버스 프록시도 같은
   `app-net`에 있어 내부 포트에 **닿을 수는 있다** — 보장의 근거는 네트워크가 아니라
   "그렇게 설정하지 않는다"이다. 반대로 내부 포트에는 엔드유저 API·로그인 흐름·
   WebSocket이 없다. 이 분리는 같은 ApplicationContext에서 조립한 두
   개의 `HttpHandler` 체인이 강제한다(`config/InternalApiConfig.kt`,
   `config/InternalApiServer.kt`). 실질적인 외부 차단은 내부 포트를 호스트에 게시하지
   않는 배포 구성이 맡으며 `docs/deployment.md`가 근거를 담는다.

경로가 비슷해 헷갈리는 쌍이 있다. `/internal/test-case-spec`은 Agent가 명세를 밀어
넣는 무인증 경로이고, `/api/projects/{projectId}/test-case-spec/download`은 사용자
다운로드로 인증 대상이다. 이름이 겹쳐도 신뢰 모델은 정반대이며, 이제 뜨는 포트조차
다르다.

## Commands

| Purpose | Command |
|---|---|
| Install dependencies | TODO |
| Run locally | TODO |
| Format | TODO |
| Lint | TODO |
| Type-check | TODO |
| Unit tests | TODO |
| Integration tests | TODO |
| Build | TODO |
| Set up Jira credentials | `cp .jira.env.example .jira.env` |

Jira access goes through the `mcp-atlassian` MCP server, declared in `.mcp.json`
at the repository root. Claude Code starts it on demand and asks for approval
the first time it connects.

Credentials live in `.jira.env`, which the server reads through `--env-file`.
Copy `.jira.env.example` and fill in `JIRA_URL`, `JIRA_USERNAME`, and
`JIRA_API_TOKEN`, issuing the token at
`https://id.atlassian.com/manage-profile/security/api-tokens`. `.gitignore`
excludes `.jira.env`; never commit it.

The server reads that file itself, so the setup does not depend on how Claude
Code was launched or on which shell exports the variables. Do not register a
`jira` server in user scope as well, or two copies start.

### Insomnia collections

API collections live in `project-artel/insomnia-api`, one YAML file per
repository (`orchestration-server.yaml` for this one), and reach people through
Insomnia's git sync. Publish changes with the `insomnia-sync` skill: it derives
the API surface from the springdoc contract at `/v3/api-docs`, writes the
collection file, and opens a PR.

Do not publish by writing into a local Insomnia app — neither through the
`insomnia` MCP server's write tools nor by editing the `insomnia.*.db` NeDB
store. Either way only one machine changes and no reviewable diff exists.
Reading local state is fine.

Environment variables are committed alongside the requests, so every consumer
gets working URLs on pull. Credentials are excluded: `access_token` for the
authenticated `/api/test-scenario/**` paths stays in an Insomnia private
environment. The collection repository is currently public.

## Constraints

- Supported platforms:
- Compatibility requirements:
- Performance constraints:
- Security or privacy requirements:

## Ownership

- Maintainers:
- Sensitive modules:
- Changes requiring explicit review:
