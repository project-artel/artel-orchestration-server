# 2026-07-16 — Implement ACTION Forwarding & Refactor DTOs

- Date: 2026-07-16
- GitHub Issue: None
- Status: Completed

## Goal
1. Refactor and modularize `Models.kt` by splitting it into three domain-specific DTO files:
   - `CommonDto.kt` (Base envelope/common requests)
   - `GameStateDto.kt` (Game state structures)
   - `ActionDto.kt` (Action, commands, and decisions)
2. Delete the monolithic `Models.kt` file.
3. Implement the ACTION forwarding flow. The Orchestrator will receive action decisions from the Agent server via HTTP POST and forward them as an `ACTION` JSON payload to the corresponding Unity SDK WebSocket client.

## Context / Constraints
- Package: `kr.artel.orchestration.sdk.dto` (remains identical, avoiding import migrations in other files).
- Endpoint: `POST /api/orchestration/action/{sdkId}`
- Action Payload Structure:
  ```json
  {
    "type": "ACTION",
    "id": 2,
    "actions": [
      {
        "id": 1,
        "jsonrpc": "2.0",
        "method": "button_click",
        "params": [2]
      }
    ]
  }
  ```

## Approach (Checklist)

- [x] **Step 1: Refactor DTO files**
  - Create `src/main/kotlin/kr/artel/orchestration/sdk/dto/CommonDto.kt`:
    - Define `BaseMessage`, `SdkIdRegistrationRequest`.
  - Create `src/main/kotlin/kr/artel/orchestration/sdk/dto/GameStateDto.kt`:
    - Define `SdkGameState`, `SdkBlock`, `SdkComponent`, `SdkState`, `SdkAction`, `SdkError`, `AgentGameState`, `ObservableValue`, `Interactable`.
  - Create `src/main/kotlin/kr/artel/orchestration/sdk/dto/ActionDto.kt`:
    - Define `CommandDto`.
    - Define new classes `ActionResponseDto`, `ActionItemDto`.
  - Delete `src/main/kotlin/kr/artel/orchestration/sdk/dto/Models.kt`.

- [x] **Step 2: Update SessionManager**
  - Update `src/main/kotlin/kr/artel/orchestration/sdk/service/SessionManager.kt`:
    - Add `sendAction(sdkId: String, action: ActionResponseDto): Mono<Void>`.

- [x] **Step 3: Update OrchestrationController**
  - Update `src/main/kotlin/kr/artel/orchestration/sdk/controller/OrchestrationController.kt`:
    - Add `POST /api/orchestration/action/{sdkId}` REST endpoint forwarding payload via `sessionManager.sendAction`.

- [x] **Step 4: Implement Integration Test**
  - Update `src/test/kotlin/kr/artel/orchestration/ArtelWebSocketIntegrationTest.kt`:
    - Add `testWebSocketActionForwardingFlow()` integration test.

- [x] **Step 5: Verification**
  - Run `mvn clean test` to verify build success and all integration tests passing.
