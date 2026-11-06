# 2026-07-16 — Refactor SCAN

- Date: 2026-07-16
- GitHub Issue: None
- Status: Completed

## Goal
Refactor the input and output structures for the `SCAN` message.
- Parse the detailed game state JSON sent by the Unity SDK (`GAME_STATE` type) via WebSockets.
- Process and refine this hierarchy into a compact representation for the Agent server.
- The compact format contains two main sections:
  1. `interactables` (targets and actions the agent can invoke)
  2. `observables` (current values for agent observation, mapping paths to objects containing value and type)
- Verify the parsing and transformation logic using updated integration tests.

## Non-goals
- Actively sending the compact JSON to an external Agent server via HTTP (since the Agent server connection is not yet defined). We will focus on parsing, transformation, serialization, and logging in the WebSocket handler.
- Managing persistent database storage for game states.
- `last_action_result` tracking (excluded per user request).

## Context / Constraints
- Language: Kotlin (matching existing SDK code, as requested "SDK 및 WebSocket만 Kotlin으로 하고 나머지는 JAVA로 가능해?" -> SDK & WebSocket remain in Kotlin).
- Framework: Spring WebFlux WebSockets.
- Serializer/Deserializer: Jackson (configured natively in Spring Boot).

## Approach (Checklist)

- [x] **Step 0: Recon**
  - Verify package structure under `kr.artel.orchestration.sdk`.
  - Review how Jackson config parses polymorphic or dynamic types.

- [x] **Step 1: Implement Data Models (Kotlin)**
  - Define SDK Game State models in `kr.artel.orchestration.sdk.dto.Models.kt`:
    - `SdkGameState` (with `type`, `id`, `scene`)
    - `SdkScene` (with `id`, `type`, `name`, `children`)
    - `SdkBlock` (with `id`, `type`, `name`, `components`, `children`)
    - `SdkComponent` (with `type`, `name`, `content`, `placeholder`, `states`, `actions`)
    - `SdkState` (with `tag`, `name`, `type`, `value`)
    - `SdkAction` (with `sequence`, `tag`, `name`, `success`, `returnValue`, `error`, `timeStamp`)
    - `SdkError` (with `type`, `message`)
  - Define Agent Game State models (the refined/compact output):
    - `AgentGameState` (with `scene`, `interactables`, `observables`)
    - `Interactable` (with `id`, `name`, `type`, and optional `actions`, `label`, `placeholder`)
    - `ObservableValue` (with `value`, `type`)
    - `BaseMessage` (with `type` to support clean deserialization branching without generic JsonNode parsing)

- [x] **Step 2: Implement Transformation Logic**
  - Implement a transformer class/method (e.g., `GameStateTransformer.toAgentGameState(sdkGameState: SdkGameState): AgentGameState`).
  - **`interactables` Extraction Rules**:
    - Recursively traverse the scene tree (nodes/blocks).
    - If a component has non-empty `actions`, extract it with list of action names.
    - If a component type is `"button"`, search for a `"text"` component in the same block to extract `label`.
    - If a component type is `"editText"`, extract its `placeholder`.
  - **`observables` Extraction Rules**:
    - Build a flat map of keys to `ObservableValue` containing `value` and `type`.
    - For components with `content` (like `text`, `editText`), add `<block_name>.content` -> `ObservableValue(content, "string")`.
    - For components with `states`, for each state add `<block_name>.<component_type>.<state_name>` -> `ObservableValue(state.value, state.type)`.

- [x] **Step 3: Update WebSocket Handler**
  - Update `SdkWebSocketHandler` to handle incoming JSON containing `"type": "GAME_STATE"`.
  - Parse the raw text to `SdkGameState` or envelope check.
  - Transform it to `AgentGameState`, serialize to compact JSON, and log/store the result.

- [x] **Step 4: Update/Add Integration Tests**
  - Update `ArtelWebSocketIntegrationTest` to send the new detailed SDK JSON format.
  - Verify that the WebSocket handler parses it, transforms it correctly, and produces the expected compact Agent JSON structure.

## Validation
- **Commands to run:**
  - `mvn clean test`
- **Expected output:**
  - Build success.
  - Integration test runs, parses the large game state structure, transforms it to the compact JSON format, and asserts the correct fields in `interactables`, `observables`, and `last_action_result`.

## Risks & Rollback
- **Risks:**
  - Dynamic JSON values (like `value` in states, `returnValue` in actions) might have varying types (Int, String, Double). We will handle them as `Any?` or `JsonNode` to prevent Jackson deserialization errors.
- **Rollback steps:**
  - `git checkout -- .` to revert to previous Kotlin WebSocket handler state.

## Open Questions
- Should the `observables` values retain their original type (e.g., `Int` for health, `String` for playerName)? Yes, using Jackson's `Any` or `JsonNode` will keep their exact JSON type representation.
