# 2026-08-27 — [Orchestration] 조회 응답에 화면·화면 전이·이미지를 싣는다

- Date: 2026-08-27
- Jira: ARTEL-596
- Status: Draft

## Goal

`GET /api/projects/{projectId}/game-builds/{gameBuildId}/content-map` 이 런타임 절반을
함께 낸다. 오늘 `screen` · `screen_transition` 은 저장만 되고 나가지 않아 ARTEL-597(중첩
다이어그램)과 ARTEL-598(화면 인스펙터)이 시작할 수 없다.

네 가지를 더한다.

1. 씬마다 화면 목록 — id, 이름, 판정 조건(discriminator), 관측 횟수, 처음 본 런
2. 화면 전이 — from, to, capability, 종류, 씬 경계를 넘는지
3. 씬마다 기능 목록 — 오늘은 카운트만 나간다. 인스펙터가 그 카운트 뒤의 행을 필요로 한다
4. 화면 이미지 — ARTEL-575 가 연 서명 경로(`DocumentStorage.presignDownload`)를 그대로 쓴다

## Non-goals

- 쓰기 경로를 열지 않는다. 이 컨트롤러는 읽는 자리다. `screen` 행을 앉히는 것은
  ARTEL-453 이고 별도 브랜치에서 진행 중이다.
- `screen_capability`(화면이 실제로 제공한 기능)는 이번에 내지 않는다. AC 의 화면 필드
  목록에 없고, 기능 목록은 **씬 단위**로 요구됐다. 아래 Open Questions 에 남긴다.
- `capability_effect`(then)는 여전히 담지 않는다. 기능 하나에 여러 행이라 접으면 곱해진다.
- 프런트엔드는 이 이슈의 범위가 아니다.

## Context / Constraints

- **추가만 한다.** 기존 칸은 이름도 뜻도 그대로다. 새 칸을 무시하는 클라이언트는 오늘과
  똑같이 동작해야 한다. 새 필드는 전부 기본값을 갖는다.
- **행 곱셈 금지.** 섹션마다 질의 하나라는 `ContentMapViewService` 의 기존 규칙을 그대로
  따른다. 화면·전이·기능은 서로 카디널리티가 다르므로 한 질의에 접지 않고, `content_map_id`
  하나로 좁힌 별도 조회 세 개를 메모리에서 묶는다.
  - `screen_transition → capability` 는 단일 FK 라 `LEFT JOIN` 이 행을 늘리지 않는다.
    `scene_edge` 가 이미 같은 판단을 했다.
- **접근 검사는 서비스 안에 남긴다.** `gameBuilds.findAccessibleById` 한 줄이 이 표를 읽는
  유일한 문이라야 다음 진입점이 빠뜨릴 수 없다.
- **화면이 0 행인 빌드가 정상이다.** QA 런 전에는 `screen` 이 비어 있고, 그 빌드도 200 으로
  씬만 낸다. 프런트엔드가 처음 보는 상태가 바로 이것이다.
- `screen` 에는 `image_width` · `image_height` · `image_failure_code` 가 없다(V54 는 `scene`
  에만 그 칸을 더했다). 그래서 화면 이미지 DTO 는 `SceneThumbnailResponse` 와 모양이 다르다 —
  실패 상태가 없으므로 `state` 판별자도 없다.
- 병행 작업: ARTEL-453 이 `ScreenRepositories.kt` 에 **쓰기** 질의를 더한다. 이 브랜치는
  그 파일을 재배치하지 않고 **덧붙이기만** 한다.

## Approach (Checklist)

- [x] **Step 0: Recon**
  - `ProjectContentMapController` → `ContentMapViewService` → `ContentMapViewDtos` 읽음
  - V40 의 `screen` · `screen_transition` · `screen_capability` 정의 확인. V41~V54 는 이 셋을
    건드리지 않는다
  - ARTEL-575 의 서명 경로 확인: `DocumentStorage.presignDownload(objectKey, fileName)` →
    `PresignedDownload(url, expiresAt)`. `ContentMapViewService.thumbnailOf` 가 부른다
  - `ContentMapViewGoldenTest` 의 픽스처 모양 확인

