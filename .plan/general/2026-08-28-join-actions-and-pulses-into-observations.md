# Join actions and pulses into observations

Jira: ARTEL-450 · base `feat/…-ARTEL-668` (PR #214, V62)

## Problem

`capability` rows come only from static analysis of evidence documents. In the
measured database that is 472 capabilities, of which exactly one has
`origin = observed` and one is `confirmed`; everything else is
`evidence` / `unverified`. The map records what the code says is possible and
learns nothing from actually playing the game.

Two channels already carry the halves of the answer and nothing joins them:

```
action channel (ACTION → ACTION_RESULT)   "clicked this object, and it worked"   t
state channel  (pulse)                    "at reading n these things changed"    t+α
```

The SDK deliberately does not put "did the thing you pressed actually fire" on
the state channel, so causality has to be built by the reader.

## What this issue owns

Writing `capability_observation` rows. Nothing else. `capability.verification`,
`capability_effect`, `screen_capability.fired_count` and
`screen_transition.capability_id` are untouched — promotion is ARTEL-451 and an
explicit non-goal.

The seat is already built: `capability_observation` arrived with V40, including
`screen_id`, `reading_before` / `reading_after`, `fired`, `observed_effects`,
`action_method` / `action_params` and `attempts`. **No migration.**

## What counts as attributable

A pulse is not evidence of the action before it. Games animate, spawn and tick on
their own — every one of the 14,489 pulses in the measured run carries a
non-empty `changed`, and five enemy-animator selectors alone account for ~20,000
of those changes. "Something changed" is true 100% of the time and is therefore
not a signal.

`ActionTimeline` carries the reasoning; five rules:

1. **Only actions whose aim is named.** `button_click(instanceId)` names an
   object and the pulse gives that id a selector; `key_click(name)` names an
   input and the map says which capabilities bind it in that scene. `move_mouse`,
   `mouse_down`, `mouse_up`, `capture_screen` and `set_axis` name a coordinate or
   nothing — 302 of the run's 394 actions — and produce no observation. Guessing
   what sat under a coordinate is the most expensive error this feature can make,
   because the guess becomes ARTEL-451's promotion evidence.
2. **Only actions the SDK accepted.** A rejected action never reached the button;
   recording it as `fired=false` blames the game for our own miss. Four clicks on
   non-buttons were rejected in the measured run. A rejection instead raises the
   next success's `attempts`.
3. **The window is exclusive.** It closes at the first of *N* pulses, a wall-clock
   cap, or the next dispatch. If a second action goes out inside the first's
   window, the changes there have two causes and neither action gets a row. A
   window that caught no pulse at all is discarded — nothing seen is not the same
   as nothing happened.
4. **The window is written in `reading` numbers.** SDK reading numbers ran to
   30,290 while we received 14,036 pulses, so roughly half never arrive. Ordering
   is still decided by arrival — live, that is all there is — but the recorded
   boundary is the SDK's own sequence so a later reader need not trust our clock.
5. **Background is subtracted.** A change counts only if it did not also occur in
   the equal-length window immediately before the action. The control group is
   the same game a few hundred milliseconds earlier: the animators shake on both
   sides and drop out, the tutorial window a click opened appears only after.

What survives all five is still **correlation**. `fired=true` alone must never
promote anything; `observed_effects` versus expected effects is what ARTEL-451
compares, and `fired=false` stays ambiguous between "the button is broken" and
"nothing observable existed" until `capability_effect.watchable` is read with it.

## One control, several capabilities

`capability` is one row per evidence branch. The measured
`TitleScene/Canvas[2]/continue[2]` has five rows behind it, `CombineButton` two,
`TurnEndButton` two — and a pulse never says which branch a click took. So the
observation is written for **every** capability behind that control, and the row
claims only "this action went to this control and these things then changed", not
"this capability caused them". Splitting the branches is the effect-versus-
expectation comparison in ARTEL-451; picking one here would make "we know" and
"we guessed among five" indistinguishable, the same call
`SceneEdgeRepository.verifyByScenePair` already made.

That is also why `screen_transition.capability_id` and
`screen_capability.fired_count` stay empty even now: both need one capability
named, and `screen_transition.kind` freezes at first observation.

## Approach

1. `ActionObservationProperties` — window size (4 readings), time cap (2 s),
   effect cap, tracked-object cap.
2. `PulseReading` — read `reading`, `changed`, and each object's `id`.
3. `ActionAttribution` — `ActionTarget` (Control | Key), `DispatchedAction`,
   `ClosedActionWindow`, `ObservedEffect` with `capability_effect`'s own
   `kind` / `target` / `detail` names.
4. `ActionTimeline` + `ActionTimelineRegistry` — the five rules; no DB.
5. `CapabilityObservationService` — resolve a closed window's target to the
   capabilities behind it and write the rows. Implements the new
   `QaActionObservationPort`.
6. Wiring — `QaActionDispatchService` after the SDK send,
   `QaSdkBridgeService.routeActionResult` on the answer,
   `ScreenObservationService.record` on every pulse.

## Cost per pulse

The measured run has 14,489 pulses and 247 dispatched ACTION messages. Per pulse
the observation path folds one map and one bounded deque; it touches the database
only when a window closes, which happened 68 times in that run. Failures are
swallowed with a warning, the same call `ScreenObservationService` already makes:
the observation path must never stop pulse relay.

## Replay of the recorded run

`artel_integration`, one QA session, 394 individual actions in 247 messages:

| | |
|---|---|
| actions with no named aim | 302 |
| rejected by the SDK | 4 |
| window discarded (no pulse, or next action first) | 20 |
| windows closed | 68 |
| closed on a control/key the map does not know | 16 |
| **actions that produced observations** | **52** |
| **`capability_observation` rows** | **120** |
| of those actions, `fired=true` / `false` | 31 / 21 |

Without rule 5 every one of the 68 would read `fired=true`.
