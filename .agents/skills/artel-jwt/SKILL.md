---
name: artel-jwt
description: >-
  Mint a JWT that artel-orchestration-server accepts, so an API call or a
  browser screen is authenticated without going through GitHub OAuth. Use when
  the task needs a session token, an `Authorization: Bearer` header, the
  `artel_access_token` cookie, an SDK token, a logged-in screen capture of
  artel-home or admin-page, or when the user says "JWT 만들어줘", "토큰 필요해",
  "로그인된 화면 캡처", "인증된 상태로 API 호출".
---

# ARTEL JWT

## What this is for

`artel-orchestration-server` signs its own sessions with HS256 over
`artel.auth.jwt-secret`, and the decoders in `auth/config/SecurityConfig.kt`
check three things only: the signature, `iss`, and `aud` (plus expiry). Nothing
in the request path calls GitHub. So a token minted with the same secret is
indistinguishable from one the server issued at login.

That removes the two places OAuth gets in the way:

- calling a protected API with `curl` or from a test, and
- taking a screen capture of artel-home or admin-page in a logged-in state.

## Mint one

```bash
.claude/skills/artel-jwt/mint-jwt.py --sub <app_user.id> [--format ...]
```

The secret is resolved in this order: `--secret`, `$ARTEL_JWT_SECRET`, then the
orchestration server's `.env`, which is searched for upward from the script (its
own repository when installed there, the sibling submodule otherwise; override
with `--env-file`). `ARTEL_JWT_ISSUER`, `ARTEL_JWT_AUDIENCE`, `ARTEL_JWT_TTL` and the
cookie names are read from the same places, so a token minted with no flags
matches whatever the local server is running with.

**`--sub` must be an existing `app_user.id`.** The token is authoritative about
identity only; the profile is read from the database
(`auth/service/SessionUserResolver.kt`), and a `sub` that is not a number is
rejected outright. Pick a real row:

```bash
docker exec artel-local-postgres psql -U postgres -d artel \
  -c 'select id, nickname, display_name from app_user order by id limit 10'
```

## The four tokens

The audience decides which filter chain accepts the token, and each chain has a
decoder built for one audience only. A browser session token is rejected by the
SDK chain and the reverse, by design.

| `--audience` | `aud` | Default TTL | Accepted by | Extra claims |
|---|---|---|---|---|
| `home` (default) | `artel-home` | 15m | `/api/**`, cookie or bearer | none required |
| `sdk` | `artel-sdk` | 30d | `/api/sdk/**`, bearer only | none |
| `refresh` | `artel-refresh` | 14d | `/api/auth/refresh`, `/api/auth/sdk/token/refresh` | `for` |
| `tracker-setup` | `artel-tracker-setup` | 15m | the GitHub App install `state` parameter | `project_id`, `provider` |

The script fills the extra claims, because a token without them passes the
decoder and is then rejected by the service that reads it.

- `--refresh-for home|sdk` sets `for`, the audience this refresh token may buy an
  access token for (`RefreshTokenService.REFRESH_TARGET_CLAIM`). A browser
  refresh token cannot buy an SDK token; that split is the point of the claim.
- `--project-id` is required for `tracker-setup`, and `--provider` names the
  `TrackerProvider` (only `GITHUB` exists). The signature over the project id is
  what stops someone attaching their own installation to another project.

## There is no admin token

admin-page carries the **same `artel-home` session** as artel-home — same
cookie, same audience, same `apiFetch(credentials: 'include')`. Nothing about
the token says admin.

The admin grade lives in `app_user.platform_role` (`USER` / `DEVELOPER`) and is
read from the database on every call, deliberately not carried in the token —
`PlatformAccessService` explains why: a claim would keep a demoted user seeing
everything until the token expired. So minting a token with a role claim buys
nothing. Set the column instead, which has no screen or API:

```sql
UPDATE app_user SET platform_role = 'DEVELOPER' WHERE id = <app_user.id>;
```

`DEVELOPER` opens read access to projects the user is not a member of, and
nothing else — writes still require a `project_member` row. See
`artel-orchestration-server/docs/platform-role.md`.

## Calling an API

Both the `Authorization` header and the cookie work — `cookieTokenConverter`
reads the header first and falls back to the cookie.

```bash
TOKEN=$(.claude/skills/artel-jwt/mint-jwt.py --sub 1 --ttl 8h)
curl -sS -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/auth/me
```

`--format curl` prints that whole command for you, and `--format header` prints
just the header line.

`/api/auth/me` returning the profile of `--sub` is the check that the token,
the secret and the server all agree. A 401 means the secret or the audience is
wrong; an empty or error body with 200 means the `app_user` row is missing.

## Taking a screen capture in a logged-in state

The frontends send the session cookie with `credentials: 'include'` and hold no
token of their own, so setting the cookie is the whole login.

The server marks the cookie `httpOnly` when it sets it, but it only ever reads
the value back — a cookie written from the console is accepted the same way.

```bash
.claude/skills/artel-jwt/mint-jwt.py --sub 1 --ttl 8h --format browser
```

That prints a one-liner to paste into the DevTools console on the page:

```js
document.cookie = 'artel_access_token=<token>; path=/; SameSite=Lax'; location.reload();
```

For a driven browser, `--format playwright` prints a cookie array ready for
`context.addCookies()`; pass `--origin http://localhost:5173` (artel-home) or
`http://localhost:5174` (admin-page) so the domain matches.

Give it a TTL longer than the 15-minute default — `--ttl 8h` — or the session
expires in the middle of a capture run.

Both frontends also need the server to allow their origin, which is a separate
setting: `ARTEL_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:5174`.
Without it the browser is blocked by CORS before the cookie ever matters. See
`artel-orchestration-server/.agents/docs/local-stack.md`.

## Other flags

- `--claim KEY=VALUE` adds or replaces any claim; the value is parsed as JSON
  when it can be, so `--claim 'scope=["a","b"]'` gives an array. Repeatable.
  Nothing on the server reads a claim it was not built to read — for admin
  access see the section above.
- `--ttl` accepts `900`, `15m`, `8h`, `30d`, and ISO-8601 `PT15M` / `P14D`.
- `--aud` and `--issuer` override the resolved values outright, for reproducing
  a rejection on purpose.
- `--decode <token>` prints the header and payload of an existing token and says
  how long it has left. Use it on a token pulled from a browser or a log.
- `--format json` prints the token with its claims and cookie name together.

## Inside the server's own tests

Do not use this script there. `JwtService` is a bean, so an integration test
injects it and calls `jwtService.issue(user)` directly — see
`ProjectCrudIntegrationTest.kt`. The script is for the outside of a running
server.

## Local only

**This mints sessions for a local server and nothing else.** `--origin` refuses
any host but `localhost` / `127.0.0.1`.

The reason is the secret. Signing a token that a deployed server accepts means
holding that server's secret on this machine, and anyone holding it can mint a
session for any user id — there is no per-token revocation to fall back on. A
local `.env` holds a throwaway secret and this script is the intended way to use
it; a deployed one should never reach a developer machine, a command line, a
commit, an issue, or a pull request.
