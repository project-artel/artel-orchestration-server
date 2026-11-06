# 2026-07-16 — Integrate Agent HTTP Client

- Date: 2026-07-16
- GitHub Issue: None
- Status: Completed

## Goal
Implement HTTP client logic in the Orchestrator to forward the refined compact `AgentGameState` JSON to the Python FastAPI Agent server.
- Define a prefix variable for the base URL (`artel.agent.base-url`) to support future deployment server migrations.
- Append `/gamestate/scene` in the code dynamically.
- Perform the HTTP POST request reactively when the WebSocket handler receives a `GAME_STATE` message.
- Ensure the WebSocket connection is resilient (does not close if the Agent server is offline).
- Mock the HTTP client during integration tests to prevent test failures due to missing external servers.

## Non-goals
- Implementing the Python FastAPI server itself (since it is located in a separate repository).
- Implementing authentication or authorization for the HTTP POST request (unless requested in the future).

## Context / Constraints
- Language: Kotlin (matching existing WebSocket/SDK code).
- Framework: Spring WebFlux (using non-blocking `WebClient`).
- Test isolation: Use `@MockBean` to mock the HTTP client in `ArtelWebSocketIntegrationTest`.

## Approach (Checklist)

- [x] **Step 1: Configuration**
  - Add `artel.agent.base-url=http://localhost:8000` to `src/main/resources/application.properties`.

- [x] **Step 2: Implement AgentClient**
  - Create `kr.artel.orchestration.sdk.service.AgentClient.kt`:
    - Inject `@Value("\${artel.agent.base-url}") private val agentBaseUrl: String`.
    - Build a `WebClient` request targeting `"$agentBaseUrl/gamestate/scene"`: `POST`, `Content-Type: application/json`, Body: `AgentGameState`.
    - Return `Mono<String>`.

- [x] **Step 3: Update SdkWebSocketHandler**
  - Inject `AgentClient` into `SdkWebSocketHandler`.
  - In the `GAME_STATE` message handler branch, change the reactive flow from `.doOnNext` to `.flatMap`.
  - Invoke `agentClient.sendState(agentGameState)`.
  - Prevent errors from bubbling up by attaching `.onErrorResume { Mono.empty() }` to the send call.

- [x] **Step 4: Update Integration Tests**
  - In `ArtelWebSocketIntegrationTest.kt`, add `@MockBean private lateinit var agentClient: AgentClient`.
  - Stub `agentClient.sendState(any())` to return `Mono.just("mocked_response")` to keep the test environment offline-friendly.

## Validation
- **Commands to run:**
  - `mvn clean test`
- **Expected output:**
  - Build success. Both unit and integration tests passing successfully without attempting real connections.

## Risks & Rollback
- **Risks:**
  - If the Agent Server responds with a non-2xx code, the connection should not be retried infinitely, and the WebSocket connection should not drop. (Mitigated via `onErrorResume` on the sending Mono).
- **Rollback steps:**
  - Delete `AgentClient.kt` and git checkout the modified files.
