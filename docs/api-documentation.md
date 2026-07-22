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

## Documented API surface

- `POST /api/sdkId` — register an SDK client ID before session approval.
- `POST /api/orchestration/action/{sdkId}` — deliver an Agent action list to a connected SDK client.
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

Planning-document bytes never pass through this server. The client uploads
directly to S3 with the presigned URL and then calls the register endpoint; a
document does not exist until that registration succeeds.

WebSocket communication at `/ws/sdk` is not an HTTP request/response contract. Keep its message format documented separately; OpenAPI only covers the REST endpoints.

## Verification

```bash
./mvnw -Dtest=OpenApiDocumentationIntegrationTest test
```

The integration test verifies that `/v3/api-docs` publishes the server title, both REST paths, and their operation summaries.
