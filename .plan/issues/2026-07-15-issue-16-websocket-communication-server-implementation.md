# 2026-07-15 — Websocket Communication Server Implementation

- Date: 2026-07-15
- GitHub Issue: #16
- Status: Completed

## Goal
Initialize the `artel-orchestration-server` as a 100% Kotlin Spring Boot WebFlux application. Implement a reactive WebSocket communication server that:
- Accepts WebSocket connections from the Unity SDK client at `/ws/sdk`.
- Validates the client's connection request using a `uuid` query parameter during the handshake/connection startup.
- Manages valid UUIDs and active sessions in-memory.
- Allows real-time bidirectional communication:
  - Client registers class metadata structure.
  - Server pushes test commands (eliminating HTTP polling).
  - Client reports test execution results back to the server.
- Verifies the full flow using an automated WebFlux integration test containing a mock SDK client.

## Non-goals
- Persistent database implementation (in-memory state only).
- Creating a complex UI (simple REST endpoints to register UUIDs or trigger test commands are sufficient).

## Context / Constraints
- Java Version: JDK 26 (targeted compilation to Java 21 compatibility).
- Build Tool: Maven (pre-installed, `pom.xml` configured for standard Kotlin compilation).
- Spring WebFlux framework for reactive networking and WebFlux WebSockets.
- Unity SDK has a poll-based PoC (`ArtelPocManager.cs`) in the sibling repository `artel-sdk`. We will adapt the WebSocket models to match this structure.

## Approach (Checklist)

- [x] **Step 0: Recon & Kotlin Project Initialization**
  - Verify Maven and Java.
  - Create the `pom.xml` file containing:
    - Spring Boot Starter WebFlux (which natively supports reactive WebSockets).
    - Kotlin standard library + Jackson Module Kotlin for Kotlin data class serialization.
    - Maven compile plugins for standard Kotlin compilation.
  - Create project folders:
    - `src/main/kotlin/` (Kotlin source root)
    - `src/test/kotlin/` (Kotlin test root)
  - Create the Spring Boot entry point in Kotlin: `kr.artel.orchestration.ArtelOrchestrationApplication.kt`.

- [x] **Step 1: In-Memory Verification & Session Management (Kotlin)**
  - Implement `UuidVerificationService` (Kotlin):
    - Maintain a thread-safe in-memory set of valid UUIDs.
    - Provide methods to check if a UUID is valid.
    - Provide a REST endpoint `POST /api/uuids` to register new valid UUIDs dynamically.
  - Implement `SessionManager` (Kotlin):
    - Store active WebSocket sessions mapped by their validated UUID.
    - Provide methods to retrieve a session by UUID and send messages.

- [x] **Step 2: WebSocket Handler & Models (Kotlin)**
  - Define data models matching the Unity SDK (Kotlin `data class`es):
    - `ClassMetadata`, `VariableMetadata`, `MethodMetadata`, `ParameterMetadata`
    - `CommandDto`
    - `ReportDto`
    - `WebSocketMessage` (envelope wrapping payload with a `type` enum: `SCAN`, `COMMAND`, `REPORT`, `ERROR`)
  - Implement `SdkWebSocketHandler` (Kotlin, implementing `org.springframework.web.reactive.socket.WebSocketHandler`):
    - Parse the `uuid` query parameter from `session.handshakeInfo.uri`.
    - Check validity via `UuidVerificationService`. Reject/close connection with status `4001 (Unauthorized)` if invalid.
    - Register session in `SessionManager`.
    - Handle incoming WebSocket messages:
      - `SCAN`: Log metadata upload and store/print it.
      - `REPORT`: Log execution results.
    - Handle session closure: Remove session from `SessionManager`.

- [x] **Step 3: WebFlux Configuration & REST Controller (Kotlin)**
  - Create `WebSocketConfig` (Kotlin):
    - Configure the `SimpleUrlHandlerMapping` mapping `/ws/sdk` to our Kotlin `SdkWebSocketHandler`.
    - Register a `WebSocketHandlerAdapter` bean to enable reactive WebSockets.
  - Create `OrchestrationController` (Kotlin):
    - `POST /api/orchestration/command/{uuid}`: REST endpoint to trigger a command on a specific connected SDK client via its WebSocket session.

- [x] **Step 4: Integration Tests (Kotlin)**
  - Create `ArtelWebSocketIntegrationTest` (Kotlin):
    - Spin up the WebFlux server on a random port.
    - Register a test UUID via the REST API.
    - Connect a mock client using WebFlux's `ReactorNettyWebSocketClient`.
    - Send metadata (`SCAN`) and verify server receives it.
    - Trigger a command via REST API, verify mock client receives the command, and mock client responds with a `REPORT`.

## Validation
- **Commands to run:**
  - Build project: `mvn clean package`
  - Run tests: `mvn test`
  - Run server locally: `mvn spring-boot:run`
- **Expected output:**
  - Successful compilation of Kotlin code.
  - Webflux integration tests passing with clean handshakes, metadata scans, and commands.

## Risks & Rollback
- **Risks:**
  - Kotlin version alignment with JDK 26: We will target Java 21 bytecode version in the Kotlin compiler settings to ensure stability.
- **Rollback steps:**
  - `git checkout main -- .` and clean project directory.

## Open Questions
- None. (Aligned on using query parameter verification and automated integration testing).
