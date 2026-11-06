# 2026-07-19 — ARTEL-44 OAuth Login and JWT Authentication

- Date: 2026-07-19
- Jira Issue: ARTEL-44
- Status: In Progress
- Repository: `artel-orchestration-server`
- Work Type: `feat`

## Goal

Authenticate Artel Home users through OAuth providers and issue a short-lived JWT session after a successful provider login. GitHub is the only enabled provider in this issue, while the code must allow additional OAuth providers without changing the shared JWT or session flow.

## Non-goals

- Local username/password authentication.
- User registration, account administration, or roles.
- Replacing the existing Unity SDK `sdkId` authorization or defining Agent Server service authentication.

Scope change (2026-07-21): the data model was split into `app_user` + `oauth_identity` so one user
can hold several provider identities. The *linking flow* (attaching a second provider while signed
in) remains out of scope; only the schema and the identifier contract that make it possible landed here.

## Architecture

1. Spring Security starts the provider flow at `/oauth2/authorization/{registrationId}`.
2. A provider-specific `OAuthIdentityMapper` normalizes provider attributes into `OAuthIdentity`.
3. The shared OAuth success handler upserts the normalized user through JPA on a bounded-elastic scheduler.
4. Flyway V2 owns the PostgreSQL `app_user` table and the `oauth_identity` table that links provider
   accounts to it (1:N), with uniqueness on `(provider, provider_user_id)`. An unseen provider account
   always creates a new `app_user`; identities are never auto-linked by email.
5. The server creates a JWT containing provider-neutral identity claims.
6. The server sends the JWT in a Secure, HttpOnly, SameSite=Lax cookie and redirects to Artel Home.
7. `/api/auth/me` validates the cookie and returns the minimal user view; `/api/auth/logout` clears it.
8. Bearer headers remain supported for non-browser API clients.

Adding another provider requires only its Spring client registration and a new `OAuthIdentityMapper` implementation. The JWT service, cookie handling, controller, and Home session contract stay unchanged.

## Claims

- Standard: `sub`, `iss`, `aud`, `iat`, `exp`.
- Normalized identity: `provider`, `login`, `name`, and optional `avatar_url`.
- `sub` is the `app_user` id. It stays stable as further providers are linked to the same user, and
  it carries no provider prefix — clients must treat it as opaque.

## Approach (Checklist)

- [x] Add Spring Security OAuth2 client and resource-server dependencies.
- [x] Add typed authentication configuration for frontend URL, JWT settings, cookie policy, and GitHub OAuth credentials.
- [x] Implement provider-neutral `OAuthIdentity`, `OAuthIdentityMapper`, and `OAuthIdentityResolver` abstractions.
- [x] Implement `GitHubOAuthIdentityMapper` as the first provider adapter.
- [x] Add Flyway V2 for the OAuth user table and provider/login index.
- [x] Add the JPA entity, repository, and transactional upsert service.
- [x] Persist OAuth users without blocking the WebFlux event loop.
- [x] Implement signed JWT issuance and validation with issuer, audience, signature, and expiration checks.
- [x] Implement common OAuth success/failure handlers and secure JWT cookie delivery.
- [x] Implement `/api/auth/me` and `/api/auth/logout`.
- [x] Preserve public SDK WebSocket and Agent Server routes according to their existing trust boundaries.
- [x] Add unit coverage for GitHub normalization and JWT claims.
- [x] Add integration coverage for unauthenticated and authenticated `/api/auth/me` behavior.
- [x] Run the full Maven test suite in a Dockerized JDK 21 environment.
- [ ] Verify the real GitHub callback flow after OAuth credentials are configured.

## Configuration

- `GITHUB_CLIENT_ID`
- `GITHUB_CLIENT_SECRET`
- `ARTEL_JWT_SECRET` — at least 32 bytes
- `ARTEL_HOME_URL` — defaults to `http://localhost:5173`
- `ARTEL_JWT_ISSUER` — defaults to `artel-orchestration-server`
- `ARTEL_JWT_AUDIENCE` — defaults to `artel-home`
- `ARTEL_JWT_TTL` — defaults to `PT15M`
- `ARTEL_AUTH_COOKIE` — defaults to `artel_access_token`
- `ARTEL_SECURE_COOKIE` — defaults to `true`; set `false` only for local HTTP development

The GitHub OAuth callback URL is `{orchestration-origin}/login/oauth2/code/github`.

## Validation

- `./mvnw test`
- `./mvnw clean package`
- Verify GitHub login success and failure redirects.
- Verify the JWT cookie is HttpOnly, Secure in deployed environments, SameSite=Lax, and absent from URLs and logs.
- Verify `/api/auth/me` returns `401` without a valid session and normalized identity with one.
- Verify logout clears the cookie.
- Re-run the existing SDK WebSocket integration flow.

## Risks / Mitigations

- **Provider claims differ:** isolate parsing in provider-specific mappers and test every mapper independently.
- **Provider user IDs collide:** namespace JWT subjects with the registration ID.
- **JWT theft:** keep TTL short, use TLS and an HttpOnly cookie, and never expose the token to Home JavaScript.
- **CSRF:** use SameSite=Lax, explicit CORS origins, and credentialed requests only from Artel Home. Reassess CSRF tokens if cross-site embedding or broader cookie policies are introduced.
- **SDK/Agent regression:** keep those routes outside end-user JWT protection and cover them with regression tests.

## Deferred Work

The explicit provider-linking flow (endpoints and Home UI for attaching a second provider to a
signed-in user), user authorization, refresh-token rotation, session revocation, and additional
provider registrations belong in separate issues.

## Open Questions

- **Cookie policy vs deployment topology.** The JWT cookie is `SameSite=Lax`. If Home and the
  orchestration server end up cross-site, the cookie will not ride along on credentialed `fetch`
  and `/api/auth/me` will always return 401. Local development hides this because both sides are
  `localhost`. Confirm the production domains; cross-site would require `SameSite=None; Secure`
  and a fresh look at CSRF, which is globally disabled today.
- **First-login race.** `OAuthUserService.upsert` reads then writes, so two concurrent first logins
  for the same provider account can collide on `uk_oauth_identity_provider_identity` and bounce one
  request to `/login?error=server`. The window is narrow; consider a constraint-violation retry or
  an `ON CONFLICT` upsert.