- [ ] **Step 1: 조회 질의** (`repository/`)
  - `ScreenRepositories.kt` — **덧붙이기만.**
    - `ScreenRepository.findByContentMapId(contentMapId): Flow<ScreenEntity>`
      (`screen JOIN scene`, `ORDER BY scene_id, id`)
    - `ScreenTransitionRepository.findByContentMapId(contentMapId): Flow<ContentMapScreenTransitionRow>`
      (`screen_transition JOIN screen JOIN scene`, `LEFT JOIN capability`, `ORDER BY id`)
  - `CapabilityRepository.findSceneCapabilities(contentMapId): Flow<SceneCapabilityRow>`
    — `countByScene` 와 **같은 필터**(`merged_into IS NULL`, 같은 조인)를 써서
    `capabilityList.size == capabilities.total` 이 구조적으로 성립하게 한다

- [ ] **Step 2: 행 DTO** (`dto/ContentMapViewRows.kt`)
  - `ContentMapScreenTransitionRow`, `SceneCapabilityRow`

- [ ] **Step 3: 응답 DTO** (`dto/ContentMapViewDtos.kt`)
  - `ContentMapResponse.screenTransitions: List<ContentMapScreenTransitionResponse> = emptyList()`
  - `ContentMapSceneResponse.screens: List<ContentMapScreenResponse> = emptyList()`
  - `ContentMapSceneResponse.capabilityList: List<SceneCapabilityResponse> = emptyList()`
  - `ContentMapScreenResponse` · `ScreenImageResponse` · `SceneCapabilityResponse` 신규

- [ ] **Step 4: 서비스** (`service/ContentMapViewService.kt`)
  - `scenesOf` 가 화면·기능 목록을 씬별로 묶어 넣는다
  - `read` 가 `screenTransitions` 를 더한다
  - `imageOf(screen)` 이 `thumbnailOf(scene)` 과 같은 서명 경로를 쓴다

- [ ] **Step 5: 테스트** (`ContentMapViewGoldenTest`)
  - 화면이 든 씬과 안 든 씬
  - 화면이 0 행인 빌드가 씬만 낸다
  - 씬 경계를 넘는 전이
  - 기능 목록이 제 씬 아래 서고 수가 카운트와 같다
  - 화면 이미지가 서명된 주소로 나온다

- [ ] **Step 6: Rollout / Rollback**
  - 마이그레이션 없음. 스키마는 V40~V54 로 이미 `develop` 에 있다
  - 추가만 하는 변경이라 되돌리기는 `git revert` 한 번

## Validation

- **Commands to run:**
  - `./mvnw test -Dtest='ContentMap*'` — 실제 게이트
  - `./mvnw test` — 전체
- **Expected output:**
  - `ContentMap*` 전부 통과
  - 전체에서는 기존 실패 둘이 그대로 남는다(깨끗한 `origin/develop` 워크트리에서 확인됨):
    `OpenApiDocumentationIntegrationTest`(5초 blocking read 타임아웃),
    `TestScenarioReconcileIntegrationTest`(부하에서 flaky)

## Risks & Rollback

- **Risks:**
  - **응답이 커진다.** 골든 문서에서 기능 491 행이 씬 아래로 들어온다(오늘 `steps` 는 51 행).
    행을 얇게 유지해 완화한다 — 판정 세 축과 status·origin·verification·summary 만 담고,
    컨트롤 표시용 칸은 이미 `steps` 가 든다.
  - `discriminator` 를 `JsonNode` 로 통과시킨다. 코딩 규약이 금지하는 "타입 없는 맵"이 아니라
    **통과 전용 페이로드**다 — 서버는 이 값을 읽지 않고 화면이 그대로 그린다. 모양을 DTO 로
    못 박으면 ARTEL-453 이 다른 모양을 쓰는 날 조회가 깨진다.
  - `ScreenRepositories.kt` 를 ARTEL-453 과 함께 만진다. 덧붙이기만 하므로 병합 충돌은
    파일 끝 근처 한 곳으로 좁혀진다.
- **Rollback steps:** `git revert`. 스키마도 쓰기 경로도 건드리지 않아 데이터 영향이 없다.

## Open Questions

- `screen_capability`(화면별 기능과 관측/발화 횟수)는 이번 AC 에 없다. ARTEL-598 의 인스펙터가
  **씬 단위** 기능 목록으로 충분한지, 화면 단위가 필요한지는 그 이슈가 정한다. 추가만 하는
  변경이라 나중에 붙일 수 있다.
- `screens` 안의 정렬은 `screen.id` 오름차순, 즉 처음 관측한 순서다. 화면 이름으로 정렬하지
  않는 것은 `name` 이 nullable 이고 LLM 이 짓는 표시용 값이기 때문이다.
