# 2026-07-16 — Refactor GameStateTransformer Node Traversal

- Date: 2026-07-16
- GitHub Issue: None
- Status: Draft

## Goal
Clean up and modularize the scene tree traversal logic inside `GameStateTransformer.kt`.
- Separate the massive recursive `traverse` function into dedicated, single-responsibility helper functions:
  1. `extractInteractables(node, list)`: Extracts buttons, inputs, and script controllers.
  2. `extractObservables(node, map)`: Extracts states and content fields.
- Improve code readability, documentation, and maintainability.

## Non-goals
- Changing the functional output or output JSON structure of `AgentGameState` (the outputs must remain exactly the same as verified by tests).

## Context / Constraints
- Language: Kotlin.
- Test compatibility: Must pass all existing tests in `ArtelWebSocketIntegrationTest.kt` without any changes to the assertions.

## Approach (Checklist)

- [ ] **Step 1: Refactor GameStateTransformer**
  - Modify `src/main/kotlin/kr/artel/orchestration/sdk/service/GameStateTransformer.kt`:
    - Refactor `traverse` to call `extractInteractables` and `extractObservables`.
    - Implement `extractInteractables` for handling target & action mapping.
    - Implement `extractObservables` for handling variables & content mapping.
    - Keep recursion (`traverse(child)`) clean.

- [ ] **Step 2: Verification**
  - Run `mvn clean test` to ensure no regression or logic changes occurred.

## Validation
- **Commands to run:**
  - `mvn clean test`
- **Expected output:**
  - Build success. All tests pass.
