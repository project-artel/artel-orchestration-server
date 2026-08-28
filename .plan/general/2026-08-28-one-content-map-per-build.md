# 2026-08-28 — 지도를 빌드당 하나로 모으고 문서 없이도 만든다

- Date: 2026-08-28
- Jira: ARTEL-642
- Status: Implemented

## Goal

`content_map` 의 유일 키를 `(game_build_id, capture)` 에서 `game_build_id` 하나로 줄이고, 근거 문서
없이도 행이 설 수 있게 NOT NULL 셋을 뗀다. `capture` 는 사라지지 않고 `scene` 으로 내려가 "이 값을
어느 상태에서 읽었나" 로 남는다.

## Non-goals

- 관측으로 `scene` 이나 `capability` 를 만드는 쓰기 경로. ARTEL-644 가 한다. 이 작업은 스키마만
  열어 둔다.
- `capture` 별 값 쌍의 보존. Story(ARTEL-638)가 그것을 버리는 결정 자체다.
- `screen` 유일 제약. ARTEL-453 이 `V59` 에서 이미 했다.

## Context / Constraints

base 는 `develop` 이 아니라 ARTEL-453 브랜치(PR #213)다. 이 작업이 고치는
`ContentMapViewService.kt` 와 `ContentMapViewDtos.kt` 의 `screen` · `screen transition` ·
화면별 `capability` 판은 아직 `develop` 에 없다.

마이그레이션 번호는 **V63**. `develop` 이 V60, PR #212(ARTEL-655)가 V61, ARTEL-668 브랜치가 V62 를
쥐고 있어 그 위 첫 빈 번호다(`docs/flyway-migrations.md`).

제약:

- **겹친 행 이관이 제약 변경보다 앞이다.** 한 빌드가 지도를 여러 벌 든 채로
  `uk_content_map_build` 를 걸면 실데이터에서 마이그레이션이 죽는다.
- **뷰는 `CREATE OR REPLACE` 로 못 고친다.** Postgres 는 열 추가만 허용하는데 `capture` 가
  `content_map` 에서 `scene` 으로 옮겨간다. V45 처럼 `DROP` 뒤 `CREATE`.
- **응답에서 `capture` 필드를 지우지 않는다.** artel-home 의 `contentMapApi.ts` 가 읽고, null 은
  "알 수 없음" 으로 그려진다.
- `screen` 의 `uk_screen_discriminator`(V59)가 살아 있으므로 행 이관이 그것을 건드리면 안 된다.
  이 작업의 이관은 `scene` 과 `content_map_document` 만 옮기므로 `screen` 은 자기 `scene` 을 따라
  움직이고 판정 키는 그대로다.

## Approach (Checklist)

- [x] **Step 0: Recon** — `content_map` 의 자식은 `scene`(V40)과 `content_map_document`(V41)
      둘이고 둘 다 CASCADE 다. 이슈 본문은 `scene` 만 말하지만 문서까지 옮기지 않으면 진 지도의
      근거 포인터가 사라져 재적재가 불가능해진다.
- [x] **Step 1: 마이그레이션** `V63__collapse_content_map_to_one_per_build.sql`
      1. `scene.capture` · `scene.origin` 추가 — 값을 채우려면 컬럼이 먼저 있어야 한다
      2. `scene.capture` 를 **자기 지도의 옛 `capture`** 로 채운다 (이관 전에)
      3. 진 지도의 `scene` · `content_map_document` 를 이긴 지도로 옮기고 진 행을 지운다
      4. `uk_content_map_build_capture` → `uk_content_map_build`
      5. `evidence_digest` · `schema_version` · `capture` NOT NULL 해제
      6. `content_map.rooted_by` 추가
      7. `v_spec_gap` · `v_content_map_capability` DROP 후 재생성 (`cm.capture` → `s.capture`)
- [x] **Step 2: 엔티티·조회·적재**
      - `ContentMapEntity` — `schemaVersion` · `capture` · `evidenceDigest` nullable, `rootedBy` 추가
      - `SceneEntity` — `capture` · `origin` 추가
      - `ContentMapRepository` — `findByGameBuildId` 하나로 모은다
      - `ContentMapViewService.read` — `capture` 인자와 `selectContentMap` 제거
      - `ProjectContentMapController` — `capture` 질의 인자와 `parseCapture` 제거
      - `EvidenceDocumentService` — 지도 upsert 키가 `game_build_id`, `rooted_by` 를 evidence 로 올린다
      - `ContentMapIngestService` — 문서의 `capture` 를 그 문서가 만진 씬에 적는다
- [x] **Step 3: 테스트** — 기존 capture 분리 전제 테스트를 뒤집고, 합류와 승격을 새로 덮는다

## Validation

- **Commands to run:**
  - `./mvnw test`
  - `./scripts/check-flyway-migrations.sh feat/orchestration-판독에서-화면을-가르고-화면-전이를-남긴다-ARTEL-453`
  - `./scripts/verify-flyway-upgrade.sh feat/orchestration-판독에서-화면을-가르고-화면-전이를-남긴다-ARTEL-453`
  - 실데이터 사본(`artel_integration` 의 `pg_dump`)에 V63 적용 — 이관 전후 행 수 비교
- **Expected output:** 지도가 빌드당 하나로 줄고 `scene` 이 이긴 지도로 따라붙는다.

## Risks & Rollback

- **Risks:** 이름이 겹친 `scene` 은 이긴 쪽이 이기고 진 쪽은 CASCADE 로 사라진다. 그 씬에 매달린
  `capability` · `screen` · 관측도 함께 간다. 실데이터에서 한 빌드가 지도를 여러 벌 든 경우가
  없어 오늘은 0건이지만, 되돌릴 수 없는 삭제라는 사실은 남는다.
- **Rollback steps:** 되돌리는 마이그레이션을 새로 낸다. 합쳐진 행은 복원되지 않으므로 배포 전
  덤프가 유일한 복구 수단이다.

## Open Questions

- 없음.
