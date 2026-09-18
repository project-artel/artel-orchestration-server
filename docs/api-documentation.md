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
98 paths and 117 operations at the time of writing.

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

## Verification

```bash
./mvnw -Dtest=OpenApiDocumentationIntegrationTest test
```

The integration test verifies that `/v3/api-docs` publishes the server title, both REST paths, and their operation summaries.
