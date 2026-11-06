# 2026-07-22 — 게임 인스턴스 및 게임 빌드 API 구현

- Date: 2026-07-22
- Jira Issue: ARTEL-75
- Status: Implemented
- Repository: orchestration-server
- Work Type: feat
- Client counterparts: ARTEL-76 (home), ARTEL-77 (sdk)

## Goal

Give a project two new child concepts and the SDK a way to attach itself to one:

1. **Game instance** — a named installation of the SDK that belongs to a project. Created from
   the dashboard, which issues a permanent `instance_key`. The key is the SDK's only credential:
   it registers with it and opens its WebSocket with it.
2. **Game build** — a `(project, version)` pair, created as a side effect of registration when the
   SDK reports the version it was built with. The dashboard lists builds and edits their
   `label`/`notes`; `version` is never editable because it is observed, not authored.

Registration replaces the current `POST /api/sdkId` flow, in which any caller could register an
arbitrary self-chosen string into an in-memory set with no owner, no project, and no persistence.

## Non-goals

- **Platforms other than `UNITY`.** The enum has one value. A second value is a schema-free change
  later; guessing its shape now is not.
- **Key rotation, expiry, revocation, or hashing at rest.** The key is stored and compared in
  plaintext. Decided deliberately with the product owner: security hardening is a later pass.
  See Risks.
- **Backward compatibility.** Nothing is deployed. `POST /api/sdkId` and `SdkIdVerificationService`
  are deleted outright — no deprecation window, no data migration, no dual-path support.
- **Multiple concurrent SDK connections per instance.** One instance, one live socket (see
  Architecture). A `game_session` table is the right answer if that changes; it is not this issue.
- **Deleting builds.** Builds are observed facts; a deleted build would reappear on the next
  registration. No `DELETE` endpoint.
- **Adopting `sdk_session_log` / `action_execution_log`.** Those V1 tables remain orphaned, as
  ARTEL-58 already recorded.

## Context

- Spring Data **R2DBC**, fully reactive (`Mono`/`Flux`). No JPA, no coroutines — ARTEL-58 rejected
  `CoroutineCrudRepository` as "the only coroutine island in the repo."
- Flyway runs on PostgreSQL in production but tests run **H2 in `MODE=PostgreSQL`**. Every
  statement must work on both: no `ON CONFLICT`, no `DISTINCT ON`, no partial indexes, no
  `gen_random_uuid()`.
- Access control lives in SQL, not in service branches. `ProjectRepository` joins `project_member`
  and filters `deleted_at IS NULL` in every query so a forgotten condition cannot leak another
  user's rows. New repositories follow that shape; new services start from
  `requireAccessible(projectId, userId)`.
- Timestamps come from the injected `java.time.Clock`, never from a SQL default.
- Ids are serialized as `String` in every DTO.
- `SecurityConfig` authenticates `anyExchange()` by default, so the project-scoped endpoints need
  no configuration. The SDK-facing ones do — see Configuration.

### Two existing defects this issue must not inherit

`SessionManager.registerSession` is a bare `sessions[sdkId] = session`
([SessionManager.kt:17](../../src/main/kotlin/kr/artel/orchestration/sdk/service/SessionManager.kt)).
Two consequences, both currently live:

1. A second connection with the same id **silently replaces** the first. The first socket is never
   closed; it stays open and unreachable.
2. `SdkWebSocketHandler`'s `doFinally { sessionManager.removeSession(sdkId) }` removes the entry
   **by key**, so when the stale first connection finally dies it evicts the second, live
   connection's entry. The SDK stays connected but becomes undeliverable.

Today the id is a per-process GUID, so collisions are rare. A permanent `instance_key` reused
across every reconnect makes reconnect the common case, so both must be fixed here rather than
left as a pre-existing condition.

## Constraints

- Migration is **V4** — next free version.
- New tables must be added to `DatabaseConnectionTest`, which asserts each migrated table is
  queryable over R2DBC.
- `docs/api-documentation.md` and `OpenApiDocumentationIntegrationTest` both enumerate every REST
  path; the repo treats them as the convention-enforcement mechanism.
