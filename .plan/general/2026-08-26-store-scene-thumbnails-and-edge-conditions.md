# 2026-08-26 — Store scene thumbnails and expose edge conditions

- Date: 2026-08-26
- Jira: ARTEL-502 (umbrella ARTEL-501; SDK ARTEL-503, home ARTEL-504)
- Branch: `feat/orchestration-씬-대표-이미지를-저장하고-서명해-낸다-ARTEL-502`
- Base: `feat/orchestration-씬마다-조작-단계와-조건을-함께-낸다-ARTEL-496`, not `develop` — the edge
  condition work reuses `ConditionNodeResponse`, which that branch introduces. Retarget the PR to
  `develop` after ARTEL-496 merges.
- Status: Implemented, tests green, PR not opened

## Goal

Store per-scene evidence thumbnails, expose signed thumbnail views, and carry each scene edge's normalized transition condition.

## Non-goals

- Proxying image bytes through orchestration.
- Replacing runtime screen captures.

## Context / Constraints

Evidence JSON digest and duplicate detection must remain stable. Old SDK registration bodies and existing content maps must continue to work.

## Approach (Checklist)

- [x] **Step 0: Recon** — inspect evidence tickets, registration, ingest, scene image columns, and edge DTOs.
- [x] **Step 1: Persistence/API** — add scene-capture rows, batch upload tickets, optional registration metadata, and signed scene thumbnails.
- [x] **Step 2: Edge contract** — join and normalize capability condition trees into edge responses.
- [x] **Step 3: Tests** — migration, validation, ingest, authorization, compatibility, and wire-contract coverage.
- [ ] **Step 4: Rollout / Rollback** — deploy additive migration/API before consumers; revert code while leaving additive schema if needed.

## What landed

- `V54__add_content_map_scene_capture.sql` — `content_map_scene_capture` table plus four nullable
  image columns on `scene`. V52 는 ARTEL-553 이, V53 은 ARTEL-554·555 가 origin 에서 먼저 claim 해 두 번 옮겼다.
- `POST /api/game-builds/{gameBuildId}/content-map/scene-captures/tickets` — batch presigned upload
  tickets, JPEG only, one per scene, object key prefixed with the build id.
- `RegisterEvidenceDocumentRequest.sceneCaptures` — optional; each entry is either a success
  (`objectKey`/`contentType`/`width`/`height`) or a failure (`failureCode`), never both.
- `EvidenceDocumentService.replaceSceneCaptures` — deletes and rewrites per document, so a later run
  cannot leave a stale image beside this run's failure. Runs on the new, duplicate-digest, and
  race-loser registration paths.
- `ContentMapIngestService.applySceneCaptures` — copies capture rows onto scene rows after ingest,
  for the case where registration arrived before the scenes existed.
- `ContentMapViewService` — `scenes[].thumbnail` (`available`/`unavailable`) with a signed URL, and
  `edges[].given` carrying the normalized condition tree.

## Validation

- **Commands run:** `./mvnw -Dtest='kr.artel.orchestration.contentmap.**' test`
- **Result:** 280 tests, 0 failures (2026-08-26). New: `SceneCaptureRegistrationTest` (11) and two
  cases added to `ContentMapViewGoldenTest`.
- **Also run:** `scripts/check-flyway-migrations.sh` — OK, no version collisions.
- **Not run:** manual check against a live SDK. No SDK build emits `sceneCaptures` yet (ARTEL-503).

## Risks & Rollback

- **Risks:** signing many thumbnails increases response work; malformed manifests must not attach
  another build's object — the build-id key prefix and a storage `head` check guard this, and both
  are covered by tests.
- **Residual risk:** the signed thumbnail URL uses the storage default TTL (5 minutes). A Content Map
  page left open longer than that will show broken images until reload.
- **Rollback steps:** revert endpoints and DTO fields; the additive table and nullable columns may stay.

## Open Questions

- Should the thumbnail URL TTL be raised above the storage default so a long-open Content Map page
  keeps rendering? Deferred until ARTEL-504 shows how long the page actually stays open.
