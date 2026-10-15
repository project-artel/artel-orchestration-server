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

## Verification

```bash
./mvnw -Dtest=OpenApiDocumentationIntegrationTest test
```

The integration test verifies that `/v3/api-docs` publishes the server title, both REST paths, and their operation summaries.