- Korean Swagger strings (`@Tag`/`@Operation`/`@Parameter`), matching the project controllers, not
  the older English `SdkController`.
- Korean KDoc that explains *why* — the rejected alternative, the failure mode being prevented.

## Architecture

### Instance key

Generated server-side with `SecureRandom` over a 32-character Crockford-style alphabet
(`0-9A-Z` minus `I`, `L`, `O`, `U` to remove transcription ambiguity), 20 characters emitted as
four dash-separated groups of five: `H4KQ2-8VTRM-9XZ0C-N5JWE`. ~100 bits of entropy.

The dashboard and the Unity window both treat this as a **copy/paste** value, not a typed one, so
typeability is not the driver. The grouping and the reduced alphabet are for the moments a human
does read it — comparing the key on screen against the one pasted into Unity, or reading it to a
teammate. Base64 of 32 bytes would be 43 dense case-sensitive characters: stronger than needed and
unverifiable by eye.

The key is **re-readable** from the dashboard, not shown once. It is a durable credential the user
must be able to recover when they reinstall the SDK; a show-once key would make re-issue the
common path, and there is no re-issue endpoint in this issue.

### Session identity

The WebSocket handshake carries `instanceKey`, but the session map is keyed by **`instanceId`**,
resolved from the key during the handshake. The key stays out of the session map, out of the
agent-facing action URL, and out of the log lines that print the session id.

`/api/orchestration/action/{sdkId}` therefore becomes `/api/orchestration/action/{instanceId}`.
Verified safe: `artel-agent-server` contains no reference to `sdkId` or to that path — `AgentClient`
posts game state to `/gamestate/scene` and `/gamestate/result` with no client identifier at all.
No cross-repository change is required.

### Duplicate connection: reject the newcomer

Chosen over "evict the incumbent". If a QA session is mid-run, a stray second launch of the same
build must not silently take the socket away from it. The newcomer gets a close frame it can
surface to the developer.

`SessionManager` gains a compare-and-set registration and a compare-and-remove eviction:

- `registerSession(instanceId, session): Boolean` — `putIfAbsent`, returns `false` when occupied.
- `removeSession(instanceId, session)` — `sessions.remove(key, value)`, so a dying stale session
  cannot evict a live one.

### Build upsert without `ON CONFLICT`

H2 rules out `ON CONFLICT`. Reuse the pattern already proven in `ProjectDocumentService`: read,
insert, and `retryWhen(Retry.max(3).filter { it is DataIntegrityViolationException })`, with the
unique constraint as the real arbiter and the read re-executed on every retry.

## API Contract

Agreed shape for ARTEL-76 and ARTEL-77. All ids are JSON strings. All timestamps are ISO-8601.

### Project-scoped, JWT-authenticated

```
GET    /api/projects/{projectId}/game-instances          -> 200 { "items": [GameInstance] }
POST   /api/projects/{projectId}/game-instances          -> 201 GameInstance
       { "name": string(1..80), "platform": "UNITY" }
PATCH  /api/projects/{projectId}/game-instances/{id}     -> 200 GameInstance
       { "name": string(1..80) | null }                     null = untouched
DELETE /api/projects/{projectId}/game-instances/{id}     -> 204                soft delete

GET    /api/projects/{projectId}/game-builds             -> 200 { "items": [GameBuild] }
PATCH  /api/projects/{projectId}/game-builds/{id}        -> 200 GameBuild
       { "label": string(0..80) | null, "notes": string(0..2000) | null }
```

```jsonc
// GameInstance
{
  "id": "12",
  "projectId": "3",
  "name": "내 맥북",
  "platform": "UNITY",
  "instanceKey": "H4KQ2-8VTRM-9XZ0C-N5JWE",
  "connected": false,              // live, from SessionManager
  "lastConnectedAt": "2026-07-22T09:12:03Z",   // nullable
  "createdAt": "2026-07-22T08:00:00Z",
  "updatedAt": "2026-07-22T09:12:03Z"
}

// GameBuild
{
  "id": "5",
  "projectId": "3",
  "version": "1.2.3",              // observed, never writable
  "label": "1차 QA 빌드",           // nullable
  "notes": null,                   // nullable
  "createdAt": "2026-07-22T09:12:03Z",
  "updatedAt": "2026-07-22T09:12:03Z"
}
```

