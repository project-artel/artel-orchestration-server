# Tell the agent which screen settled

Jira: ARTEL-668 · base `feat/…-ARTEL-655` (PR #212, V61)

## Problem

ARTEL-657 gave the QA agent two tools for correcting the per-scene whitelist that
decides screen identity. Both are meant to be called when the game plainly shows a
different screen and the map still names the same one — so the agent must be able
to see what the map currently says.

Today the only frame carrying that is `SCREEN_SELECTOR_PROPOSAL`, and it fires
exactly once ever per `(scene, selector)`, enforced durably by
`uk_screen_selector_proposal`. On a build that has already been played no proposal
fires at all, `ScreenMap` on the agent side stays empty for the whole run, and the
two tools are unreachable in practice.

## What this issue owns

One new orchestration → agent frame, `SCREEN_SETTLED`, sent when an observation
settles a screen and that screen is not the one settled before.

| Type | Direction | Meaning |
|---|---|---|
| `SCREEN_SETTLED` | ORCHE_TO_AGENT | the map now says you are on this screen |

Payload reuses the proposal's screen refs verbatim — `scene`, `previous_screen`,
`current_screen` with `screen_id` / `name` / `discriminator` — so agent-server
needs no new model and no new rendering: one enum member and one router entry that
calls the `ScreenMap.apply` it already has.

## Design decisions

- **A fact, not a question.** `SCREEN_SELECTOR_PROPOSAL` asks whether a selector
  identifies a screen and is answered; `SCREEN_SETTLED` reports what the map
  decided and is answered by nobody. Conflating the two is what made the screen
  verdict unreachable, so they are two types and the settled frame claims nothing
  in `screen_selector_proposal`.
- **Sent on change, never per pulse.** The measured run holds 14,489 pulses and 3
  screen rows. The frame rides the same place a `screen_transition` row is written
  — after `ScreenFold.confirm` — and only when the settled screen row differs from
  the one settled before.
- **An empty `discriminator` is the message, not an error.** ARTEL-654 decided a
  scene whose whitelist is empty or thin collapses to one screen. That is exactly
  the state the two tools exist to fix, so it is reported plainly and the agent's
  `_told_apart_by` already spells it out.
- **The run never depends on it.** Delivery failures are swallowed the way the
  proposal's are, and no agent session at all leaves screen recording untouched.

## Approach

1. `ScreenSelectorFrames` — add `SETTLED` and `ScreenSettledPayload`, reusing
   `ScreenSelectorSceneRef` / `ScreenSelectorScreenRef`.
2. `ScreenRefs` — lift `screenRefOf` / `discriminatorOf` out of
   `ScreenSelectorProposalService` so both senders build the same ref.
3. `ScreenSettledService` — build and send the frame, swallowing failures.
4. `ScreenObservationService.settle` — announce after `fold.confirm`.
5. `QaScreenSelectorPort` → `QaScreenFramePort`, now carrying both sends.
6. `QaLogService.TYPES` + `V62` — open both `qa_log.type` gates.
7. `docs/screen-selector-frames.md` — five frames.

## Validation

- `./mvnw test`
- New `ScreenSettledFrameTest`, whose acceptance case replays a scene after every
  proposal has already been claimed: zero proposals go out, the settled frame
  still does.

## Risks & Rollback

- **Risk:** a future change that makes `settle()` run on an unchanged
  discriminator would turn this into a per-pulse frame. The `fromScreenId !=
  screenId` guard is the contract's own statement of the rule rather than an
  optimisation, and a test pins it.
- **Rollback:** `git revert`. `V62` only widens a CHECK constraint; reverting it
  needs `QaLogService.TYPES` reverted in the same step or the parity test fails.
