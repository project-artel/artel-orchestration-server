# 2026-07-24 — Implement QA Try, Log, and SDK Bridge

- Date: 2026-07-24
- Jira: ARTEL-121
- Status: Implemented (integration validation pending environment)

## Goal

Add the orchestration-server half of QA execution: create and read a QA try,
persist every relevant Agent↔Orchestration and Orchestration↔SDK message as an
append-only log, expose resumable live log delivery over SSE, and expose
cursor-based history for the frontend's reverse infinite scroll.

The orchestration server remains the protocol boundary. Agent-facing QA
envelopes carry display text and QA context, while SDK-facing `ACTION` and
`ACTION_RESULT` payloads retain the SDK's existing JSON-RPC 2.0 action contract.

## Non-goals

- Implementing or changing the Agent Server.
- Deciding whether a QA try passed or failed, or calculating step verdicts.
- Adding frozen test-scenario versions, scenario snapshots, game-build
  snapshots, result summaries, or other speculative QA metadata.
- Changing the Unity SDK protocol or implementing recorded-video playback.
- Building the React QA page (tracked separately by ARTEL-120).
- Replacing the existing Test Scenario authoring flow.

## Context / Constraints

- This is a Spring WebFlux/R2DBC service. New request paths must stay reactive
  and must not block request or WebSocket event-loop threads.
- Flyway migrations live in `src/main/resources/db/migration/`; the next
  migration is `V7__create_qa_try_and_qa_log.sql`.
- End-user QA APIs require the existing JWT cookie/bearer authentication.
  Membership is derived through the QA try's test scenario and game instance;
  inaccessible resources return 404 to avoid disclosing their existence.
- A QA try may only bind a test scenario and game instance belonging to the
  same accessible project. Starting a second active try for the same game
  instance must return 409 so SDK traffic is never routed ambiguously.
- SDK WebSocket authentication remains based on `instanceKey`; no end-user JWT
  is added to `/ws/sdk`.
- The durable database log is the source of truth. SSE is only a low-latency
  notification channel and may drop during disconnects; clients recover with
  the history endpoint or `Last-Event-ID`.
- Store only user-displayable reasoning summaries in `LOG.message`. Do not
  request, transport, or persist hidden chain-of-thought.
- Reject an individual QA envelope whose serialized `payload` exceeds 1 MiB
  before persistence or forwarding. Keep this limit as a named configuration
  value so it can be aligned with WebSocket/frame limits without a schema
  change; add boundary tests for exactly-at and over-limit payloads.

### Minimal persistence model

`qa_try`:

- `id BIGSERIAL PRIMARY KEY`
- `test_scenario_id BIGINT NOT NULL` FK to `test_scenario`
- `game_instance_id BIGINT NOT NULL` FK to `game_instance`
- `started_by BIGINT NOT NULL` FK to `app_user`
- `agent_session_id VARCHAR(255) NULL` for routing after the external Agent
  Server creates the QA session
- `status VARCHAR(20) NOT NULL` constrained to lifecycle-only values
  `STARTING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED`
- `started_at TIMESTAMPTZ NOT NULL`
- `completed_at TIMESTAMPTZ NULL`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
- `updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`

`qa_log`:

- `id BIGSERIAL PRIMARY KEY`; this is also the stable ordering/SSE event ID
- `qa_try_id BIGINT NOT NULL` FK to `qa_try` with `ON DELETE CASCADE`
- `message_id VARCHAR(255) NULL`
- `correlation_id VARCHAR(255) NULL`
- `direction VARCHAR(30) NOT NULL`, constrained to `AGENT_TO_ORCHE`,
  `ORCHE_TO_AGENT`, `ORCHE_TO_SDK`, `SDK_TO_ORCHE`, `ORCHE_INTERNAL`
- `type VARCHAR(30) NOT NULL`, constrained to the compact protocol set `LOG`,
  `ACTION`, `ACTION_RESULT`, `GAME_STATE`, `STATUS`, `ERROR`
- `message TEXT NULL`
- `payload JSONB NOT NULL DEFAULT '{}'`
- `created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`

Indexes:

- `(qa_try_id, id DESC)` for keyset log pages and resume.
- `(game_instance_id, status)` for active SDK-route lookup. Enforce at most one
  active try per instance with a PostgreSQL partial unique index over
  `game_instance_id WHERE status IN ('STARTING', 'RUNNING')`.