PATCH follows the established convention: `null` means untouched, `""` clears a nullable string.

### SDK-facing, unauthenticated

```
POST /api/sdk/registrations
     { "instanceKey": "H4KQ2-8VTRM-9XZ0C-N5JWE",
       "sdkUuid": "3f2a…",         // per-runtime GUID from PlayerPrefs
       "gameVersion": "1.2.3" }    // Application.version

200  { "instanceId": "12", "projectId": "3",
       "instanceName": "내 맥북",
       "gameBuildId": "5", "gameVersion": "1.2.3" }
404  { "code": "not_found", "message": "…" }        unknown or deleted key
400  { "code": "invalid_request", "fields": {…} }
```

Registration is **idempotent and repeated on every game launch** — that is how a version change
becomes a new build row. It updates `last_sdk_uuid` and `last_connected_at`.

An unknown key returns 404 rather than 401: the caller is not a user, there is no realm to
authenticate into, and 404 keeps the response identical for "never existed" and "instance deleted".

### WebSocket

```
/ws/sdk?instanceKey=H4KQ2-8VTRM-9XZ0C-N5JWE
close 4001  invalid or unknown instance key
close 4002  instance already has a live connection
```

## Approach (Checklist)

- [ ] **Step 0: Recon.** Re-read `ProjectDocumentService` (version-collision retry),
      `ProjectDocumentController` (nested sub-resource controller shape), `V3` migration,
      `ApiExceptionHandler`.
- [ ] **Step 1: Migration.** `V4__create_game_instance_and_game_build.sql`. `BIGSERIAL` PKs;
      `project_id BIGINT NOT NULL REFERENCES project (id) ON DELETE CASCADE`;
      `CONSTRAINT uk_game_instance_instance_key UNIQUE (instance_key)`;
      `CONSTRAINT uk_game_build_project_version UNIQUE (project_id, version)`;
      `idx_game_instance_project_id`, `idx_game_build_project_id`. Korean rationale block above
      each table. Add both tables to `DatabaseConnectionTest`.
- [ ] **Step 2: Entities and repositories.** `game/entity/GameInstanceEntity.kt` (with the
      `GamePlatform` enum in the same file, persisted as `String`), `GameBuildEntity.kt`;
      `GameInstanceRepository`, `GameBuildRepository`. Every read joins `project_member` and
      filters `deleted_at IS NULL` — including the by-key lookup, so a key on a soft-deleted
      instance or project stops working.
- [ ] **Step 3: Key generator.** `game/service/InstanceKeyGenerator.kt` — `SecureRandom`, the
      unambiguous alphabet, dash grouping. Unit-tested for format, alphabet, and non-repetition.
- [ ] **Step 4: Instance service and controller.** `GameInstanceService` +
      `GameInstanceController` at `/api/projects/{projectId}/game-instances`. `requireAccessible`
      first; `connected` filled from `SessionManager.hasSession(instanceId)`. Delete is soft.
- [ ] **Step 5: Build service and controller.** `GameBuildService` + `GameBuildController` at
      `/api/projects/{projectId}/game-builds`. PATCH touches `label`/`notes` only.
- [ ] **Step 6: Registration endpoint.** `SdkRegistrationService` — resolve key, upsert build with
      the retry pattern, stamp `last_sdk_uuid` / `last_connected_at`, return the response. Wire
      into `SdkController` (or a new `SdkRegistrationController`), Korean Swagger annotations.
- [ ] **Step 7: Delete the old path.** Remove `POST /api/sdkId`, `SdkIdVerificationService`,
      `SdkIdRegistrationRequest`, and the `/api/sdkId` entry in `SecurityConfig`. Six references
      total; `getAllValidSdkIds()` is already dead code.
- [ ] **Step 8: WebSocket auth.** `SdkWebSocketHandler` reads `instanceKey` from the handshake
      query, resolves it to an instance, closes 4001 when unresolved and 4002 when
      `registerSession` reports the slot occupied. Fix `SessionManager` to `putIfAbsent` /
      `remove(key, value)`. Rename the action path variable to `instanceId`.
