# 2026-08-21 — content_map 조회 API 를 연다

- Date: 2026-08-21
- Jira: ARTEL-446
- Status: Implemented

## Goal

`content_map` 을 **프로덕션에서 처음으로 읽는다.** 지금 이 표들을 읽는 것은 테스트뿐이다 —
V40~V48 이 세운 스키마, ARTEL-485 의 조인, ARTEL-442 의 적재기가 행을 앉히기만 하고 아무도 보지
않는다.

```
GET /api/projects/{projectId}/game-builds/{gameBuildId}/content-map?capture=editor
→ { contentMap, scenes[], edges[], gaps[], verification, pendingDocuments[] }
```

응답 모양은 Notion "씬 명세 조회 (Content Map)" 로 **이미 발행된 계약**이고, home 화면
(ARTEL-489)이 지금 그 계약에 맞춰 만들어지는 중이다. 모양을 새로 발명하지 않는다. 더할 수는
있고 뺄 수는 없다.

## Non-goals

- home 화면 (ARTEL-489)
- 기능 단건 조회 · 씬 상세 · 효과(`capability_effect`) 노출. 효과는 행이 곱해져서 이 응답에 접지
  않는다(V45 가 뷰에서 뺀 것과 같은 이유)
- 페이지네이션. 실측이 씬 7 · 기능 491 이라 한 번에 나간다. 잘라야 할 크기가 되면 그때 자른다
- `scene_edge` 를 채우는 일. **지금 프로덕션에 이 표를 쓰는 코드가 없다** — 아래 "정직하지 못한
  칸" 참조

## Context / Constraints

- `/api/` 아래다. 사람이 브라우저에서 읽는 화면이고 JWT 인증 대상이다
  (`project.md` 의 신뢰 경계, `KnowledgeGraphViewController` 가 선례)
- 컨트롤러는 **새로 만들지 않는다.** `ProjectContentMapController` 가 이미 같은 경로 접두사와 같은
  인증을 쓴다. `@CurrentUserId` + 위임 + `?: throw NotFoundException` 모양을 그대로 잇는다
- 접근 검사는 **서비스 안**이다. `EvidenceDocumentService.requireAccessibleBuild` ·
  `ContentMapIngestService.ingestBuild` 와 같은 자리 — 이 표를 읽는 유일한 문이 그 함수라야
  검사를 빠뜨릴 수 없다. `findAccessibleById(id, projectId, userId)` 를 쓴다(경로의 projectId 가
  장식이 되지 않게)
- id 는 `Long` 으로 낸다. 같은 컨트롤러의 등록·적재 응답이 그렇게 낸다
  (`ContentMapIngestDtos.kt` 의 KDoc 이 이유를 든다) — 한 화면이 같은 `documentId` 를 어떤
  요청에서는 숫자로 어떤 요청에서는 문자열로 받는 것이 더 나쁘다
- 효과를 조인하지 않는다. 기능 하나에 여러 개라 행이 곱해진다

## 정해야 하는 것

### 1. 씬별 상태 카운트를 어디서 세나 — **SQL. `capability` 테이블 직접.**

`v_content_map_capability` 는 **`not-a-step` 을 이미 걸러 낸다**(V45 의 `WHERE c.status <>
'not-a-step'`). 그래서 `notAStep` 카운트는 그 뷰에서 **구조적으로 나올 수 없다.** 실측에서 기능
491행 중 뷰가 내주는 것은 51행뿐이라, 뷰로 세면 씬별 합이 491 이 아니라 51 이 된다.

정직한 출처는 `capability` 테이블 자체다. 뷰가 쓰는 나머지 필터(`merged_into IS NULL`)는 그대로
가져가고 `status <> 'not-a-step'` 만 뺀다 — 그러면 뷰가 내주는 행 수 = `total - notAStep` 이라는
관계가 성립하고, 그 관계 자체를 테스트가 못 박는다.

Kotlin 에서 `findCapabilityRows` 를 훑어 세지 않는 이유가 둘이다:

1. 그 뷰는 애초에 `not-a-step` 을 못 내준다(위)
2. 정수 다섯 개를 얻으려고 스키마에서 가장 넓은 표의 491행을 조건 트리·힌트 파라미터까지 통째로
   실어 나른다. `GROUP BY` 는 씬당 한 줄, 최대 7줄이다

`CapabilityRepository` 에 `countByScene(contentMapId)` 를 더한다. `countEvidenceVerification` 이
이미 같은 성격의 집계를 들고 있는 자리다. `count(*) FILTER (WHERE status = ...)` 로 축을 펼쳐
씬당 한 행으로 낸다.

