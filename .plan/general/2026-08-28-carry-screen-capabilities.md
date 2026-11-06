# 2026-08-28 — [Orchestration] 조회 응답에 화면별 capability 를 싣는다

- Date: 2026-08-28
- Jira: ARTEL-658
- Status: Draft

## Goal

content map 조회 응답의 `screen` 마다 그 `screen` 에 묶인 `capability` 목록을 싣는다. 항목마다
`origin`(evidence · observed · inferred · human) 과 `verification`(unverified · confirmed ·
contradicted) 이 함께 나간다.

## Non-goals

- `screen_capability` 를 채우는 쪽(관측 경로)은 여기가 아니다
- `screen_transition.capability_id` 가 비어 있는 문제는 별도다

## Context / Constraints

- `screen_capability` 는 실측 DB 에 134 행 있는데 응답에 전혀 실리지 않는다. ARTEL-596 이
  `screen` 과 `screen_transition` 을 실을 때 이 연결만 빠졌다.
- 인스펙터(ARTEL-598)가 그 자리를 `scene` 전체 `capability` 로 대신 채우고 있어, `screen` 을
  고른 사람이 보는 목록이 그 `screen` 의 것이 아니다.
- `screen_transition` 에서 유도하는 길은 막혀 있다 — 실측 39 행 전부 `capability_id` 가 null.
- **질의 수가 `screen` 수에 비례하면 안 된다.** 한 `scene` 이 `screen` 수십 개를 담는다(실측
  `TurnBattleScene` 이 29 개). `ContentMapViewService` 는 섹션마다 질의 하나라는 규칙을 이미
  지키므로 같은 모양으로 간다 — `content_map_id` 하나로 좁힌 질의 한 번.
- **`scene` 의 `capability` 로 대신 채우지 않는다.** 빈 배열과 `scene` 전체 목록은 다른 사실이다.

## Approach (Checklist)

- [x] **Step 0: Recon** — `ContentMapViewService.scenesOf` 가 `scene` 별 `capability` 를 한 번에
      읽는 모양(`CapabilityRepository.findSceneCapabilities`) 과
      `ScreenRepository.findByContentMapId` 를 확인한다.
- [x] **Step 1: Implementation**
  - `dto/ContentMapViewRows.kt` — `ScreenCapabilityRow` 추가
  - `repository/CompositeKeyRepositories.kt` — `ScreenCapabilityRepository.findByContentMapId`
    추가. `screen_capability → screen → scene` 으로 좁히고 `capability` 를 조인한다.
    `merged_into IS NULL` 을 `scene` 목록과 똑같이 걸어 `screen` 목록이 `scene` 목록의
    부분집합으로 남게 한다.
  - `dto/ContentMapViewDtos.kt` — `ScreenCapabilityResponse` 추가,
    `ContentMapScreenResponse.capabilities` 추가(기본값 빈 목록)
  - `service/ContentMapViewService.kt` — `scenesOf` 에서 `screen` 목록과 같은 자리에서 한 번 읽어
    `screenId` 로 묶고 `screenOf` 에 넘긴다
- [x] **Step 2: Tests**
  - `ContentMapViewGoldenTest` — `screen` 에 묶인 것만 나오고, 묶인 것이 없는 `screen` 은 빈
    배열이며 `scene` 목록으로 채워지지 않는다는 것을 못 박는다
  - `ContentMapViewQueryCountTest` — `screen` 이 하나든 여럿이든 질의 수가 같다
    (`SceneContextQueryCountTest` 와 같은 방식: `@SpyBean` + `verify(times(1))`)
  - `docs/api/openapi.json` 재생성 (`OpenApiSnapshotTest`)
- [x] **Step 3: Rollout / Rollback** — 추가만 하는 응답 변경이라 마이그레이션도 플래그도 없다.

## Validation

- **Commands to run:** `./mvnw test`
- **Expected output:** base 브랜치와 같은 선재 실패 집합(전역 `DELETE FROM app_user` /
  `DELETE FROM game_instance` / `DELETE FROM project` 가 `qa_run` FK 에 걸리는 9 개 테스트
  클래스, 64 건) 외에 새 실패 없음. 실측 DB 로 134 행이 30 `screen` 에 어떻게 나뉘는지 확인한다.

## Risks & Rollback

- **Risks:** 조인이 행을 곱하면 같은 `capability` 가 한 `screen` 에 여러 줄 선다.
  `screen_capability` 의 PK 가 `(screen_id, capability_id)` 이고 `capability` 를 단일 FK 로
  조인하므로 곱해질 수 없다. 골든 테스트가 표의 행 수와 응답의 합을 맞춰 그것을 확인한다.
- **Rollback steps:** `git revert` — 응답에 필드가 하나 빠질 뿐이다.

## Open Questions

- 없음