- [ ] **Step 9: Docs.** `docs/api-documentation.md` gains the new paths and loses `/api/sdkId`;
      `OpenApiDocumentationIntegrationTest` asserts the new operations.
- [ ] **Step 10: Tests.** See Validation.

## Configuration

- `SecurityConfig`: **remove** `/api/sdkId` from the `permitAll` list, **add**
  `/api/sdk/registrations`, with the existing comment style noting it sits on the SDK/Agent trust
  boundary and is not an end-user JWT path. `/ws/sdk` stays whitelisted at the filter level; its
  auth remains inside the handler.
- No new `artel.*` properties, so `application-test.yml` needs no change.

## Validation

**Commands**

```bash
./mvnw clean test
```

**Tests to add**

- `GameInstanceCrudIntegrationTest` — create/list/rename/soft-delete; a non-member gets 404 on
  every verb; a member who is not OWNER can still list (role gating matches project rules);
  `instanceKey` is present and unique across two creates.
- `SdkRegistrationIntegrationTest` — unknown key → 404; valid key → 200 and a `game_build` row;
  re-registering the same version → same `gameBuildId`, no duplicate row; a new version → a second
  row; registration against a soft-deleted instance → 404.
- `GameBuildIntegrationTest` — list ordering (newest first); PATCH updates `label`/`notes`; a
  `version` field in the PATCH body is ignored rather than honoured.
- `SessionManagerTest` — `registerSession` returns `false` when occupied; `removeSession` with a
  stale session does not evict the live one (this is the regression test for the defect above).
- WebSocket handshake: valid key connects; unknown key closes 4001; second connection closes 4002.
- `InstanceKeyGeneratorTest` — format, alphabet, length.

Every test class clears its own tables in `@BeforeEach` in FK-safe order; reactive `@Transactional`
does not roll back in tests, as `ProjectCrudIntegrationTest` documents.

**Expected output:** `BUILD SUCCESS`, no Flyway checksum warnings, `DatabaseConnectionTest` green
for both new tables.

## Risks & Rollback

- **The key is a bearer credential in a query string.** It lands in server access logs and in any
  proxy in front of the socket. Accepted for now by explicit product decision; the mitigation that
  costs nothing today is keeping it out of the session map and the action URL, which the
  `instanceId` design already does. Header- or subprotocol-based auth is the follow-up.
- **Reject-on-duplicate can strand a user** if a socket half-dies and the server has not noticed:
  the developer relaunches and is refused until the dead session times out. Mitigated by the
  `remove(key, value)` fix (a dying session now reliably frees its own slot) and by the 4002 close
  reason being explicit enough to act on. If it proves annoying in practice, a liveness ping or an
  explicit "force disconnect" dashboard action is the escalation — not silent eviction.
- **`connected` is single-instance truth.** `SessionManager` is an in-memory map; with more than
  one orchestration server replica the flag is wrong and 4002 stops being enforceable. Not a
  problem today (single replica), and out of scope, but it is the first thing to break on scale-out.
- **Rollback:** the feature is additive plus one deletion. `git revert` the commit; V4 must then be
  dropped manually in any environment where it ran (`DROP TABLE game_build, game_instance`).
  Nothing is deployed, so this is a development-time concern only.

## Deferred Work

- Key rotation / revocation / re-issue endpoint.
- Hashing keys at rest and accepting them via header rather than query string.
- `game_session` table if concurrent connections per instance become a requirement.
- Platform values beyond `UNITY`.
- Adopting or dropping `sdk_session_log` and `action_execution_log`.

## Open Questions

- Build list ordering: newest-created first is assumed. Semantic version ordering would need a
  parse the server cannot guarantee (`version` is a free-form Unity string), so it is not proposed.
- Should `GET /api/projects/{projectId}/game-instances` include `instanceKey` for every row, or
  only on the detail/create response? Included everywhere in this draft, on the grounds that the
  dashboard is already an authenticated, member-only surface and hiding it would only add a
  round-trip.