- A partial unique `(qa_try_id, direction, message_id)` where `message_id IS NOT
  NULL` for idempotent ingestion without incorrectly conflating the same ID
  across protocol directions.

No `test_scenario_version`, frozen payload, build ID, summary, sequence,
severity/category, processing-state, producer timestamp, or duplicated
scene/action columns are added.

The migration uses PostgreSQL's explicit `DEFAULT '{}'::jsonb`. The three
`qa_try` foreign keys use the default restrictive behavior (no cascade), so a
scenario, instance, or user cannot be hard-deleted out from under retained
audit history. Only `qa_log.qa_try_id` uses `ON DELETE CASCADE`, for a future
explicit QA-data deletion workflow.

`FAILED` means the QA execution infrastructure could not continue. It is never
a QA test verdict. `COMPLETED` means execution ended normally, without implying
that assertions passed.

### HTTP and event contract assumptions

- `POST /api/qa-tries` accepts `{testScenarioId, gameInstanceId}`. It validates
  membership, same-project ownership, active SDK connection, and no active try.
  It transactionally creates the `STARTING` try and initial STATUS log first.
  It then calls the Agent's `POST /sessions` with `{type:"QA", model, context}`
  including IDs and the current scenario payload, saves the returned
  `agent_session_id`, and opens `/sessions/{agentSessionId}`. Once that route is
  usable it transitions to `RUNNING` and returns `201`. Session/open failure
  transitions the retained try to execution `FAILED` with ERROR and terminal
  STATUS logs and returns `503`.
- `GET /api/qa-tries/{qaTryId}` returns lifecycle and association fields only.
- `GET /api/qa-tries/{qaTryId}/logs?beforeId=<logId>&size=<n>` selects the
  latest rows by `id DESC`, then reverses the page before returning
  `{items, nextBeforeId, hasMore}`. Thus every `items` array is chronological
  (oldest→newest) for direct timeline insertion. Default size is 50 and the
  maximum is 100. `beforeId` is exclusive (`id < beforeId`); omitting it starts
  at the newest page. `nextBeforeId` is the oldest returned ID, or null when
  `hasMore=false`.
- `GET /api/qa-tries/{qaTryId}/events?afterId=<logId>` emits
  `ServerSentEvent<QaLogResponse>`. The SSE `id` is the decimal `qa_log.id`,
  the SSE event name is always `log`, and `data` is the same `QaLogResponse`
  DTO returned by history. Message type remains in `data.type`.
- Browsers cannot set `Last-Event-ID` on their initial `EventSource` request,
  so initial subscription resumes from the optional exclusive `afterId` query
  parameter. Native EventSource reconnects use the `Last-Event-ID` header; if
  both are present, the header takes precedence. Rows with `id > cursor` replay
  in ascending order before live delivery.
- The implementation must avoid a query/subscription gap by registering the
  in-memory sink first. Each subscriber captures its own durable high-water log
  ID, replays `(cursor, highWater]` in ascending order, buffers live events
  while replaying, and then drains only buffered/live IDs `> highWater` in
  ascending order. Per-subscriber ID de-duplication protects the boundary; a
  global cursor must not let one subscriber skip another subscriber's rows.
  For a terminal try, replay through the terminal `STATUS` event and then
  complete the SSE response.
- The existing authenticated frontend can open SSE with the JWT cookie.
- Agent-facing QA messages use an envelope containing `type`, `messageId`,
  `qaTryId`, optional `correlationId`, and `payload`. `payload.message` is
  normalized into `qa_log.message` for `LOG` and `ACTION`, while the complete
  accepted payload is retained in `payload`.
- Agent `ACTION.payload.actions` is a list of the existing JSON-RPC action
  objects (`id`, `jsonrpc: "2.0"`, `method`, `params`). Orchestration converts
  it to the existing SDK `ActionResponseDto` without exposing QA-only fields.
- SDK `ACTION_RESULT` is parsed enough to validate/type it, persisted in the
  `SDK_TO_ORCHE` row, then wrapped for the Agent with the originating
  `correlationId`. Exact result fields remain in `payload`.
