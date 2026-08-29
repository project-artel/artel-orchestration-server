# Capability Write Frames

The QA WebSocket contract for **recording what a run learned about a
capability**. Orchestration owns the frames and the storage; the QA agent
writes.

Implemented by ARTEL-644 (orchestration). Consumed by ARTEL-645 (the QA agent's
two tools).

Kotlin definitions: `contentmap/observe/CapabilityWriteFrames.kt`. When this
document and that file disagree, the file is right.

## The number that decided this

The content map records what static analysis believes and almost never learns
whether it is true. On the measured build (`artel_integration`, 2026-08-29):

| | |
|---|---|
| capabilities | 472 |
| `verification = confirmed` | 2 |
| `interaction = none` | 418 (89%) |
| `interaction = press` / `click` | 27 / 27, of which 24 carry a `control_selector` |

Only 51 capabilities are pressable at all. Comparing pulses before and after an
action — the ARTEL-450 route, built and then set aside — can therefore never
speak about the other 421. Those are things like "defeating an enemy grants a
reward": not something you press, something that happens. Whether it happened is
known by whoever watched the screen. That is the QA agent, so the agent writes.

## The three frames

```
AGENT_TO_ORCHE  CAPABILITY_VERDICT       an existing capability worked, or did not
AGENT_TO_ORCHE  CAPABILITY_DISCOVERED    a capability the evidence never mentioned
ORCHE_TO_AGENT  CAPABILITY_WRITE_RESULT  the answer to both, when accepted
```

A rejection comes back as the existing `ERROR` frame with `correlationId` set to
the request's `messageId` and the reason in `payload.message`. That is the
contract knowledge writes (ARTEL-331) and issue reports (ARTEL-366) already use,
so the agent needs no new branch: one correlation releases the waiting tool, and
the outcome is the frame type.

All three ride the QA envelope (`messageId`, `type`, `qaTryId`, `correlationId`,
`timestamp`, `payload`). `messageId` must be a UUID — the router drops frames
that are not.

**Neither write appears on the QA timeline as its own `qa_log` row.** The record
is the map itself: a `capability` row, a `capability_observation` row, or both.
Rejections do land on the timeline, as `ERROR`. Knowledge writes made the same
choice for the same reason — a success frame that only carries an id is a
derivative of a row that already exists.

## Four rules the shapes enforce

**The reasoning comes with the verdict.** `rationale` is required on both
frames. A bare verdict cannot be checked later: nobody can retrace it, so nobody
can decide it was wrong. A `CHECK` constraint refuses an agent row whose
rationale is blank, so the guarantee does not depend on this service being the
only writer.

**The agent does not overwrite static analysis.** Exactly one statement in this
path updates an existing `capability` row, and its `SET` clause holds
`verification`, `verification_observation_id`, and `updated_at`. Nothing the
agent sends can reach `summary`, `interaction`, `control_*`, or the evidence
sub-tables. New rows are accepted only with `origin` `observed` or `inferred`.
When the agent and the evidence disagree, both records stand and a human decides
— that disagreement is the finding.

**Writable mid-run.** One frame is one transaction. A run that dies halfway
keeps everything written up to that point; nothing waits for the run to end.

**Idempotent, enforced by the database.** Two unique indexes, not two `if`
statements:

- `uk_capability_observation_agent_statement (qa_run_id, capability_id, verdict) WHERE source = 'agent'`
- `uk_capability_agent_statement (scene_id, interaction, coalesce(control_path,''), md5(lower(btrim(summary)))) WHERE origin IN ('observed','inferred') AND merged_into IS NULL`

Note what the first key does *not* contain: `messageId`. A tool that retries
issues a new one, so keying on it would let the same statement through twice.
Note what it *does* contain: `verdict`. Seeing a capability work and later seeing
it fail, in one run, is two statements and two rows. Collapsing them would erase
the more interesting of the two.

## `CAPABILITY_VERDICT` (agent → orchestration)

"I saw this capability work" or "I saw it fail", about a capability the map
already holds.

```json
{
  "scene": "TurnBattleScene",
  "capability_key": "TurnBattleScene|Combat.TurnManager|EndTurn|0|a1b2c3",
  "verdict": "works",
  "rationale": "Clicked Canvas[7]/EndTurn[0]. The turn counter went 3 → 4 and the enemy took its move.",
  "capture_id": "0b0f5d0a-6d1a-4a5f-9d2e-8f6d5c4b3a21",
  "screen_id": null,
  "action": { "method": "button_click", "params": { "instanceId": 4213 }, "attempts": 1 }
}
```

