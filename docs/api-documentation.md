# API Documentation

`artel-orchestration-server` publishes a machine-readable OpenAPI contract and interactive Swagger UI through Springdoc.

## Local access

Run the application:

```bash
./mvnw spring-boot:run
```

Then use:

| Path | Purpose |
|---|---|
| `/swagger-ui.html` | Interactive Swagger UI |
| `/v3/api-docs` | OpenAPI JSON contract |

Both live on the public port (8080). The internal port (8081, `ARTEL_INTERNAL_API_PORT`)
serves `/internal/**` and nothing else, so requesting the docs there returns 404. The
contract still documents the `/internal/**` endpoints — it describes the whole
application, not one port. See `docs/deployment.md` § Ports.

## Documented API surface

The route list is not repeated here. It lives in [`api/openapi.json`](api/openapi.json) —
115 paths and 136 operations at the time of writing.

`ARTEL_SECRETS_KEY` encrypts the OpenRouter, AWS Bedrock, Claude and TypeSafe API keys saved by `PUT /api/admin/settings/llm`, `PUT /api/admin/settings/bedrock`, `PUT /api/admin/settings/anthropic` and `PUT /api/admin/settings/typesafe`, and the keys users save with `PUT /api/auth/me/llm-keys/{service}`. None of the keys is read from the environment (`OPENROUTER_API_KEY` is no longer read), so an install enters them on the admin page. Blank is allowed: the server starts and all of those `PUT`s answer 409 `secrets_key_missing`. A non-blank value must be at least 32 characters and must not contain a placeholder (`change_me`, `change-me`, `changeme`, `replace-with`, `replace_with`, `your-secret`, `secret`, case-insensitive), otherwise the server refuses to start. Generate one with `openssl rand -hex 32`; keep it separate from `ARTEL_JWT_SECRET`. The value is never printed in the error.

`GET /api/admin/settings/models` and `PUT /api/admin/settings/models` (ADMIN only) read and set which LLM the scenario authoring agent uses (`scenarioAuthoringModel`) and which LLM answers the Jev decision questions (`decisionModel`). Both fields are always sent in `PUT`; `null` deletes the setting. A value must be an enabled `llm_model.id`, otherwise 400 `llm_model_not_found` or `llm_model_disabled`; `typesafe/jev-1.13` is also accepted for `decisionModel`. `scenarioAuthoringFallback` in the response is what is used while `scenarioAuthoringModel` is null (`ARTEL_AGENT_MODEL`, or null for the agent-server default), and a null `decisionModel` means Jev. The agent-server reads both values from `GET /internal/settings/models`.

`GET /api/admin/settings/typesafe` and `PUT /api/admin/settings/typesafe` (ADMIN only) store the TypeSafe API key that calls Jev directly. The response carries `configured`, `maskedKey` and `updatedAt`, never the key. The agent-server reads the key as `typesafeApiKey` from `GET /internal/settings/llm`.

That file is generated, not written. `OpenApiSnapshotTest` boots the application, reads
`/v3/api-docs`, and overwrites the snapshot, so `./mvnw test` refreshes it and a diff in
that file means the contract moved.

This section used to enumerate about 25 of them by hand, and a hand-copied list goes stale
without anything failing. By the time it was cut it described two routes that no longer
exist (`/api/test-scenario/{clientId}/stream` and `/api/test-scenario/{clientId}/message`,
both replaced by the `/api/projects/{projectId}/test-runs/{runId}/chat/**` paths) and
called `POST /api/sdk/registrations` unauthenticated, when it requires an `aud=artel-sdk`
Bearer token like everything else under `/api/sdk/**`.

WebSocket communication at `/ws/sdk` and `/ws/viewer` is not an HTTP request/response
contract. Keep its message format documented separately; OpenAPI only covers the REST
endpoints. Those contracts are `docs/streaming-protocol.md`,
`docs/capability-write-frames.md`, and `docs/screen-selector-frames.md`.