- SDK `GAME_STATE` is transformed with the existing `GameStateTransformer`;
  only that compact `scene`, `interactables`, and `observables` representation
  is persisted and sent to the Agent. The full SDK scene tree is not stored.
- `STATUS.payload` always contains
  `{status: "STARTING|RUNNING|COMPLETED|FAILED|CANCELLED", completedAt:
  <ISO-8601 string|null>}`. The top-level `message` is display text only.
- Agent session creation sends the confirmed Notion contract:
  `{type:"QA",model:"openai/gpt-4o-mini",context:{qa_try_id,
  game_instance_id,test_scenario_id,scenario}}` and expects
  `{session_id,type:"QA"}` before opening `/sessions/{session_id}`.

The three externally forwarded message paths always produce two distinct log
rows, never one row whose direction is later mutated:

1. `AGENT_TO_ORCHE/ACTION` → `ORCHE_TO_SDK/ACTION`
2. `SDK_TO_ORCHE/GAME_STATE` → `ORCHE_TO_AGENT/GAME_STATE`
3. `SDK_TO_ORCHE/ACTION_RESULT` →
   `ORCHE_TO_AGENT/ACTION_RESULT`

The first successful insert of an Agent ACTION message ID wins execution. A
duplicate `(qaTryId, AGENT_TO_ORCHE, messageId)` resolves to the already
persisted row and must not append the SDK-direction row or call
`SessionManager.sendAction` again.

ACTION result correlation is deterministic:

- The SDK outer `ActionResponseDto.id` is the persisted
  `ORCHE_TO_SDK/ACTION` `qa_log.id`.
- That decimal ID is also the outbound log row's `messageId`; its
  `correlationId` stores the inbound Agent ACTION `messageId`.
- SDK `ACTION_RESULT.id` resolves that exact outbound log row and active QA try.
  The inbound result uses the outer decimal ID as `messageId` and the original
  Agent ACTION ID as `correlationId`.
- A duplicate result outer ID is idempotent and is never forwarded twice.
  An unknown/mismatched outer ID appends `ORCHE_INTERNAL/ERROR` and is not
  forwarded. Unknown inner JSON-RPC result IDs stay untouched in `payload` and
  are forwarded only when the outer mapping is valid.

All public identifiers are base-10 strings: `qaTryId`, `testScenarioId`,
`gameInstanceId`, `startedBy`, `agentSessionId` where applicable, QA log `id`,
SSE `id`, `beforeId`, `nextBeforeId`, and `afterId`. Persistence uses `BIGINT`;
controllers validate non-negative decimal syntax and signed 64-bit range before
calling repositories.

## Approach (Checklist)

- [ ] **Step 0: Recon and contract lock**
  - Confirm ARTEL-121 acceptance criteria and the Agent API specification for
    QA session creation, QA WebSocket envelope, close behavior, and JSON-RPC
    result correlation.
  - Inspect the current `SdkController`, `SdkWebSocketHandler`,
    `SessionManager`, message handlers, Test Scenario access pattern, and
    frontend ARTEL-120 contract before naming public DTO fields.
  - Record any contract mismatch in this plan instead of silently adding
    compatibility aliases.

- [ ] **Step 1: Add backward-compatible Flyway schema**
  - Re-scan `src/main/resources/db/migration/` immediately before creating the
    migration. Use V7 only if it is still the highest-free sequential version;
    if another branch has claimed it, select the next available version and
    update this plan. Never overwrite a colliding migration.
  - Add
    `src/main/resources/db/migration/V7__create_qa_try_and_qa_log.sql` using
    PostgreSQL DDL and the minimal tables, constraints, and indexes above.
  - Add R2DBC entities `qa/entity/QaTryEntity.kt` and
    `qa/entity/QaLogEntity.kt`.
  - Add `qa/repository/QaTryRepository.kt` with accessible-by-member and
    active-by-instance queries, and `qa/repository/QaLogRepository.kt` with
    explicit keyset/replay queries. Do not use offset pagination.
  - Treat duplicate message IDs as idempotent existing writes; do not mutate an
    existing log row. The log append API returns an atomic
    `{log, inserted: Boolean}` result: `inserted=true` owns any resulting side
    effect, while a unique-conflict lookup returns the existing row with
    `inserted=false`.

