# Screen Selector Frames

The QA WebSocket contract for deciding **which selectors identify a screen**.
Orchestration owns the list and the frames; agent-server answers.

Implemented by ARTEL-655 (orchestration). Consumed by ARTEL-656 (the judging
agent) and ARTEL-657 (the QA agent's two tools).

Kotlin definitions: `contentmap/observe/ScreenSelectorFrames.kt`. When this
document and that file disagree, the file is right.

## Why there is anything to answer

A screen is identified by its `discriminator`: the on/off state of the selectors
that matter in that scene. ARTEL-654 inverted the default so that only selectors
on a per-scene whitelist (`scene_screen_selector`) enter it. Everything else is
ignored, which is what stopped screen counts from tracking play length — one
measured scene went from 29 rows to 2.

The whitelist is seeded from `capability.control_selector` (24 of 472
capabilities on the measured build carry one) and nothing else ever adds to it.
That is the hole these frames fill. The failure direction matters: too few
entries means two genuinely different screens merge into one row, and **merging
is silent**. Too many means noisy over-splitting, which at least shows up.

Three machine-only rules for deciding what identifies a screen were tried and
all three have counterexamples, which is why the answer is to ask rather than to
infer:

| Rule | Counterexample |
|---|---|
| instance count in one reading | misses games that put counters in object names (`agent(1)`, `agent(2)`), and wrongly drops two same-named sibling controls such as a confirm and a cancel button |
| how many readings a selector appeared in | only knowable in hindsight, and the table grows with play length when names change every time |
| "changed with no action, so it does not identify" | a loading screen becoming the game screen refutes it |

## The four frames

```
ORCHE_TO_AGENT  SCREEN_SELECTOR_PROPOSAL   orchestration asks about candidates in one scene
AGENT_TO_ORCHE  SCREEN_SELECTOR_VERDICT    the answer to one proposal
AGENT_TO_ORCHE  SCREEN_SELECTOR_RULE       the QA agent's tools edit the list directly
ORCHE_TO_AGENT  SCREEN_SELECTOR_RESULT     the answer to both writes
```

All four ride the existing QA envelope (`messageId`, `type`, `qaTryId`,
`correlationId`, `timestamp`, `payload`) and all four are recorded in `qa_log`.

### Two rules the answer format enforces

**The answer is whitelist entries, not "this screen equals screen N".** A
per-screen verdict has to be asked again on the thirtieth card draw. A rule is
asked once.

**No regular expressions in stored entries.** Entries are evaluated in Kotlin
when building the `discriminator` and in SQL when folding; `java.util.regex` and
POSIX ARE differ, and one entry that matches in one engine and not the other
splits the same screen into two discriminators. The second reason is that an LLM
writes these: a wrong exact string matches nothing, while a wrong regex matches
everything, silently. If you need "this path and everything under it", use
`subtree` — it matches at node boundaries, so `Zone1` does not match
`SomeZone1Extra`.

## `SCREEN_SELECTOR_PROPOSAL` (orchestration → agent)

Sent when a reading holds a selector that is on neither the whitelist nor the
already-asked list, or when a scene hits the screen cap. The screen row is
written first and the run never waits for an answer.

```json
{
  "reason": "unknown-selector",
  "scene": { "scene_id": "12", "name": "TurnBattleScene" },
  "previous_screen": {
    "screen_id": "226",
    "name": null,
    "discriminator": [{ "selector": "Canvas[7]/Button (Legacy)[0]", "active": true }],
    "capture_url": null,
    "capture_expires_at": null
  },
  "current_screen": {
    "screen_id": "227",
    "name": null,
    "discriminator": [
      { "selector": "CombineSystem[7]/CombineButton[0]", "active": true },
      { "selector": "CombineSystem[7]/CombineZone[1]/Button[2]", "active": false }
    ],
    "capture_url": "https://…",
    "capture_expires_at": "2026-08-28T09:00:00Z"
  },
  "changes": [
    { "selector": "CombineSystem[7]/CombineZone[1]/Zone1[0]", "was": false, "now": true }
  ],
  "candidates": [
    {
      "selector": "CombineSystem[7]/CombineZone[1]/Zone1[0]",
      "path": "CombineSystem/CombineZone/Zone1",
      "active": true,
      "instances_in_reading": 1,
      "readings_seen_in_scene": 431,
      "distinct_values_observed": 1,
      "in_whitelist": false
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `reason` | `unknown-selector` — a selector outside the list changed state. `scene-screen-cap` — this scene hit `MAX_SCREENS_PER_SCENE` (32); the list is too fine and the answer should **remove** entries |
| `previous_screen` | the screen settled before the current one; `null` on a run's first screen or after a server restart |
| `current_screen` | what the map says you are on right now; `null` before any screen has settled |
| `changes` | what moved between the previous reading and this one. `was: null` means first seen |
| `candidates` | what to answer about. For `scene-screen-cap` these are the selectors **currently splitting** the scene (`in_whitelist: true`) |

Candidate statistics exist so a reader who knows nothing about the game can
judge. None of them is a rule — each is one of the three that failed above:

- `instances_in_reading` — objects in this reading sharing the candidate's
  index-stripped path. Several suggests something spawned, but two same-named
  sibling controls land here too
- `readings_seen_in_scene` — readings in this scene that held it. In-process
  memory; a restart resets it to 0
- `distinct_values_observed` — how many distinct selector strings fold to the
  same path. `Card(Clone)[37]` and `Card(Clone)[38]` are two. `1` means a fixed
  piece of UI whose hierarchy index does not move

At most 12 candidates and 32 changes ride one proposal. Anything cut is not
marked as asked, so it comes back on a later reading.

**Each `(scene, selector)` is asked exactly once, ever.** A second proposal for
the same target is not sent while one is outstanding, and not sent again once
answered — even if the answer said nothing about that selector. Without this,
drawing a card would spawn a proposal per draw.

## `SCREEN_SELECTOR_VERDICT` (agent → orchestration)

The answer to one proposal. Set the envelope's `correlationId` to the proposal's
`messageId`; `proposal_id` in the payload is accepted as a fallback. The scene is
resolved from the proposal record, not from the payload — by the time a late
answer lands the QA agent may be standing somewhere else.

```json
{
  "proposal_id": "0b0f…",
  "entries": [
    {
      "match": "selector",
      "pattern": "CombineSystem[7]/CombineZone[1]/Zone1[0]",
      "screen_defining": true,
      "reason": "The combine panel's drop zones are visible only while the panel is open."
    }
  ],
  "note": null
}
```

`entries: []` is a valid answer and means "none of these identify a screen" —
the default is to ignore, so there is nothing to store. When the model breaks
format, answer with no entries rather than inventing one.

## `SCREEN_SELECTOR_RULE` (agent → orchestration)

What the QA agent's two tools send: "use this selector for screen identity" is
`screen_defining: true`, "ignore this one" is `false`. One frame, because both
store the same row shape.

```json
{
  "scene": "TurnBattleScene",
  "entries": [
    {
      "match": "path",
      "pattern": "CombineSystem/CombineZone/Zone1",
      "screen_defining": false,
      "reason": "Always on while the scene is loaded; it never told the two screens apart."
    }
  ]
}
```

`scene` must be the scene the agent is standing in. The whitelist is per scene,
and another scene was not observed from where the agent stands.

## Entry shape

| Field | Rule |
|---|---|
| `match` | `selector` (one exact selector string), `path` (one selector with every sibling index stripped: `CombineSystem[7]/CombineZone[1]/Zone1[0]` → `CombineSystem/CombineZone/Zone1`), or `subtree` (that path and everything below it, at node boundaries) |
| `pattern` | an exact string, at most 512 characters. Never a regular expression |
| `screen_defining` | `true` puts it in the discriminator; `false` is an explicit exclusion that punches a hole in a broader entry |
| `reason` | required. An entry nobody can retrace is an entry nobody can decide to remove |

Stored entries carry `source = agent`. Precedence when several entries match one
selector: source first (`human` > `agent` > `static-analysis`), then
specificity (`selector` > `path` > `subtree`), then the longer `subtree`
pattern. The same rule is implemented twice on purpose — `ScreenSelectorWhitelist.defines`
in Kotlin and `screen_defining_selector` in SQL — and a test drives every
observed selector through both to prove they agree.

## `SCREEN_SELECTOR_RESULT` (orchestration → agent)

Answers both writes; `correlationId` echoes the request's `messageId`.

```json
{
  "type": "SCREEN_SELECTOR_RULE",
  "scene_id": "12",
  "accepted": [
    { "match": "path", "pattern": "CombineSystem/CombineZone/Zone1", "screen_defining": false }
  ],
  "rejected": [
    { "match": "selector", "pattern": "Canvas[2]/nope[9]", "reason": "pattern matches nothing observed in this scene: Canvas[2]/nope[9]" }
  ],
  "folded_screens": 1
}
```

Rejection reasons you can get back:

- `match must be one of selector, path, subtree: …`
- `pattern is required` / `pattern is longer than 512 characters`
- `screen_defining is required`
- `reason is required`
- `pattern matches nothing observed in this scene: …` — including anything that
  looks like a regular expression, since `.*` is not literally equal to any
  selector. Validation is skipped only when this process has observed nothing in
  that scene at all (a restart with no proposal history), because rejecting
  everything there would leave no way to fix the list
- `SCREEN_SELECTOR_RULE references an unknown scene: …`
- `SCREEN_SELECTOR_VERDICT references an unknown proposal: …`

## What happens after an answer

Entries are stored, then the scene is folded **once**:

1. apply the whitelist to every screen of that scene, rewriting each
   `discriminator` to the entries that still identify
2. group the screens whose rewritten `discriminator` is equal
3. collapse each group onto its lowest id, summing `observed_count`, moving
   `screen_capability`, `screen_transition`, `capability_observation` and
   `knowledge_anchor`, and deleting transitions that became self-loops

This is `fold_scene_screens(scene_id)` in SQL (V67), which is V60's retroactive
fold scoped to one scene. It is a set operation over (the scene's screens, the
current whitelist) and never a pairwise merge in arrival order, so **answers
arriving in any order end at the same state**. A test applies the same answer set
in all six orders and compares the final rows.

`folded_screens` is 0 for an answer that only **adds** entries, and that is
correct rather than a bug:

- **Adding does not re-split the past.** The selector's value was never recorded
  in those discriminators, so there is nothing to restore. New splits begin at
  the next observation
- **Removing does fold the past.** The value to delete is right there in the
  record

The asymmetry is the design, not an oversight. A later entry that re-enables a
selector cannot bring back a value an earlier exclusion already erased.

## Degradation

If no answer ever comes, screen recording continues exactly as it does today:
selectors outside the list stay ignored, screens keep settling, `observed_count`
keeps counting. A proposal that cannot be delivered (no active QA try, or the
agent session has not attached yet) releases its claim so the question can be
asked again later.