| Field | Rule |
|---|---|
| `scene` | required. The scene the agent is standing in. This is the axis the rejection rule turns on |
| `capability_key` | the stable, reingest-surviving key `v_content_map_capability` publishes. Send this one |
| `capability_id` | the numeric id, for a row that has **no** key — `observed` and `inferred` rows carry `capability_key: null`, so a row you just created is addressed this way |
| `verdict` | required. `works` → `verification = confirmed`; `fails` → `contradicted` |
| `rationale` | required, at most 2000 characters. What you saw, with the identifiers in it |
| `capture_id` | optional. The `captureId` of a `SCREENSHOT` frame **this try** took |
| `screen_id` | optional. Must belong to the named scene |
| `action` | optional. `method`, `params`, `attempts` — what you actually sent |

Send exactly one of `capability_key` and `capability_id`. Sending both, or
neither, is rejected.

**Every id in a request is a JSON string**, not a number — `capability_id`,
`screen_id`, and each element of `based_on`. Responses do the same, for the
reason at the end of `CAPABILITY_WRITE_RESULT`.

Addressing by id, which is how you verdict a row you created a moment ago:

```json
{
  "scene": "TurnBattleScene",
  "capability_id": "3184",
  "verdict": "fails",
  "rationale": "Killed the last enemy three more times. The reward panel never opened again."
}
```

`action` is optional because 418 of 472 capabilities are not pressable; for those
there is no method to record. When it is present it lands on the observation row,
which is where a reproduction is read from — not the content map, which the agent
re-decides every run. `params` is stored as sent, so send a JSON object; it is
never read here. `attempts` defaults to 1 and anything below 1 is stored as 1.

`verdict` moves `verification` directly. There is no rule here that waits for N
observations to agree; recording what the agent said is the whole job. Promotion
and demotion when evidence and observation collide is ARTEL-646.

## `CAPABILITY_DISCOVERED` (agent → orchestration)

A capability the evidence never mentioned.

```json
{
  "scene": "TurnBattleScene",
  "origin": "observed",
  "summary": "Combat.RewardPanel shows a reward line when the last Combat.Enemy reaches hp 0.",
  "given_text": "At least one enemy is alive in the battle.",
  "interaction": "none",
  "input_key": null,
  "input_phase": null,
  "control_path": null,
  "control_label": null,
  "rationale": "Killed the last enemy twice. Both times the reward panel opened with a gold line.",
  "capture_id": null,
  "screen_id": null,
  "verdict": "works",
  "based_on": []
}
```

| Field | Rule |
|---|---|
| `scene` | required, and must already exist in this build's map |
| `origin` | required. `observed` — you pressed it and watched the result. `inferred` — you concluded it. `evidence` and `human` are rejected |
| `summary` | required, at most 1000 characters. Write the identifiers verbatim and only join them with words. Turning `MapMove.position` into "the character moves sideways" is the most expensive false statement this system can hold — on the measured build `MapMove.position` was a lane index, not a screen coordinate |
| `given_text` | optional. The precondition, one line |
| `interaction` | required. `click`, `type`, `press`, `axis`, `none` |
| `input_key` | required when `interaction` is `press`, and forbidden otherwise |
| `input_phase` | optional. `down`, `held`, `up` |
| `control_path`, `control_label` | optional. What you pressed, and the text on it |
| `rationale` | required, at most 2000 characters |
| `verdict` | **required** with `origin: observed`, and forbidden with `inferred`. The row lands verified in one round trip |
| `action` | optional, same shape as on `CAPABILITY_VERDICT`. Only meaningful alongside a `verdict` |
| `based_on` | required and non-empty when `origin` is `inferred`. `capability_observation` ids **from this run** |

`origin: inferred` with a `verdict` is rejected: you cannot both infer something
and have watched it happen. `origin: observed` **without** one is rejected too,
and for a sharper reason — `observed` means you pressed it and watched the
result, so there is a verdict by definition, and the verdict is what carries your
`rationale` into a row. Without it the reasoning would be validated and then
dropped. If you did not watch a result, the honest origin is `inferred`.

### What the server fills in, and why you do not

- `capability_key` stays `null`. The formula is
  `(entry_id, branch_offset, normalized condition_tree)` and an agent row has
  none of those three. A made-up key stops being a key.
- `actionability` is `not-a-step` when `interaction` is `none`, `runnable`
  otherwise. `observability` is `unknown`; `applicability` is `applies`.
  `status` is a generated column derived from those three.