- [ ] **Step 2: Implement authenticated QA try/history APIs**
  - Add `qa/dto/QaDtos.kt`, `qa/controller/QaTryController.kt`,
    `qa/service/QaTryAccessService.kt`, and `qa/service/QaTryService.kt`.
  - Resolve `app_user` through `SessionUserResolver`, validate both referenced
    resources belong to the same member-accessible project, and map
    missing/inaccessible data to 404, active conflicts to 409, and disconnected
    SDK instances to a documented conflict/service-unavailable response.
  - Create the try and initial `ORCHE_INTERNAL/STATUS` log transactionally so a
    visible try is never missing its first lifecycle record.
  - Implement conditional status updates in the repository (`WHERE id = ? AND
    status = ?`) so concurrent callbacks cannot regress lifecycle state. Legal
    transitions are `STARTING→RUNNING|FAILED|CANCELLED` and
    `RUNNING→COMPLETED|FAILED|CANCELLED`; terminal states never transition.
    Non-terminal rows require `completed_at IS NULL`; terminal transitions set
    it once, and the terminal `STATUS.payload.completedAt` must match that
    persisted timestamp.
  - Return string IDs if needed to match the repository's existing frontend
    convention and avoid JavaScript integer precision loss.
  - Add the protected `/api/qa-tries/**` matcher explicitly to
    `SecurityConfig.kt` for clarity, even though `anyExchange().authenticated()`
    is already protective.

- [ ] **Step 3: Add durable log append and resumable SSE**
  - Add `qa/service/QaLogService.kt` as the single append path: validate enum
    values and payload size, insert inside its transaction, and publish the
    persisted DTO only after the transaction successfully commits. A rollback
    must never produce an SSE event.
  - Add `qa/service/QaLogStreamManager.kt` with one multicast sink per QA try,
    subscriber cleanup that does not remove a shared sink while another
    subscriber remains, per-subscriber high-water ordered replay, bounded
    buffering/error handling, and terminal completion only after the terminal
    status log commits and is publishable.
  - Implement history keyset pagination and SSE replay from the same repository
    ordering. The repository may fetch descending for efficiency, but the
    service returns each page oldest→newest. Use `beforeId`/`nextBeforeId` for
    history and `afterId`/`Last-Event-ID` for SSE; test reconnect without
    duplicates or gaps.
  - Do not keep completed-try sinks alive. History remains queryable after
    completion, while `/events` either replays through the durable terminal row
    and completes or returns the documented terminal behavior.

- [x] **Step 4: Introduce the internal QA Agent port**
  - Define a narrow `QaAgentPort` owned by the QA application service for
    create-session, send-message, and close-session operations. Inject this
    port into QA services so domain/API implementation and tests do not require
    a running Agent Server.
  - Add an in-memory fake/test adapter. Do not extend
    `TestScenarioAgentService` with conditionals that couple authoring and QA
    lifecycles.
  - Create the external Agent session with `type: "QA"` after the STARTING try
    exists, persist
    `agent_session_id`, and maintain the active `qaTryId ↔ Agent WebSocket`
    mapping through the port abstraction. The production adapter uses the
    confirmed HTTP/WebSocket contract; wire DTOs do not leak into controllers,
    persistence, or SDK DTOs.
  - Route only `LOG`, `ACTION`, `STATUS`, and `ERROR` from the Agent. Reject or
    persist an `ERROR` for malformed/unsupported envelopes without executing
    them.
  - For inbound `ACTION`, atomically claim execution through the unique
    `AGENT_TO_ORCHE/ACTION` insert before side effects,
    translate its JSON-RPC actions to `ActionResponseDto`, append
    `ORCHE_TO_SDK/ACTION`, and then enqueue it through `SessionManager`.
    Insert-wins semantics mean a duplicate Agent `messageId` returns the
    existing claim and must not execute twice.
  - Persist `ORCHE_TO_SDK/ACTION` first, use its generated log ID for the SDK
    outer action ID and outbound `messageId`, and retain the inbound Agent ID in
    `correlationId`.
  - Persist outbound `GAME_STATE`/`ACTION_RESULT` rows before sending to the
    Agent. Send failures append `ORCHE_INTERNAL/ERROR`; they never rewrite the
    original audit row.

