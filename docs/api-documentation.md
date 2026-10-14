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

`ARTEL_SECRETS_KEY` encrypts the OpenRouter, AWS Bedrock and Claude API keys saved by `PUT /api/admin/settings/llm`, `PUT /api/admin/settings/bedrock` and `PUT /api/admin/settings/anthropic`, and the keys users save with `PUT /api/auth/me/llm-keys/{service}`. None of the keys is read from the environment (`OPENROUTER_API_KEY` is no longer read), so an install enters them on the admin page. Blank is allowed: the server starts and all of those `PUT`s answer 409 `secrets_key_missing`. A non-blank value must be at least 32 characters and must not contain a placeholder (`change_me`, `change-me`, `changeme`, `replace-with`, `replace_with`, `your-secret`, `secret`, case-insensitive), otherwise the server refuses to start. Generate one with `openssl rand -hex 32`; keep it separate from `ARTEL_JWT_SECRET`. The value is never printed in the error.

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

## Verification

```bash
./mvnw -Dtest=OpenApiDocumentationIntegrationTest test
```

The integration test verifies that `/v3/api-docs` publishes the server title, both REST paths, and their operation summaries.
