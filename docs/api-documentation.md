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

- `POST /api/sdk/registrations` — register a running SDK with the instance key issued by the dashboard, and report the game version it was built from. Unauthenticated: the key is the only credential.
- `POST /internal/action/{instanceId}` — deliver an Agent action list to a connected game instance. `/internal/**` is the unauthenticated server-to-server prefix; nothing under it takes an end-user JWT, and it is served only on the internal port.
- `GET /api/test-scenario/{clientId}/stream` — subscribe to test-scenario events over SSE.
- `POST /api/test-scenario/{clientId}/message` — relay a user message to the Agent server.
- `GET /api/auth/me` — read the signed-in user.
- `POST /api/auth/logout` — clear the session cookie.
- `GET /api/projects` — list projects the caller belongs to, paged.
- `POST /api/projects` — create a project; the creator becomes its owner.
- `GET /api/projects/{projectId}` — read one project.
- `PATCH /api/projects/{projectId}` — partially update a project.
- `DELETE /api/projects/{projectId}` — soft-delete a project. Owner only.
- `POST /api/projects/{projectId}/documents/upload-url` — mint a presigned URL for a planning-document PDF.
- `POST /api/projects/{projectId}/documents` — register an uploaded object as the next document version.
- `GET /api/projects/{projectId}/documents` — list document versions, newest first.
- `GET /api/projects/{projectId}/documents/{documentId}/download-url` — mint a short-lived download URL.
- `GET /api/projects/{projectId}/game-instances` — list the project's SDK installations.
- `POST /api/projects/{projectId}/game-instances` — create one and issue its permanent instance key.
- `PATCH /api/projects/{projectId}/game-instances/{instanceId}` — rename it. The key never changes.
- `DELETE /api/projects/{projectId}/game-instances/{instanceId}` — soft-delete it; its key stops working immediately.
- `GET /api/projects/{projectId}/game-builds` — list the versions SDKs have reported, newest first.
- `PATCH /api/projects/{projectId}/game-builds/{buildId}` — edit `label` and `notes`. `version` is observed, not authored, and cannot be changed.

Planning-document bytes never pass through this server. The client uploads
directly to S3 with the presigned URL and then calls the register endpoint; a
document does not exist until that registration succeeds.

Game builds are never created through the API. They appear when an SDK reports a
version it has not reported before, which is why there is no create or delete
endpoint for them.

WebSocket communication at `/ws/sdk` is not an HTTP request/response contract. Keep its message format documented separately; OpenAPI only covers the REST endpoints.

The socket authenticates with the same instance key, passed as the `instanceKey`
query parameter. The server closes with `4001` when the key matches no live
instance and with `4002` when that instance already has a connection — one
instance holds one socket, and the newcomer is refused rather than displacing
the incumbent.

## Verification

```bash
./mvnw -Dtest=OpenApiDocumentationIntegrationTest test
```

The integration test verifies that `/v3/api-docs` publishes the server title, both REST paths, and their operation summaries.
