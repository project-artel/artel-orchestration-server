# 2026-07-16 — Forward ACTION_RESULT and Refactor to Strategy Pattern

- Date: 2026-07-16
- GitHub Issue: None
- Status: Completed

## Goal
1. Implement the forwarding of `ACTION_RESULT` messages from the Unity SDK to the Agent server.
2. Refactor the message dispatching logic in `SdkWebSocketHandler` from an `if-else` structure to the **Strategy Pattern** to support clean open-closed extension of new message types.

## Context / Constraints
- WebSocket Message Types: `GAME_STATE`, `ACTION_RESULT`
- Agent Server Destination Endpoints:
  - `GAME_STATE` -> `POST /gamestate/scene`
  - `ACTION_RESULT` -> `POST /gamestate/result`
- Technical Pattern: Spring-injected list of Strategy handlers mapped by message type.

## Approach (Checklist)

- [x] **Step 1: Define SdkMessageHandler Strategy Interface**
  - Create `src/main/kotlin/kr/artel/orchestration/sdk/service/handler/SdkMessageHandler.kt`:
    - Define the `SdkMessageHandler` interface with `val messageType: String` and `fun handle(sdkId: String, payloadText: String, session: WebSocketSession): Mono<Void>`.

- [x] **Step 2: Implement GameStateMessageHandler**
  - Create `src/main/kotlin/kr/artel/orchestration/sdk/service/handler/GameStateMessageHandler.kt`:
    - Extract the GAME_STATE parsing, compacting, and sending logic from `SdkWebSocketHandler` into this component.

- [x] **Step 3: Implement ActionResultMessageHandler**
  - Create `src/main/kotlin/kr/artel/orchestration/sdk/service/handler/ActionResultMessageHandler.kt`:
    - Implement handling for `ACTION_RESULT`, calling `agentClient.sendResult(payloadText)`.

- [x] **Step 4: Update AgentClient**
  - Update `src/main/kotlin/kr/artel/orchestration/sdk/service/AgentClient.kt`:
    - Add `sendResult(actionResultJson: String): Mono<String>` to post the raw JSON string to `${agentBaseUrl}/gamestate/result`.

- [x] **Step 5: Refactor SdkWebSocketHandler to use Strategy Pattern**
  - Update `src/main/kotlin/kr/artel/orchestration/sdk/service/SdkWebSocketHandler.kt`:
    - Inject `handlers: List<SdkMessageHandler>`.
    - Build a `handlerMap` (`handlers.associateBy { it.messageType }`).
    - Dispatch incoming WebSocket messages dynamically using `handlerMap[base.type]`.

- [x] **Step 6: Implement Integration Test**
  - Update `src/test/kotlin/kr/artel/orchestration/ArtelWebSocketIntegrationTest.kt`:
    - Add `testWebSocketActionResultForwardingFlow()` integration test.

- [x] **Step 7: Verification**
  - Run `mvn clean test` to verify build success and all integration tests passing.
