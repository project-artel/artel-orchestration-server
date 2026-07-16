# 2026-07-16 — Remove Command Flow

- Date: 2026-07-16
- GitHub Issue: None
- Status: Completed

## Goal
Remove all obsolete `Command` and `CommandDto` related endpoints, DTOs, and test cases, leaving `ACTION` as the unified decision execution pathway to the Unity SDK.

## Context / Constraints
- Obsolete endpoint: `POST /api/orchestration/command/{sdkId}`.
- Obsolete class: `CommandDto`.
- Obsolete test: `testWebSocketBidirectionalFlow`.

## Approach (Checklist)

- [x] **Step 1: Clean up DTOs**
  - Update `src/main/kotlin/kr/artel/orchestration/sdk/dto/ActionDto.kt`:
    - Remove the `CommandDto` data class definition.

- [x] **Step 2: Update SessionManager**
  - Update `src/main/kotlin/kr/artel/orchestration/sdk/service/SessionManager.kt`:
    - Remove the `sendCommand` function.

- [x] **Step 3: Update and Rename OrchestrationController**
  - Rename the file `src/main/kotlin/kr/artel/orchestration/sdk/controller/OrchestrationController.kt` to `SdkController.kt`.
  - Update class name from `OrchestrationController` to `SdkController`.
  - Remove the `sendCommand` endpoint.

- [x] **Step 4: Clean up Tests**
  - Update `src/test/kotlin/kr/artel/orchestration/ArtelWebSocketIntegrationTest.kt`:
    - Remove the `testWebSocketBidirectionalFlow` test case.
    - Update references from `OrchestrationController` to `SdkController` if any.

- [x] **Step 5: Verification**
  - Run `mvn clean test` to ensure everything compiles and all tests pass cleanly.