> **주의: 이 카운트는 `origin` 을 가리지 않는다.** `countEvidenceVerification` 은
> `origin='evidence'` 로 좁히지만(커버리지 지표의 분모가 정적 분석 성능이라서), 씬별 카운트는
> 화면이 "이 씬에 무엇이 있나"를 묻는 것이라 QA 가 관측으로 배운 기능도 세어야 한다. 두 수가
> 다른 것이 정상이다.

### 2. 섹션마다 질의 하나 vs 집계 질의 하나 — **섹션마다 하나.**

한 방에 답하려면 씬 · 간선 · gap · 문서를 한 질의에 접어야 하는데, 서로 카디널리티가 달라 조인하면
행이 곱해지고 `LATERAL` 이나 `json_agg` 로 도망가게 된다. 그러면 응답 모양이 SQL 문자열 안으로
숨는다.

여섯 섹션에 다섯 질의다(문서 하나가 두 칸을 답한다). 전부 `content_map_id` 하나로 좁힌 인덱스
조회이고 한 화면이 한 번 부른다.

| 섹션 | 출처 |
|---|---|
| `contentMap` | `ContentMapRepository.findByGameBuildIdAndCapture` / `findByGameBuildIdOrderByIdDesc` |
| `scenes` | `SceneRepository.findByContentMapIdOrderByNameAsc` + `CapabilityRepository.countByScene` (신규) |
| `edges` | `SceneEdgeRepository.findByContentMapId` (신규) |
| `gaps` | `ContentMapRepository.findSpecGaps` → Kotlin 에서 사유별로 묶는다 |
| `verification` | `CapabilityRepository.countEvidenceVerification` |
| `pendingDocuments` · `contentMap.ingestedAt` | `ContentMapDocumentRepository.findByContentMapIdOrderByReceivedAtDesc` |

`gaps` 만 Kotlin 집계인 이유: `findSpecGaps` 가 **이미 있는 창구**이고 `reason IS NOT NULL` 필터와
정렬을 그 한 곳이 소유한다. 사유 어휘가 늘 때 고칠 자리를 둘로 늘리지 않는다. 행 수는 기능 수로
상한이 잡혀 있고(실측 491), 그 491행을 만든 것은 1.4MB 문서 파싱이다 — 여기서 아낄 것이 아니다.

`pendingDocuments` 도 Kotlin 필터인 이유: **같은 목록이 두 칸을 답한다.** 문서 목록 하나를 읽어
`ingested_at` 의 최댓값이 `contentMap.ingestedAt` 이고, `ingested_at IS NULL` 인 것이
`pendingDocuments` 다. 질의를 둘로 나누면 두 칸이 서로 다른 스냅샷을 볼 수 있다.

### 3. `edges` 에 계약 밖의 칸을 더한다

계약: `{ fromSceneId, toSceneName, toSceneId|null, capabilityId|null, source, verifiedAt|null }`.

여기에 둘을 **더한다**(빼지 않는다):

- `capabilitySummary` — 무엇을 해서 그 씬으로 가는가. 없으면 화면이 간선에 붙일 글자가
  `toSceneName` 뿐이라, 같은 씬으로 가는 간선 여럿이 전부 같은 이름으로 보인다.
  `LEFT JOIN capability` 는 `capability_id` 가 단일 FK 라 **행을 곱하지 않는다**
- `givenText` — 같은 컨트롤이 조건으로 갈려 서로 다른 씬으로 간다(`SceneEdgeEntity` 의 KDoc).
  간선 행에 이미 있는 칸이라 공짜다

### 4. `capture` 파라미터

없으면 **가장 최근에 만들어진 지도**(`findByGameBuildIdOrderByIdDesc` 의 첫 행)다. `updated_at`
으로 고르지 않는 이유: `content_map` 행은 같은 capture 를 다시 등록해도 갱신만 되므로, 시각으로
고르면 옛 capture 를 한 번 다시 올린 것만으로 기본값이 뒤집힌다. id 는 "언제 이 capture 를 처음
알았나"라 그렇게 흔들리지 않는다.

값이 있으면 **폴백하지 않는다.** `?capture=player` 인데 player 지도가 없으면 `contentMap: null`
이다 — 폴백하면 화면이 editor 를 player 라고 그린다.

어휘에 없는 값(`?capture=bogus`)은 **400** 이다. `Capture.from()` 이 null 을 돌려주는 자리라
그대로 두면 오타가 "지도가 없다"로 보이고, 화면은 빈 상태를 그리며 아무도 오타를 못 찾는다.
계약이 든 상태코드(401 · 404) 밖이지만, 계약이 열거한 세 값 밖의 입력에 대한 답이라 성공 모양을
건드리지 않는다.

### 5. 다섯 상태를 두 값으로 가른다

home 화면은 `contentMap` 과 `contentMap.ingestedAt` 두 값만으로 상태를 가른다:

| `contentMap` | `ingestedAt` | 뜻 |
|---|---|---|
| `null` | — | 이 빌드에 문서가 **한 번도** 등록되지 않았다 |
| 있음 | `null` | 등록됐는데 아직 적재되지 않았다 (`pendingDocuments` 가 무엇을 기다리는지 말한다) |
| 있음 | 있음 | 적재됐다 (`pendingDocuments` 가 비어 있지 않으면 그 뒤 새 문서가 더 왔다는 뜻) |

그래서 두 값 다 **이 한 번의 호출로 답할 수 있어야 한다.** `ingestedAt` 을 `content_map` 이 아니라
문서 목록에서 유도하는 이유가 그것이다 — `content_map` 에는 적재 시각 칸이 없고, 그것은 적재기가
그 행을 건드리지 않는다는 ARTEL-442 의 결정이 옳기 때문이다.

## 정직하지 못한 칸

**`edges` 는 실측 문서를 적재해도 빈다.** `scene_edge` 를 쓰는 프로덕션 코드가 아직 없다 —
`ContentMapIngestService` 는 씬 · 기능 · 근거 · 효과만 앉히고, V40 의 주석이 말하는
"`effects.kind='scene'` 으로 정적으로 채워서 출발한다"는 아직 아무도 구현하지 않았다. 이 diff 는
**읽는 창구를 여는 것**이고 그 표를 채우지 않는다. 계약의 칸은 그대로 내되, 테스트는 간선 행을
직접 심어 모양을 지킨다(`ContentMapSchemaTest` 가 하는 방식). 화면에는 빈 배열로 보인다.

## Approach (Checklist)

- [x] **Step 1: 저장소 두 줄**
  - `CapabilityRepository.countByScene(contentMapId)` — `count(*) FILTER` 로 축을 펼친 씬별 집계
  - `SceneEdgeRepository.findByContentMapId(contentMapId)` — `scene` 조인으로 지도 전체,
    `LEFT JOIN capability` 로 요약 한 칸
- [x] **Step 2: 행 DTO 둘** — `dto/ContentMapViewRows.kt`
      (`SceneCapabilityCountRow`, `ContentMapSceneEdgeRow`)
- [x] **Step 3: 응답 DTO** — `dto/ContentMapViewDtos.kt`. 계약 그대로
- [x] **Step 4: 서비스** — `service/ContentMapViewService.kt`.
      접근 검사 → 지도 고르기 → 다섯 조회 → 조립. 지도가 없으면 빈 응답
- [x] **Step 5: 컨트롤러** — `ProjectContentMapController` 에 `@GetMapping` 하나
- [x] **Step 6: 테스트** — 아래 Validation

## Validation

- **Commands:** `./mvnw -Dtest='kr.artel.orchestration.contentmap.**' test` (오늘 210건, 그대로 녹색)
- **Cases**
  - `ProjectContentMapReadAccessTest` — `ProjectContentMapAccessTest` 와 같은 결.
    경로의 projectId 가 빌드의 것과 다르면 `null`(→404), 맞으면 응답
  - `ContentMapViewGoldenTest` — 실측 픽스처(`wv-editor-latest.json`)를
    `ContentMapIngestService` 로 적재하고 응답을 읽는다
    - 씬 7개. 이름까지 골든 테스트와 같다
    - **씬별 카운트의 합이 기능 총수와 같다.** 그리고 `total - notAStep` 이
      `v_content_map_capability` 의 행 수와 같다 — 뷰가 무엇을 거르는지를 응답이 정확히 안다는 증거
    - gap 이 사유별로 묶이고, 그 합이 `v_spec_gap` 의 사유 있는 행 수와 같다
    - `verification` 이 `0 / evidence 기능 수`
    - **빈 모양 셋**: 문서 없음(`contentMap: null`) · 등록만 됨(`ingestedAt: null`,
      `pendingDocuments` 1건) · 적재됨(`ingestedAt` 있음, `pendingDocuments` 비었음)
    - `?capture=player` 는 editor 지도로 폴백하지 않는다
    - 간선을 직접 심으면 계약 모양대로 나온다(적재기가 아직 안 채우는 표)

## Risks & Rollback

- **읽기 전용이다.** 새 마이그레이션도, 기존 코드 경로 변경도 없다. 저장소 메서드 둘과 새 파일
  셋을 더하고 컨트롤러에 메서드 하나를 붙인다
- 응답이 씬 · 기능 수에 선형이다. 실측 씬 7 · 간선 0 · gap ≤ 491 이라 지금은 작다. 상용 게임에서
  커지면 자를 자리는 `gaps`(이미 집계) 가 아니라 `edges` 다
- **Rollback:** `git revert`. 이 엔드포인트를 부르는 것은 아직 아무도 없다

## Open Questions

- `scene_edge` 를 실제로 채우는 것은 어느 이슈인가? 이 diff 는 그 표가 채워지면 자동으로 보이는
  창구까지만 연다
