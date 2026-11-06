# Ask about unknown screen selectors and fold the screens an answer collapses

Jira: ARTEL-655 · base `develop` (V60, PR #201 merged)

## Problem

ARTEL-654 made the screen `discriminator` carry only selectors on the per-scene
whitelist. Nothing ever adds to that whitelist: it is seeded from
`capability.control_selector` (24 of 472 capabilities carry one) and never grows.
Measured on `artel_integration`, `TurnBattleScene` should be 3 screens and is 2 —
`CombineSystem[7]/CombineZone[1]/Zone1[0]` and `Zone2[1]` are the two selectors
that split the missing screen and neither is a capability control selector.

Merging is the silent failure direction. Over-splitting is noisy; under-splitting
looks like a working map that quietly answers the wrong screen.

## What this issue owns

All three orchestration-side pieces, so ARTEL-657 touches only agent-server:

1. the proposal frame that goes out when a reading holds a selector that is on
   neither the whitelist nor the already-asked list
2. the inbound handler for the answer, which stores whitelist entries and folds
   the screens that become equal
3. the inbound handler for the QA agent's two tools

## Frames

| Type | Direction | Meaning |
|---|---|---|
| `SCREEN_SELECTOR_PROPOSAL` | ORCHE_TO_AGENT | ask about candidates in one scene |
| `SCREEN_SELECTOR_VERDICT` | AGENT_TO_ORCHE | answer to one proposal |
| `SCREEN_SELECTOR_RULE` | AGENT_TO_ORCHE | QA agent tool edits the whitelist |
| `SCREEN_SELECTOR_RESULT` | ORCHE_TO_AGENT | answer to both writes |

The answer is a list of whitelist entries, never "this screen equals screen N":
a per-screen verdict has to be asked again on the thirtieth card draw. Entries
are exact strings, never regular expressions — they are evaluated in Kotlin and
in SQL, and a wrong regex matches everything, silently.

Contract document: `docs/screen-selector-frames.md`.

## Design decisions

- **Folding is a set operation.** `fold_scene_screens(scene_id)` applies the
  whitelist to every screen of one scene, groups by the resulting discriminator,
  and collapses each group onto its lowest id. Never a pairwise merge in arrival
  order. The body is V60 sections 4-8, scoped to one scene, and it calls the same
  `screen_defining_selector` the runtime mirrors.
- **Folding is one-way.** Adding an entry later does not re-split what folded;
  the excluded value was never recorded.
- **Ask once.** `screen_selector_proposal` carries `UNIQUE (scene_id, selector)`.
- **Never block.** The screen row is written first; the proposal is sent after
  and its failure is swallowed.
- **The scene cap coarsens.** Hitting `MAX_SCREENS_PER_SCENE` now sends a
  proposal whose candidates are the selectors currently splitting that scene,
  asking which to drop, instead of only logging a warning.

## Validation

- `./mvnw test`
- replay of the 14489 recorded pulses in `artel_integration`: seeds only →
  `TurnBattleScene` 2 screens; seeds + Zone1/Zone2 → 3 screens
- order independence: the same answer set applied in every order, final state
  compared
