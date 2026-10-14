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

`ARTEL_SECRETS_KEY` encrypts the OpenRouter and AWS Bedrock API keys saved by `PUT /api/admin/settings/llm` and `PUT /api/admin/settings/bedrock`. Neither key is read from the environment (`OPENROUTER_API_KEY` is no longer read), so an install enters both on the admin page. Blank is allowed: the server starts and both `PUT`s answer 409 `secrets_key_missing`. A non-blank value must be at least 32 characters and must not contain a placeholder (`change_me`, `change-me`, `changeme`, `replace-with`, `replace_with`, `your-secret`, `secret`, case-insensitive), otherwise the server refuses to start. Generate one with `openssl rand -hex 32`; keep it separate from `ARTEL_JWT_SECRET`. The value is never printed in the error.

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

## Verification

```bash
./mvnw -Dtest=OpenApiDocumentationIntegrationTest test
```

The integration test verifies that `/v3/api-docs` publishes the server title, both REST paths, and their operation summaries.
