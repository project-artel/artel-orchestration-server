# Make a scene the evidence never named

Jira: ARTEL-689 · base `origin/develop` (V73 is the highest merged migration)

## Problem

`ScreenObservationService.record` resolves the scene by name and gives up when it
is not there:

```kotlin
val contentMap = contentMaps.findByGameBuildId(buildId) ?: return
val contentMapId = contentMap.id ?: return
val sceneName = fold.scene ?: return
val scene = scenes.findByContentMapIdAndName(contentMapId, sceneName) ?: return
```

A `pulse` naming a scene the `evidence` document never mentioned falls out at the
last line, with no log and no counter. Everything that scene would have produced
is lost: its `screen` rows, its `screen_transition` rows, its `screen_capability`
links, and every `SCREEN_SELECTOR_PROPOSAL` and `SCREEN_SETTLED` frame that would
have reached the agent.

Three ways a run hits it:

- a build that gained a scene after its `evidence` document was made
- a scene the `evidence` scan missed
- **a build with no `evidence` document at all.** ARTEL-642 opened the path where
  a QA run alone produces the map; on that path every scene is unknown

The third is the one that makes this worth doing now. It is also the one that the
scene fix alone does not repair — see the first decision below.

The schema has been open for this since ARTEL-642 (V63) and nothing writes to it.
`scene.origin` takes `evidence` and `observed`; `content_map.rooted_by` takes
`evidence` and `observation`. Every writer in the tree writes `evidence`.

## Design decisions

- **The map is rooted by observation too.** `content_map` rows are created in
  exactly one place — `EvidenceDocumentService.upsertContentMap`, on document
  registration. So on a build with no document there is no map, and creating the
  scene never runs. Splitting that into a separate change would leave this one
  fixing nothing on the case that motivated it, so `ScreenObservationService`
  roots the map with `rooted_by = 'observation'` when it is missing.
  `upsertContentMap` already raises `rooted_by` to `evidence` and never lowers
  it, so a document arriving later needs no new code.
- **Three refusals, not a whitelist.** The name arrives from the SDK and is
  usually real; refusing unknown names would empty the map again on exactly the
  path this fixes. `ObservedSceneName` refuses a blank name, a name longer than
  `scene.name`'s 255 characters, and `DontDestroyOnLoad`. No game's scene names
  appear in the code — the SDK attaches to arbitrary Unity games, and a list
  taken from one game either filters nothing or filters a real scene in the next.
- **`DontDestroyOnLoad` is named as a string here and nowhere else.** The
  `evidence` side does not string-match it: the document carries a `scenes[]`
  array that says what a scene is, and `PersistentSceneAttribution` filters
  against that array (ARTEL-460). A `pulse` carries no such array — the name is
  the only thing that says what scene this is — so there is no structure to
  filter with and the one engine-made pseudo-scene is named outright. Letting the
  observation path seat it would put back the row ARTEL-460 removed, and the test
  cases built from it read `DontDestroyOnLoad scene 이 실행 중이다` as a
  precondition and cannot run.
- **Ingest promotes an observed scene in place.** `upsertScenes` already matches
  by `(content_map_id, name)` and writes `origin = 'evidence'` onto the row it
  finds, so a document naming a scene a QA run already recorded raises that row's
  `origin` and keeps its id. Keeping the id is the point: the `screen`,
  `screen_transition`, `screen_capability`, and `scene_edge` rows hanging off it
  survive. `uk_scene_map_name` is never challenged because no second row is
  written. This plan adds the test that holds it, not the behaviour.
  `retireVanishedScenes` already refuses to delete anything but
  `origin = 'evidence'`, so an observed scene is never swept.
- **Observed is visible in all three places a reader looks.** The row already
  carries `origin`. `ContentMapSceneResponse.origin` carries it into the browser
  response and `ContentMapSummaryResponse.rootedBy` says where the map itself came
  from — V63 §5 wrote down why that cannot be inferred from empty header columns.
  `SceneContextEntry.origin` carries it into what the agent reads, beside
  `knownToContentMap`: the two answer different questions, and a scene nobody has
  a document for is a weaker claim than one static analysis described.
- **Failure keeps the run.** `observe` already swallows and warns, so nothing new
  is needed for the unexpected case. The two expected ones do not throw at all: a
  refused name returns, and a lost `INSERT` race re-reads the row the winner
  wrote. The refusal warning is gated on `ScreenFold.sceneChanged` because the
  measured run holds 14,489 pulses and a per-pulse warning would be a louder
  failure than the silence it replaces.
- **No migration.** V63 already added both columns and both `CHECK` constraints,
  and no view reads scene `origin`. The sweep over every `.worktrees/*` and every
  `origin/*` branch found V72 (ARTEL-680) and V73 (ARTEL-685) as the highest
  claimed numbers; both merged into `develop` while this branch was being
  written, and this branch claims no number of its own.
  `scripts/check-flyway-migrations.sh` reports `OK: no version collisions`
  against the rebased base.

## Approach

1. `ObservedSceneName` — the three refusals and the reasoning behind them.
2. `ScreenFold.sceneChanged` — one boolean set where `statsScene` already resets.
3. `ContentMapRepository.rootByObservation` — `INSERT … ON CONFLICT
   (game_build_id) DO NOTHING RETURNING id`.
4. `SceneRepository.insertObserved` — the same shape against `uk_scene_map_name`.
5. `ScreenObservationService.record` — `rootedMapId` and `sceneIdOf` replace the
   two `?: return` lines.
6. `ContentMapSceneResponse.origin`, `ContentMapSummaryResponse.rootedBy`,
   `SceneContextEntry.origin`, and the three call sites that fill them.
7. `ContentMapIngestService.upsertScenes` — the KDoc says what the promotion is
   for now that something writes `observed`.
8. Tests, and the `docs/api/openapi.json` snapshot `OpenApiSnapshotTest` drops.

## Collision with PR #225

ARTEL-680 edits `SceneContextDtos.kt` and `SceneContextService.entryOf` — the same
two regions step 6 touches. It merged as PR #225 while this branch was being
written, along with ARTEL-685 as PR #226. This branch was rebased onto the
resulting `develop`; `entryOf` conflicted in one hunk and was resolved by keeping
both changes — the `status` partition into `capabilities` / `notAStepCapabilities`
and the new `origin` field. Nothing else overlapped: neither PR touches the
observation path.