- **An `interaction: none` row is filtered out of `v_content_map_capability`,**
  because that view drops `status = 'not-a-step'`. This is not special treatment
  of agent rows — it is what 418 of the 472 evidence rows already do. Such a row
  is an ingredient for a `given`/`then`, not a standalone test case.
- `observability` stays `unknown` even when you clearly watched something change.
  Effects live in `capability_effect`, and writing them is ARTEL-646. Claiming
  `observable` here would push a row through the test-case window with nothing
  behind it.
- For `origin: inferred`, `capability_inference` is written with `model` and
  `prompt_version` taken from `qa_try` — what the session actually negotiated,
  not what the frame claims.

### Resending a discovery

A repeat of the same discovery returns the existing row with `created: false`
and rewrites none of its description — not the summary, not the interaction, not
the rationale already stored. Letting a second statement overwrite the first
would take the "cannot edit" rule out through the back door.

`verification` is the exception, and deliberately so. A discovery written first
as `inferred` and later sent again as `observed` with a verdict keeps
`origin: inferred` and gains `verification: confirmed` — the two axes doing
exactly what they are for. The second send writes its own observation row, so
its rationale is kept alongside the first rather than replacing it.

The key is content, so a discovery reworded on the second send lands as a second
row. That is the honest failure direction — absorbing a resend is the common
case, and deciding that two differently-worded sentences describe one capability
is ARTEL-646's job, which is also why `capability.merged_into` exists.

A discovery that duplicates a capability the evidence already holds is **not**
detected here. The index is partial and never looks at `origin = 'evidence'`
rows. Keeping the agent from writing those is the tool description's job on the
ARTEL-645 side.

## `CAPABILITY_WRITE_RESULT` (orchestration → agent)

`correlationId` echoes the request's `messageId`.

```json
{
  "type": "CAPABILITY_DISCOVERED",
  "capability_id": "3184",
  "capability_key": null,
  "scene_id": "12",
  "verification": "confirmed",
  "observation_id": "77",
  "created": true
}
```

| Field | Meaning |
|---|---|
| `type` | which request this answers. One result type serves both writes, the way `KNOWLEDGE_WRITE_RESULT` serves five |
| `capability_id` | the row that now carries the statement. **Keep it** — it is how you address an `observed` row on a later `CAPABILITY_VERDICT`, since that row has no key |
| `capability_key` | present for an evidence-derived row, `null` for one the agent created |
| `verification` | `unverified`, `confirmed`, or `contradicted` after this write |
| `observation_id` | the `capability_observation` row this frame wrote, or `null` for a discovery that carried no verdict |
| `created` | `true` only when `CAPABILITY_DISCOVERED` inserted a row. `false` means a resend was absorbed, or the frame was a verdict |

Ids go out as strings. A 64-bit id serialized as a JSON number loses precision in
a JavaScript consumer, and the read APIs already do this.

## Rejection reasons

Every reason is prefixed with the request type, so the log line says which frame
was refused. The list, verbatim:

**Both frames**

- `payload.scene is required`
- `payload.rationale is required — a verdict nobody can retrace cannot be checked later`
- `payload.rationale is longer than 2000 characters`
- `references an unknown scene: <name>`
- `references an unknown screen_id: <id>` / `payload.screen_id must be a numeric id: <value>`
- `screen <id> is not in the scene this frame names`
- `references a capture this try never took: <captureId>`
- `this try does not belong to a qa_run`
- `cannot resolve the game instance of this run`
- `this game instance has no build to attach a capability to`
- `this game build has no content map yet`
- `<TYPE> failed: <message>` — a parse failure or an unexpected error. The run is not failed with it

**`CAPABILITY_VERDICT`**

- `payload.verdict must be one of [works, fails]: <value>`
- `needs exactly one of capability_key or capability_id`
- `payload.capability_id must be a numeric id: <value>`
- `references an unknown capability_key: <key>`
- `references an unknown capability_id: <id>`
- `capability <id> belongs to scene <other>, not <named>`
- `capability <id> has been merged into <id> — send the verdict there`

**`CAPABILITY_DISCOVERED`**