- [ ] **Step 5: Bind existing SDK handlers to the active QA try**
  - Replace the global HTTP behavior in
    `sdk/service/handler/GameStateMessageHandler.kt` and
    `ActionResultMessageHandler.kt` with a QA bridge service that looks up the
    one active try by `instanceId`.
  - Branch explicitly at the handler boundary:
    successful lookup with an active QA try uses only the QA bridge; successful
    lookup with no active try uses the existing legacy `AgentClient`; an active
    lookup error, QA log-persistence error, or QA forwarding error fails/logs
    the QA path and must never fall back to legacy delivery.
  - Keep `GameStateTransformer.kt`, `ActionResponseDto`, and
    `SessionManager.sendAction` as the SDK protocol seam.
  - Add `qa/service/QaSdkBridgeService.kt` to append both SDK directions,
    resolve `ACTION_RESULT.id` against the exact outbound ACTION log,
    idempotently claim result forwarding, and forward compact state or valid
    results to the correct Agent session. Unknown outer IDs append ERROR and
    stop; inner JSON-RPC results remain opaque payload.
  - Decide deduplication only after confirming whether SDK `GAME_STATE.id` is a
    stable message ID. Do not add a scene hash or new database column
    speculatively.

- [ ] **Step 6: Lifecycle and failure behavior**
  - Move `STARTING → RUNNING` only when the Agent session and SDK route are
    usable. Apply terminal status received from the Agent without deriving a
    pass/fail verdict.
  - Store each lifecycle transition as an append-only `STATUS` log and update
    `qa_try.status/completed_at` in the same transaction.
  - Reject illegal/stale transitions through conditional updates rather than
    last-write-wins saves. Publish the transition log only after the combined
    transaction commits.
  - Any Agent or SDK disconnect while a try is `STARTING` or `RUNNING`
    atomically transitions it to execution `FAILED`, appends a user-displayable
    `ERROR` plus terminal `STATUS`, and completes Agent/SSE/routing resources.
    This is infrastructure failure only and never a QA verdict.
  - Close Agent/SSE resources and active routing maps on terminal status and
    application shutdown.

- [ ] **Step 7: Tests**
  - Add migration/repository integration coverage for both tables, JSONB,
    explicit JSONB default, restrictive/cascading FK behavior, constraints,
    active-try uniqueness, atomic `{inserted}` duplicate handling, legal and
    stale status transitions, `completed_at` consistency, keyset page
    boundaries, and replay ordering.
  - Add WebTestClient integration coverage for authentication, membership
    isolation, same-project validation, create/detail/log responses, default
    and maximum page sizes, chronological page items,
    `beforeId`/`nextBeforeId`, decimal-string public IDs, malformed/overflow
    cursors, and 404/409/503 behavior. Verify Agent connection failure leaves a
    retained terminal FAILED try and audit logs.
  - Add Reactor tests for persist-before-emit, multiple SSE subscribers,
    initial replay with `afterId`, reconnect replay with `Last-Event-ID`,
    independent per-subscriber high-water marks, rollback-without-publish,
    constant SSE event name `log`, terminal replay-then-complete, and no
    duplicate/out-of-order event at the replay/live boundary.
  - Extend SDK WebSocket integration coverage for:
    `GAME_STATE → compact log → Agent`, Agent JSON-RPC
    `ACTION → durable logs → SDK`, and
    `ACTION_RESULT → durable logs → originating Agent`, including malformed
    messages, missing active try, duplicate Agent action/result, unknown outer
    result ID, preserved unknown inner results, deterministic outer-ID mapping,
    and disconnect-driven execution failure.
    Use an in-memory/fake `QaAgentPort`; no test requires a live external Agent
    Server.
  - Verify explicit SDK branching: no active QA invokes legacy behavior, active
    QA invokes only QA behavior, and lookup/persistence/forwarding errors never
    leak into the legacy path. Use fakes for both ports.
  - Add 1 MiB payload boundary tests without allocating unbounded test data.
  - Confirm existing Test Scenario authoring, SDK WebSocket authentication,
    stream signaling, and OpenAPI tests remain green.

- [ ] **Step 8: Contract documentation and rollout**
  - Update springdoc-visible DTO/controller annotations and
    `OpenApiDocumentationIntegrationTest.kt`.
  - If requested after implementation, publish the generated contract through
    the repository's `insomnia-sync` workflow; do not edit a local Insomnia
    database.
  - Update this plan with final files, deviations, validation evidence, and
    residual risks before review.