The socket authenticates with the same instance key, passed as the `instanceKey`
query parameter. The server closes with `4001` when the key matches no live
instance and with `4002` when that instance already has a connection — one
instance holds one socket, and the newcomer is refused rather than displacing
the incumbent. It closes with `4003` when the token's account is blocked, with
the same code the HTTP API would answer 403 with as the close reason:
`account_disabled` or `password_change_required`.

`GET /api/qa-runs/{qaRunId}/changes` lists every knowledge entry and macro the run created, updated, or deleted, one item per target: the state before the run's first event against the state after its last event, with `before` / `after` as plain text for a line diff. `POST /api/qa-runs/{qaRunId}/changes/{kind}/{targetId}/revert` (`kind` is `knowledge` or `macro`) appends an event that undoes the run's change and answers with the updated item. It answers 404 when the run made no change to that target and 409 when the item is not revertible, with `code` set to `superseded` (a later event exists on that target), `already_reverted`, or `before_unknown` (the content before the run was never recorded). The same three values appear upper-cased in each item's `notRevertibleReason`.

### Project QA run lists

Two read endpoints list a project's QA runs, newest first. A non-member gets 404 from both.

| Endpoint | Query | Response |
| --- | --- | --- |
| `GET /api/projects/{projectId}/qa-runs` | `limit` (default 20), `testRunId` | `QaRunSummaryListResponse` — the QA launch screen and QA history |
| `GET /api/projects/{projectId}/game-builds/{gameBuildId}/qa-runs` | `size` (1–100, default 20; 400 outside) | `QaRunListResponse` — runs on that build, each with `gameBuildId` and `tries` |

The build list was `GET /api/projects/{projectId}/qa-runs?gameBuildId=` until ARTEL-1155. That form no longer exists: the project path ignores `gameBuildId` and answers with the QA launch list. A non-numeric `gameBuildId` in the build path is 400.

### User LLM keys

A signed-in user can save their own Claude (`anthropic`), OpenAI (`openai`) and OpenRouter (`openrouter`) key and run QA on it instead of the platform key.

| Request | Response |
|---|---|
| `GET /api/auth/me/llm-keys` | 200 `[{service, keyHint, verifiedAt}]`, saved keys only |
| `PUT /api/auth/me/llm-keys/{service}` `{apiKey}` | 200 `{service, keyHint, verifiedAt}`; replaces an existing key |
| `DELETE /api/auth/me/llm-keys/{service}` | 204, also when nothing was saved |

