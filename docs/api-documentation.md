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

WebSocket communication at `/ws/sdk` is not an HTTP request/response contract. Keep its message format documented separately; OpenAPI only covers the REST endpoints.

## Verification

```bash
./mvnw -Dtest=OpenApiDocumentationIntegrationTest test
```

The integration test verifies that `/v3/api-docs` publishes the server title, both REST paths, and their operation summaries.