## Validation

- **Commands to run:**
  - `./mvnw test -Dtest='*Qa*Test,*ArtelWebSocketIntegrationTest,*OpenApiDocumentationIntegrationTest'`
  - `./mvnw test`
  - `./mvnw clean test` (required by the local `flyway-migration` skill)
  - `git diff --check`
- **Expected output:**
  - Flyway applies V1 through V7 in the test profile without checksum,
    PostgreSQL/H2 compatibility, or ordering failures.
  - QA API tests prove JWT and project-membership isolation.
  - History queries select stable, non-overlapping keysets while each response
    page is chronological and terminates with `hasMore=false` and
    `nextBeforeId=null`.
  - Initial SSE `afterId` and reconnect `Last-Event-ID` emit every committed row
    exactly once in ascending live-display order with event name `log`;
    terminal streams replay their final status and complete.
  - Rolled-back writes emit nothing, concurrent subscribers retain independent
    ordered streams, and duplicate ACTION delivery produces one SDK execution.
  - SDK bridge integration preserves JSON-RPC action fields and records all
    three bidirectional log pairs before forwarding.
  - Existing test suites pass without Agent Server or Unity SDK changes.

## Risks & Rollback

- **Risks:**
  - The Agent QA contract is not implemented in this repository and may change;
    isolate it behind QA-specific DTOs/session service and lock the contract
    before coding.
  - R2DBC transactions do not make network sends atomic with database writes.
    Persist-before-send plus idempotent `messageId` prevents duplicate Agent
    actions, but delivery remains at-least-once/recoverable rather than atomic.
  - In-memory Agent/SSE routing is process-local. A multi-instance deployment
    requires sticky ownership or a shared event/session transport; this issue
    does not introduce Redis/Kafka.
  - A replay/live race can lose or duplicate logs if implemented as a naive
    query followed by subscription. Subscription-first buffering and log-ID
    de-duplication are required.
  - H2 test SQL may not fully prove PostgreSQL partial-index behavior; validate
    migration against PostgreSQL in CI or a local container when available.
  - Existing `AgentClient` globally posts SDK state/results without QA context.
    Removing that fallback prematurely could regress non-QA consumers.
- **Rollback steps:**
  - Deploy application code before/with the additive V7 migration; old code
    ignores the new tables.
  - Roll back runtime behavior by reverting the application commit. Leave the
    additive tables in place during immediate rollback because they do not
    affect existing paths and retain audit data.
  - Only drop `qa_log` and then `qa_try` in a separately reviewed forward
    migration after confirming no retained QA audit data is required. Never
    edit or delete the already-applied V7 migration.

## Open Questions
- What exact JSON shape does the SDK return for batched `ACTION_RESULT`, and
  which ID reliably correlates it to the Agent `ACTION`/individual JSON-RPC
  request?
- Is an Agent or SDK disconnect immediately an execution `FAILED`, or may the
  try remain resumable for a bounded interval? In either case, `FAILED` remains
  an execution failure and never a QA verdict.
- Does deployment run more than one orchestration-server instance? If yes,
  process-local Agent/SSE routing needs an explicit sticky-session or shared
  transport decision before production rollout.

## Implementation Evidence

- Added Flyway V7, the QA persistence/API/SSE services, internal Agent port,
  deterministic SDK action/result bridge, and active-run disconnect failure
  handling.
- Added the confirmed QA-specific production HTTP/WebSocket Agent adapter while
  keeping the existing Test Scenario adapter isolated.
- Validation completed:
  - `./mvnw -q -o -DskipTests -Duser.home=/tmp/artel-userhome -Dkotlin.compiler.daemon=false compile`
    — passed.
  - `./mvnw -q -o -Duser.home=/tmp/artel-userhome -Dkotlin.compiler.daemon=false -Dtest=QaAgentEnvelopeTest test`
    — passed.
  - `git diff --check` — passed.
- Validation blocked by the execution environment:
  - `ArtelWebSocketIntegrationTest` reached test startup but Mockito inline
    mock-maker could not attach under sandboxed JDK 25.
  - `DatabaseConnectionTest` could not open the configured local PostgreSQL
    socket (`Operation not permitted`), so Flyway V7 still needs PostgreSQL
    execution in an environment with DB access.