`keyHint` is the first 6 characters, `…`, and the last 4. No response carries the key itself. `PUT` makes one read call with the key before saving it, never a model call: `GET {ARTEL_ANTHROPIC_BASE_URL}/v1/models` for `anthropic`, `GET {ARTEL_OPENAI_BASE_URL}/v1/models` for `openai`, and `GET {ARTEL_OPENROUTER_BASE_URL}/key` for `openrouter` (OpenRouter's `/models` answers 200 to any key). A 200 or 429 saves the key, encrypted with `ARTEL_SECRETS_KEY`. Otherwise `PUT` answers:

| Status | `code` | When |
|---|---|---|
| 400 | `invalid_request` | unknown `service`, blank key, or the key does not start with `sk-ant-` / `sk-` / `sk-or-` |
| 400 | `llm_key_invalid` | the service answered 401 |
| 400 | `llm_key_permission_denied` | the service answered 403 |
| 503 | `llm_key_check_unavailable` | any other status, a connection error, or a timeout |
| 409 | `secrets_key_missing` | `ARTEL_SECRETS_KEY` is blank |

`GET /api/qa-models` lists its platform entries (`keySource: "platform"`) first and appends one user entry (`keySource: "user"`) per enabled model whose route takes a user key: `claude/` → `anthropic`, `gpt/` → `openai`, any other non-`bedrock/` id → `openrouter`. A user entry carries `keyProvider`, `keyRegistered` and `keyHint`, and is listed even when the key is not saved. A platform entry appears only when the route has a platform key: `gpt/` models (the OpenAI API route) never, because there is no platform OpenAI key; `claude/` models only while the admin Claude API key (`PUT /api/admin/settings/anthropic`) is stored; OpenRouter and Bedrock models as before. The four `claude/` models are enabled by default so that they can run on a user key. Every entry carries `pricing: {inputPerMtok, outputPerMtok}`, or `null`.

`POST /api/qa-runs` and `POST /api/qa-tries` take an optional `keySource` (`platform` by default). `user` without a saved key for the model's service, `user` on a `bedrock/` model, `platform` on a `gpt/` model, and `platform` on a `claude/` model without the admin Claude API key all answer 400 `llm_key_missing`. The run records it in `qa_run.key_source` and `qa_try.key_source`. A `user` run sends the decrypted key to the agent-server in the session-open body as `"llm_key": {"service": ..., "api_key": ...}`; a `platform` run sends no `llm_key`. `POST /internal/llm-usage` takes an optional `keySource` per record and stores it in `llm_usage.key_source` (`platform` when absent).

### Scenario execution mode

A scenario carries `execution_mode`, either `ORDERED` (the default for a new scenario) or `FREE`. A `PUT` or approve request that leaves it out of `draft` keeps the scenario's current value, so a client that does not know the field cannot turn a `FREE` scenario into an `ORDERED` one. An `ORDERED` scenario is an ordered list of steps that the QA agent runs in order. A `FREE` scenario is a bundle of test cases: one step per test case, each step needs only `case_id`, and the QA agent decides the order and how to verify each one. The field is named `execution_mode` in the `draft` of `PUT` and approve requests, in `payload` of a scenario read, in project scenario list items, in the scenario library items, in each `scenarios` item of a run coverage response, and in the scenario the server sends to the QA agent. Saving a `FREE` draft (autosave or approve) answers 400 with `code` `invalid_free_scenario` when a step has no `case_id`, a `case_id` appears twice, or a step has a `step_kind` other than `ACTION`; an empty step list is accepted.

The authoring chat of a test run creates and updates `FREE` scenarios as well. Each scenario in a `submit_scenario` frame from the agent, in the proposals (`scenarios`) of the chat SSE `result` event, and in the `scenarios` of `POST /api/projects/{projectId}/test-runs/{runId}/scenarios/commit` carries `execution_mode`. When it is missing, a new scenario is `ORDERED` and an update keeps the stored value of the scenario it updates (found by `scenario_id`, or by a title already in the run); an update that names a different value changes the mode. The proposals of the SSE `result` event always carry the value. Each item of `current_scenarios`, sent to the agent when a session opens and with every turn, carries the stored `execution_mode`. A `FREE` scenario is saved with its steps as given: the code splitting, bridge insertion, case operation filling and opening note that rewrite `ORDERED` steps do not touch it. A `FREE` scenario that breaks the rules above is not saved: a `submit_scenario` frame gets `accepted: false` with the rule in `detail`, and the commit answers 400 with `code` `invalid_free_scenario`. Gap filling and merge absorption still leave `FREE` scenarios unchanged.

### Game notes

Per-project notes a game's makers write for the agent-server planner, plus the genre modules (`ui-flow`, `turn-based`) to attach to its prompt. Every save that changes something is a new row in `project_game_note`; rows are never updated.

| Request | Response |
|---|---|
| `GET /api/projects/{projectId}/game-notes` | 200 `{version, body, genres, updatedAt, updatedBy: {id, displayName}}`, the latest version. Without notes: `{"version": 0, "body": "", "genres": [], "updatedAt": null, "updatedBy": null}` |
| `PUT /api/projects/{projectId}/game-notes` `{body, genres, baseVersion}` | 200, the new latest version |

Any project member may read and save, as with `PATCH /api/projects/{projectId}`; anyone else gets 404. `PUT` normalizes `\r\n` to `\n`, trims the body, and stores `genres` in the order listed above, whatever order they were sent in. When the result equals the latest body and genre set, nothing is saved and the latest is returned unchanged. `baseVersion` must be the `version` the editor started from (0 for none): otherwise, or when another save takes the same version first, `PUT` answers 409 `game_notes_conflict`. A body over 20000 characters, an unknown or repeated genre, or a missing field is 400 `invalid_request` with `fields`.

`POST /api/qa-runs` and `POST /api/qa-tries` read the latest version when they open the agent-server session and send it in the session-open body as `"game_notes": {"version": ..., "body": ..., "genres": [...]}`, or `"game_notes": null` when the project has none.

### Developer macros

A game developer writes macros in the macro DSL from their own coding agent, through `artel-macro-mcp`, and stores them per project. These are not the macros the QA agent registers on a build's `content_map` (`/api/projects/{projectId}/game-builds/{gameBuildId}/macros`): a developer macro belongs to the project, its name is unique within the project, and it records the build it was last written against. Rows live in `developer_macro` (V116).

| Request | Response |
|---|---|
| `GET /api/macros/grammar` | 200 `{grammar, rules}` |
| `GET /api/projects/{projectId}/developer-macros` | 200 `{items: [summary]}`, by name ascending |
| `GET /api/projects/{projectId}/developer-macros/{name}` | 200 detail |
| `POST /api/projects/{projectId}/developer-macros:check` `{name, source, gameBuildId}` | 200 `{ok, parameters, docstring, rejection}` |
| `PUT /api/projects/{projectId}/developer-macros/{name}` `{source, gameBuildId}` | 200 detail |
| `DELETE /api/projects/{projectId}/developer-macros/{name}` | 204 |
| `GET /internal/projects/{projectId}/developer-macros` | 200 `{items: [detail]}`, internal port only |

A summary is `{name, parameters, docstring, gameBuildId, buildVersion, buildLabel, version, updatedAt, updatedBy}`; a detail adds `source`. `parameters` is the entry `def`'s parameter names in order, as strings. `gameBuildId` is a string, like `GameBuildResponse.id`, and `buildVersion` / `buildLabel` are that build's. `updatedBy` is `{id, nickname}` with `id` a string `app_user.id`, or `null` once that user is deleted. `version` starts at 1 and goes up only when the stored definition changes: putting the same source again, or the same source against another build, keeps it.

`grammar` is the agent-server's `GET /internal/macros/grammar` body, `{grammar, rules}` (`grammar` is the DSL description in markdown, `rules` the developer macro rules as sentences), passed through with its content type and not parsed here. It is not tied to a project, so it has no member check.

`gameBuildId` in a request is a string of digits; a JSON number is also accepted. It must be a build of the same project. `name` (in the `:check` body or the `PUT` path) is a Python identifier of at most 200 characters, `source` at most 20,000 characters.

`:check` and `PUT` send `{name, source, imports}` to the agent-server's `POST /internal/macros/check`, where `imports` maps the name of every other developer macro in the project to its source, so `from macros import ...` resolves against them. Nothing is stored by `:check`. A check that fails is not an HTTP error there: it is 200 with `ok: false`, `parameters` and `docstring` null, and `rejection: {line, reason}` (`line` counts from 1, null when the reason has no line). A pass is `ok: true` with `parameters` and `docstring`, and `rejection: null`. `PUT` runs the same check and stores what the agent-server returned (definition, parameter names, docstring), never what the client claims. A docstring longer than 2,000 characters is a rejection with no line.

| Status | `code` | When |
|---|---|---|
| 400 | `invalid_request` | a field fails validation; `fields` names it |
| 400 | `developer_macro_rejected` | `PUT` only: the check rejected the source; `fields` carries `reason` and, when known, `line` (a string, since `fields` is a string map) |
| 404 | `not_found` | not a member of the project, the build belongs to another project, or no developer macro by that name (`GET` detail, `DELETE`) |
| 503 | `agent_unavailable` | the agent-server did not answer within `artel.agent.macro-timeout` (default 10s), answered an error, or answered outside the contract |

The internal list is for the agent-server's planner–executer engine and carries `source`. It has no authentication, like everything under `/internal/`, and answers 404 for a deleted project.

### Game state (`STATE_SET`)

The server keeps the last observed state of every game instance in memory (`qa/state/StateSet`), built from the SDK's `pulse` frames before they are relayed to the agent-server. The contract is `artel/docs/architecture/2026-10-29-state-set-contract.md`; this is its v0.1, which needs no SDK change.

| Request | Response |
|---|---|
| `POST /internal/qa-tries/{qaTryId}/state` with an optional `ObservationSpec` body | 200 `StateSnapshot` of the try's game instance, internal port only |

`ObservationSpec` is `{scopes, paths, members, statics, includeInactive, includeStale, maxEntities}`. A null field does not filter. `scopes` takes `runtime` (statics), `persistent` (DontDestroyOnLoad), `scene` (the active scene), `scene:*` (every scene) or an exact scope such as `scene:MapScene#2`. `paths` are object path prefixes. `members` and `statics` are `Type::member` names. `maxEntities` defaults to 400.

`StateSnapshot` carries `stateVersion` (one per applied `pulse`), `streamId` (`s1`, `s2`, … one per SDK connection), `sync` (`AWAITING_WHOLE` until the connection's first whole `pulse`, then `SYNCED`), the last `reading`, `frame` and `scene`, `coverage`, `entities`, `statics`, `unobserved` (requested names seen nowhere in the selected range), `omittedEntities` and `ignoredDeltas`. Every value is an `Observation` with `status` `KNOWN`, `UNREAD` (with `reason`), `UNKNOWN` or `STALE`, plus `observedFrame`, `sourceReading` and `receivedAt`. A value the game read as null is `KNOWN` with `value: null`.

The state belongs to the game instance, not the try: a later try on the same instance reads the newer state. It is lost on restart and comes back with the next whole `pulse`. 404 `not_found` when the try does not exist.

### MCP access tokens and login handoff

`artel-macro-mcp` is a remote MCP server and the OAuth 2.1 authorization server for its clients (Claude Code, Codex). Two JWTs cross between it and this server.

**Access JWT (issued by the MCP server, verified here).** The MCP server forwards the user's access JWT as `Authorization: Bearer`. A bearer whose unverified `iss` equals `artel.mcp.issuer` is verified against `artel.mcp.jwks-uri`; every other bearer goes through the existing session, SDK and CLI token paths unchanged. The token must have header `typ` `at+jwt` (RFC 9068; `application/at+jwt` is also accepted, plain `JWT` or no `typ` is 401), an RS256 or ES256 signature from a key in the MCP JWKS, `aud` containing `artel.mcp.audience`, and a valid `exp` / `nbf`. `sub` is the `app_user.id`, so every endpoint resolves the user as it does for a browser session. The JWKS is cached and fetched again only for an unknown `kid`. Any failure is 401.

An access JWT opens only these routes, by the scopes in its `scope` claim (space-separated); project routes still check membership:

| Scope | Routes |
|---|---|
| `macro:read` | `GET /api/projects`, `GET /api/projects/{projectId}/game-builds`, `GET /api/projects/{projectId}/game-builds/{buildId}/game-functions`, `GET /api/macros/grammar`, `GET /api/projects/{projectId}/developer-macros`, `GET /api/projects/{projectId}/developer-macros/{name}` |
| `macro:write` | `POST /api/projects/{projectId}/developer-macros:check`, `PUT /api/projects/{projectId}/developer-macros/{name}`, `DELETE /api/projects/{projectId}/developer-macros/{name}` |

Any other route is 403 `mcp_route_forbidden`, whatever the scope. A listed route without its scope is 403 `insufficient_scope`.

**Handoff JWT (issued here, verified by the MCP server).** When the user allows the connection on home's `/mcp/connect`, home calls:

| Request | Response |
|---|---|
| `POST /api/mcp/handoffs` `{requestId}` | 200 `{redirectUrl}` |
| `GET /.well-known/artel-handoff-jwks.json` | 200 JWK set, no authentication, `Cache-Control: max-age=300, public` |

`requestId` is the MCP server's opaque id for the stored authorization request: 1 to 128 characters of `A-Z a-z 0-9 . _ ~ -`. `redirectUrl` is `{artel.mcp.base-url}/oauth/handoff?token=<handoff JWT>`. The handoff JWT is signed RS256 with `kid` set to the key's RFC 7638 thumbprint, and carries `iss` = `artel.mcp.handoff.issuer`, `aud` = `artel-macro-mcp`, `sub` = `app_user.id`, `request_id`, a new `jti` (UUID) per call, `iat`, and `exp` = `iat` + 2 minutes. The MCP server verifies it with the JWKS above and accepts each `jti` once. The JWKS carries the public key only, and is `{"keys": []}` when no key is configured.

| Status | `code` | When |
|---|---|---|
| 400 | `invalid_request` | `requestId` blank, longer than 128 characters, or with other characters |
| 401 | `unauthorized` | no session |
| 403 | `mcp_handoff_requires_session` | the caller used a CLI token: only a browser session (cookie or session JWT) can allow a connection |
| 403 | `mcp_route_forbidden` | the caller used an MCP access JWT (refused by the route table before the endpoint) |
| 503 | `mcp_handoff_not_configured` | no signing key, or `artel.mcp.base-url` or `artel.mcp.handoff.issuer` is blank |

**Configuration.** Everything is off by default, and the server starts without any of it.

| Key | Environment variable | Default | Meaning |
|---|---|---|---|
| `artel.mcp.base-url` | `ARTEL_MCP_BASE_URL` | blank | MCP server's public URL; `redirectUrl` is built on it |
| `artel.mcp.issuer` | `ARTEL_MCP_ISSUER` | blank | expected `iss` of access JWTs. Must differ from `artel.auth.issuer` (startup fails otherwise) |
| `artel.mcp.jwks-uri` | `ARTEL_MCP_JWKS_URI` | blank | MCP JWKS, `{MCP}/.well-known/jwks.json`. Set both this and `issuer`, or neither (startup fails otherwise); access JWTs are accepted only when both are set |
| `artel.mcp.audience` | `ARTEL_MCP_AUDIENCE` | `artel-orchestration` | required `aud` of access JWTs |
| `artel.mcp.jwks-timeout` | — | `5s` | timeout of a JWKS fetch |
| `artel.mcp.handoff.issuer` | `ARTEL_MCP_HANDOFF_ISSUER` | blank | `iss` of handoff JWTs. Must equal `HANDOFF_ISSUER` on the MCP server, which falls back to its `ORCHESTRATION_BASE_URL`; use this server's public base URL |
| `artel.mcp.handoff.audience` | — | `artel-macro-mcp` | `aud` of handoff JWTs |
| `artel.mcp.handoff.ttl` | — | `2m` | handoff JWT lifetime |
| `artel.mcp.handoff.private-key` | `ARTEL_MCP_HANDOFF_PRIVATE_KEY` | blank | RSA PKCS#8 PEM, at least 2048 bits (`openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048`). In a one-line variable write line breaks as `\n`. A key that cannot be read stops startup; the error never contains the key |
| `artel.mcp.handoff.generate-dev-key` | `ARTEL_MCP_HANDOFF_GENERATE_DEV_KEY` | `false` | with no `private-key`, make a new key on every start. Local use only: a restart invalidates handoffs in flight, and each replica would sign with its own key |

## Verification

```bash
./mvnw -Dtest=OpenApiDocumentationIntegrationTest test
```

The integration test verifies that `/v3/api-docs` publishes the server title, both REST paths, and their operation summaries.