- `payload.origin must be one of [observed, inferred]: <value>`
- `payload.summary is required` / `payload.summary is longer than 1000 characters`
- `payload.interaction must be one of [click, type, press, axis, none]: <value>`
- `interaction press requires input_key, and no other interaction may carry one`
- `payload.input_phase must be one of [down, held, up]: <value>`
- `origin inferred cannot carry a verdict — an inference is not a sighting`
- `origin observed requires a verdict — if you did not watch the result, write it as inferred`
- `origin inferred requires based_on — an inference that names no observation cannot be retraced`
- `payload.based_on must be numeric observation ids: <value>`
- `payload.based_on references observations outside this run: <ids>`

The scene-membership rejection names **both** scenes on purpose. "That capability
is not here" leaves the agent unable to tell whether it named the wrong scene or
picked the wrong target; naming the owner settles it in one message.

An unknown scene is refused rather than created. `scene.origin = 'observed'`
exists for scenes a run finds first, but filling it is the observation path's job
(ARTEL-642 / ARTEL-453), not this one. On the measured build every scene came
from evidence, and the agent stands in scenes the map already knows.

## What the agent can address today, and what it cannot

Measured on the same build, and this is a real limit of the delivered path:

| | |
|---|---|
| capabilities in the map | 472 |
| rows the agent's scene context publishes | 54 |
| rows it does not | 418, every one of them `interaction = none` |

The scene context (`GET /internal/scene-context`, ARTEL-611) reads
`v_content_map_capability`, and that view drops `status = 'not-a-step'` because
it is the test-case generator's input window. So the agent is handed a
`capability_key` for the 54 rows that have an interaction, and none for the 418
that do not.

`CAPABILITY_VERDICT` itself has no such filter — it resolves the key against the
`capability` table, so a verdict on any of the 472 lands correctly the moment the
agent knows the key. The gap is on the read side, not here.

Until that read window widens, use the two frames this way:

- **A capability the scene context listed** — `CAPABILITY_VERDICT` with its key
- **Something that happened which the scene context did not list** —
  `CAPABILITY_DISCOVERED`. Some of these already exist in the map as
  `interaction = none` evidence rows the agent could not see, so the result is a
  second row beside the first. That duplicate is visible (`origin = observed`
  next to `origin = evidence` in the same scene) and folding the two is
  ARTEL-646's job, which is what `capability.merged_into` is for

## Where the rows land

```
capability                     the map row
  origin                       evidence | observed | inferred | human
  verification                 unverified | confirmed | contradicted   ← the verdict moves this
  verification_observation_id  → the statement that moved it

capability_observation         one row per statement
  source = 'agent'             separates a judgement from a pulse-diff measurement
  verdict                      works | fails
  rationale                    what the agent saw
  capture_id                   the SCREENSHOT frame, when there is one
  qa_run_id / qa_try_id        which run, which try
  agent_message_id             which frame
  acted_at                     server clock, not the agent's

capability_inference           only for origin = 'inferred'
  model / prompt_version       from qa_try, not from the frame
  based_on                     the observations the inference stands on
```

`capability_observation` is widened rather than replaced. `capability_inference.based_on`
has meant `[capability_observation.id, ...]` since V40, so an agent statement
stored anywhere else would leave every `inferred` row pointing at rows that do
not exist — nothing else writes that table now that ARTEL-450 is backlogged.

The two kinds of row stay separable: a `source = 'pulse-diff'` row still requires
`action_method` and `fired` and must leave `verdict` and `rationale` empty, and an
agent row must carry `verdict` and a non-blank `rationale`. One `CHECK` holds both
halves. `fired` is left null on agent rows — it asks whether a pulse value moved,
which is a measurement the agent does not make.

`acted_at` is the server clock rather than the envelope timestamp. One agent with
a skewed clock would otherwise bend the run's time axis, and a few milliseconds
does not change any question this table answers.

## Degradation

Every failure **inside this path** is a value, never an exception that escapes. A
frame that cannot be parsed, a capability that does not exist, a database error —
all come back as `ERROR` on the request's correlation and the run continues. A
frame that raised out of the receive chain would close the WebSocket, and the
disconnect would fail the whole run: too high a price for one refused write.

Two router guards sit **in front** of this path and drop a frame with no answer
at all. They are older than these frames and apply to every inbound type:

- `messageId` is not a UUID — nothing is written and nothing is sent
- the `qaTryId` names a try that is unknown or already finished — same

A tool that blocks on a correlation must therefore carry its own timeout. Both
guards exist because an error raised there would travel out of the receive chain
and close the socket.

If the agent session is gone by the time a write finishes, the write still
stands and only the answer is dropped. Knowledge writes made the same call —
losing the answer costs a round trip, losing the write costs what the run saw.
