# 2026-07-21 — TestScenario Chatbot Pipeline: Test Plan

- Date: 2026-07-21
- GitHub Issue: None
- Jira: None (TBD)
- Status: Draft (pre-implementation)
- Related plan: implementation plan for the TestScenario pipeline (to be written via `writing-plan`)

## Purpose

Define the test cases that validate the TestScenario chatbot pipeline **before** implementation, so
coverage matches behavior risk and the PR can report concrete evidence (`testing.md`).

Feature under test (MVP scope):
1. React → Orchestration → Agent : forward user natural language.
2. Agent → Orchestration → React : forward the agent reply / fallback question over **SSE**.

Out of scope for these tests (future work): DB persistence, Canvas / step-graph CRUD.

## Identifiers (context for the cases)

- `clientId` — FE-generated UUID. Stable key for the SSE stream and for callback routing.
- `agentSessionId` — issued by the Agent server, arrives later; Orchestration keeps a
  `clientId ↔ agentSessionId` mapping to preserve chatbot context across turns.

## Environment & Commands

| Purpose | Command |
|---|---|
| Run all tests | `./mvnw test` |
| Run only this suite | `./mvnw -Dtest=TestScenarioPipelineIntegrationTest test` |

- Framework: Spring WebFlux + `WebTestClient` (reactive).
- The real Agent server is **not** required; the outbound Agent call is stubbed and the inbound
  callback is driven directly against the internal endpoint.
- No DB needed for this feature; the `test` profile / H2 setup is unaffected.

## Risk-Based Coverage

- **High** — callback → SSE delivery (core value path); message → Agent forwarding (correctness of contract).
- **Medium** — clientId↔agentSessionId mapping and multi-turn continuity; stream isolation/cleanup.
- **Low** — input validation and graceful handling of unknown ids.

## Test Cases

### TC-1 — SSE delivery of an agent callback (core pipeline) [High]
- **Given** a client subscribes to `GET /api/testscenario/{clientId}/stream`.
- **When** the Agent posts `POST /internal/api/agents/testscenario/{agentSessionId}` with body
  `{ clientId, type, payload }`.
- **Then** the subscribed stream emits one `ServerSentEvent` whose event name reflects `type` and
  whose data equals `payload` (unchanged).
- **Asserts**: exactly one event, correct routing to the matching `clientId`, payload passed through
  without mutation.

### TC-2 — Outbound forwarding to the Agent [High]
- **Given** a stubbed Agent endpoint capturing the request body.
- **When** `POST /api/testscenario/{clientId}/message` with `{ type, testscenariomsg }`.
- **Then** Orchestration calls `POST {agentBaseUrl}/testscenario/scenariostep` with
  `{ type, testscenariomsg, clientId, agentSessionId? }`.
- **Asserts**: target URL, `clientId` propagated, `agentSessionId` is null on the first turn.

### TC-3 — clientId ↔ agentSessionId mapping & multi-turn continuity [Medium]
- **Given** TC-1 has delivered a callback carrying `agentSessionId` for a known `clientId`.
- **When** a subsequent `POST /api/testscenario/{clientId}/message` is sent.
- **Then** the outbound Agent request now includes the previously learned `agentSessionId`.
- **Asserts**: mapping stored from the callback; reused on the next turn.

### TC-4 — Fallback question vs step result differentiated by `type` [Medium]
- **Given** a subscribed stream.
- **When** the Agent posts a callback with `type = "QUESTION"` (fallback) and later one with a
  step-result `type`.
- **Then** both arrive as distinct SSE events with distinct event names.
- **Asserts**: FE can filter step events vs questions purely by event `type` (future Canvas hook).

### TC-5 — Stream isolation across clients [Medium]
- **Given** two clients subscribe with different `clientId`s.
- **When** a callback targets `clientId=A`.
- **Then** only stream A receives the event; stream B receives nothing.

### TC-6 — Unknown / stale clientId handled gracefully [Low]
- **When** a callback arrives for a `clientId` with no active stream.
- **Then** the endpoint returns 2xx (no crash); the event is dropped or buffered per the stream
  manager's policy (documented behavior), and no other stream is affected.

### TC-7 — Stream cleanup on disconnect [Medium]
- **Given** a subscribed stream.
- **When** the client cancels/closes the SSE subscription.
- **Then** the stream manager removes the sink for that `clientId` (no leak).

## Contract Assumption Being Exercised

- The Agent callback body **includes `clientId`** (echoed from the outbound request) so Orchestration
  can route to the correct SSE stream even though the callback path is keyed by `{agentSessionId}`.
  TC-1/TC-5 depend on this. If the Agent contract changes, revisit routing and these cases.

## Reporting (to appear in the PR)

- Commands run and results (`./mvnw test`).
- Which TCs are automated vs. deferred, with reasons.
- Residual risk: real Agent contract not yet integration-tested end-to-end (stubbed here).
</content>
</invoke>
