# 2026-08-21 — 씬 전이 후보를 정적으로 뽑는다

- Date: 2026-08-21
- Jira: ARTEL-445
- Status: Implemented (실측 반영 — 간선 19 · 쌍 13)

## Goal

`scene_edge` 를 **적재기가 채운다.** 오늘 이 표는 아무도 쓰지 않는다 — V40 이 만들었고
`SceneEdgeEntity` 와 `SceneEdgeRepository` 가 읽을 준비까지 끝났는데, 쓰는 코드가 없어 늘 0행이다.
그 결과 "이 씬에서 저 씬으로 갈 수 있다"는 사실이 어디에도 없고, `verified_at IS NULL` 이 곧
커버리지 구멍이라는 이 표의 존재 이유가 성립하지 않는다.

새 추출이 아니다. 근거 문서가 이미 말하고 있다:

```
SceneManager.LoadScene("X")
  → effects[] { kind: "scene", category: "observable", target: "X", offset: <IL> }
```

적재기는 그 효과를 이미 `capability_effect` 로 옮기고 있다. 남은 것은 **한 걸음의 매핑**이다:

```
capability (scene_id = 출발 씬)  +  effect(kind='scene').target = 도착 씬 이름
  → scene_edge(from_scene_id, to_scene_name, capability_id, source='static')
```

## 실측 (2026-08-21, `src/test/resources/contentmap/wv-editor-latest.json`, schema 6 · editor)

| 값 | 수 | 어디서 |
|---|---|---|
| 문서 전체 효과 | 395 | `types[].effects[]` + `unplaced[].evidence[].effects[]` |
| `kind='scene'` 효과 | **15** | 전부 `types` 쪽. `unplaced` 에는 0건 |
| `category` | 전부 `observable` | 15/15 |
| 도착 씬 이름 | TitleScene 2 · GameOverScene 4 · Map_scene 4 · TurnBattleScene 2 · GameClearScene 1 · EndingScene 1 · StoryScene 1 | |
| `recordKind` | candidate 13 · flow 2 | flow 도 기능 행이 되므로 둘 다 간선을 낸다 |

**문서의 15 는 `scene_edge` 행 수가 아니다.** 조인은 레코드 하나에서 컨트롤 배선마다 · 스폰마다
후보를 내므로 한 레코드의 효과가 여러 기능 행에 실린다(같은 이유로 문서 효과 395 가
`capability_effect` 486행이 된다).

**실측 결과 — 간선 19행, 서로 다른 (출발 → 도착) 쌍 13.**

- **왜 15 보다 많나** — `Combat.Enemies.Player::Death` 의 `GameOverScene` 효과 하나가 진입점 넷(적
  근접 공격 · 피격 · 적 투사체 · 주문)에서 각각 기능 행이 되어 간선 넷이 된다. 그 넷을 접으면
  "무엇을 해서 죽었나"가 사라지고, `scene_edge.capability_id` 가 든 뜻이 그것이다
- **왜 15 보다 적을 수도 있나** — `Scenes.GameClearController::Update` 가 `Map_scene` 을 `@72` 와
  `@90` 두 지점에서 부른다. 효과는 둘이고 `uk_scene_edge` 는 한 행이다

19행 전부 `to_scene_id` 가 풀린다 — 도착 씬 일곱이 전부 이 문서가 순회한 씬이다. 자기 씬으로 가는
간선은 없다.

## Non-goals

- 런타임 검증 — `verified_at` · `observed_count` · `first_observed_transition_id` 를 채우는 것은
  QA 런 쪽(`source='runtime'`)이다. 이 이슈는 **그 칸을 절대 건드리지 않는 것**만 한다
- `given_text` 채우기. 오늘 `capability.given_text` 는 전부 null 이고(문장 생성은 ARTEL-447),
  없는 값을 지어내면 조건별로 갈리는 간선의 설명이 거짓이 된다
- 새 마이그레이션. `scene_edge` 는 V40 에 있고 `uk_scene_edge` 도 이미 있다
- 조회 API. 읽는 쪽은 ARTEL-446 이다

## Context / Constraints

### 이미 있는 것

- `scene_edge` (V40): `uk_scene_edge UNIQUE (from_scene_id, to_scene_name, capability_id)` +
  `uk_scene_edge_auto` 부분 인덱스 `(from_scene_id, to_scene_name) WHERE capability_id IS NULL`
- `SceneEdgeEntity` · `SceneEdgeRepository` — 읽기 메서드만 있다
- `EvidenceEffect.kind` / `.category` / `.target` — 파서가 이미 타입 있는 값으로 준다
- `ContentMapIngestService.ingestInTransaction` 의 `for ((key, group) in grouped)` 루프.
  그 안에 `sceneId` 와 `capabilityId` 가 둘 다 있다 — 간선에 필요한 것 전부다

### 제약

- 우리가 쓰는 행은 `capability_id` 가 **항상 차 있다.** 그래서 `uk_scene_edge_auto` 부분 인덱스에
  걸리지 않고, 멱등 키는 `uk_scene_edge` 셋 칸 그대로다
- `to_scene_name` 은 `VARCHAR(255)`. 문서 값이 넘치면 그 한 줄이 문서 전체를 되돌린다 —
  `capability_effect` 와 같은 규칙으로 잘라서 싣는다
- 씬 이름이 이 지도에 없을 수 있다. 빌드가 스캔하지 않은 씬을 이름으로 부를 수 있고, 그것이
  `to_scene_id` 가 nullable 인 이유다

## 정해야 하는 것

### 1. 멱등 — upsert 인가 지웠다 넣기인가

**upsert 다.** `capability_effect` 는 지웠다 넣는데(`writeEffects`), 그쪽은 안정 키가 없어 그것이
유일한 길이었다. `scene_edge` 는 다르다:

- `uk_scene_edge (from_scene_id, to_scene_name, capability_id)` 라는 **안정 키가 이미 있다**
- 이 행은 `verified_at` · `observed_count` · `first_observed_transition_id` 를 든다. 지웠다 넣으면
  그 셋이 매 재적재마다 0 으로 돌아가고, **`verified_at IS NULL` 이 곧 커버리지 구멍**이라는 이
  표의 존재 이유가 재적재마다 거짓말이 된다. 씬의 `walked` 를 보존하는 것과 같은 규칙이다

그래서 `ON CONFLICT (from_scene_id, to_scene_name, capability_id) DO UPDATE` 로 쓰되 **UPDATE 절에
정적 칸만 둔다** — 실제로는 `to_scene_id` 하나뿐이다(문서가 다시 말한 이름은 같고, 달라질 수 있는
것은 "그 이름의 씬을 이제 순회했나"뿐이다).

`WHERE scene_edge.source = 'static'` 을 DO UPDATE 에 건다. 같은 셋 칸에 이미 `runtime` 행이 있으면
정적 분석이 관측을 뒤늦게 따라잡은 것이고, 그때 행은 `runtime` 인 채로 둔다 — 관측이 더 강한
근거다. 그 경우 `RETURNING` 이 0행이라 반환은 nullable 이다.

### 2. 사라진 정적 간선

문서가 더 이상 말하지 않는 간선은 내려야 한다. 두 경우다:

1. 기능은 그대로인데 도착 씬이 바뀌었다 → 옛 `to_scene_name` 행이 남는다
2. 기능이 통째로 사라졌다 → 그 기능의 간선이 전부 남는다

**2 번이 더 나쁘다.** `CapabilityRepository.hasRuntimeReferences` 가 `scene_edge` 를 참조로 세기
때문에, 정적 간선을 쓰기 시작하면 씬 효과를 든 기능은 **영원히 삭제 불가**가 되어
`retireVanished` 가 늘 `not-applicable` 로만 내린다. 런타임 지식이 하나도 없는 정적 파생물이 기능
행을 살려 두는 셈이고, 그 검사의 취지(관측이 벌어 온 것을 지키자)와 정반대다.

그래서 **문서당 한 번, `retireVanished` 앞에서** 이번에 쓰지 않은 정적 간선을 쓸어 낸다:

```sql
DELETE FROM scene_edge e USING scene s
WHERE e.from_scene_id = s.id AND s.content_map_id = :contentMapId
  AND e.source = 'static'
  AND e.verified_at IS NULL AND e.observed_count = 0
  AND e.first_observed_transition_id IS NULL
  AND e.id <> ALL (:keptIds)
```

- `source='static'` — `runtime` 행은 건드리지 않는다
- 런타임 칸 셋이 전부 비었을 때만 지운다. QA 런이 한 번이라도 지나간 정적 간선은 지식을 든
  행이므로 문서가 말을 바꿔도 남긴다
- 기능 루프를 도는 동안 upsert 가 돌려준 id 를 모아 `keptIds` 로 넘긴다. 빈 배열이면
  `<> ALL ('{}')` 이 참이라 지도의 지식 없는 정적 간선이 전부 지워진다 — 문서가 씬 효과를 하나도
  말하지 않게 된 경우의 정답이다
- 기능마다 DELETE 를 내지 않는 이유: 기능 491행 중 씬 효과를 든 것은 극소수인데, 기능당 한 문장을
  내면 적재 한 번에 쓸모없는 DELETE 가 수백 개 는다

### 3. `to_scene_id` 는 SQL 안에서 푼다

```sql
(SELECT s.id FROM scene s WHERE s.content_map_id = :contentMapId AND s.name = :toSceneName)
```

`upsertScenes` 가 돌려준 맵을 쓰지 않는 이유: 그 맵은 **이번 문서**가 말한 씬만 담는다. 같은 지도의
앞선 문서가 만든 씬이 빠지고, 그러면 표에 뻔히 있는 씬을 가리키는 간선이 null 로 남는다. UPDATE 절에
같은 식을 두어, 나중에 그 씬을 순회하면 다음 재적재에서 null 이 채워진다.

### 4. 자기 자신으로 가는 간선

`LoadScene(현재 씬)` 은 재시작이고 실제로 일어나는 전이다. 거르지 않는다 — 거르면 "이 버튼을 누르면
씬이 다시 로드된다"가 표 어디에도 없게 된다. 실측 픽스처에는 그런 쌍이 없어, 거르지 않기로 한 결정이
19 라는 수에 영향을 주지 않는다.

## Approach (Checklist)

- [x] **Step 0: Recon** — `V40__create_content_map.sql` · `SceneEdgeEntity` · `ScreenRepositories.kt`
      · `ContentMapIngestService.kt` · 골든 픽스처 실측
- [x] **Step 1: 저장소** — `SceneEdgeRepository` 에 `upsertStatic` 과 `retireStaleStaticEdges`.
      `upsertStatic` 은 `@Modifying` 을 **붙이지 않는다**(`CapabilityRepository.upsertByKey` 와 같은
      이유 — 붙이면 `RETURNING id` 대신 영향 행 수 1 이 돌아온다)
- [x] **Step 2: 적재기** — `writeSceneEdges(sceneId, capabilityId, mergedEffects, contentMapId)` 를
      `writeEffects` 뒤에 붙이고, 모은 id 로 `retireVanished` 앞에서 쓸어 낸다
- [x] **Step 3: 결과 칸** — `IngestResult.sceneEdges` 와 `IngestedDocumentResponse.sceneEdges`
- [x] **Step 4: 테스트** — `ContentMapSceneEdgeIngestTest` (6건)
  - 골든 문서가 내는 **실측 간선 수** 19 와 쌍 13
  - 이름이 지도의 씬과 맞으면 `to_scene_id` 가 그 씬을 가리킨다
  - 모든 정적 간선이 `source='static'` · `verified_at IS NULL` · `observed_count = 0`
  - 재적재가 간선을 늘리지 않고, 손으로 찍어 둔 `verified_at` · `observed_count` 를 되돌리지 않는다
  - `source='runtime'` 행이 살아남는다
  - 문서가 씬 효과를 뺐을 때 지식 없는 정적 간선만 사라진다

## Validation

- **Commands to run:** `./mvnw -Dtest='kr.artel.orchestration.contentmap.**' test`
- **Expected output:** 216 tests · failures 0 · errors 0 (기존 210 + 신규 6)
- **Actual:** 216 tests · failures 0 · errors 0

## Risks & Rollback

- **`hasRuntimeReferences` 의 뜻이 넓어진다.** 정적 간선을 쓰기 시작하면 그 검사가 "런타임 지식이
  있다"가 아니라 "간선이 있다"로 읽힌다. 2 절의 쓸어 내기가 그 방어이고, `retireVanished` **앞에**
  도는 순서가 그 방어의 전부다. 순서가 뒤집히면 재적재가 사라진 기능을 영영 못 지운다
- **`ON DELETE SET NULL` 충돌.** 같은 씬에서 같은 씬으로 가는 정적 간선 둘의 기능이 한 번에
  지워지면 `capability_id` 가 둘 다 NULL 이 되어 `uk_scene_edge_auto` 를 어긴다. 실측 픽스처에
  그런 쌍이 있다(`Player.Death` → GameOverScene 이 진입점 넷). 2 절의 쓸어 내기가 삭제 **전에**
  그 행들을 없애 이 경로에 닿지 않게 한다
- **행 수가 예상과 다를 수 있다.** 문서의 15 는 효과 수이고 간선은 기능 행 기준이라 더 많다.
  **문서가 이긴다** — 실측해서 박고, 그 수가 어디서 오는지 테스트 KDoc 에 적는다
- **Rollback:** `git revert`. 표를 채우기만 하고 읽는 쪽이 아직 없어, 되돌려도 도는 것이 없다

## 기존 테스트 한 곳을 고쳤다

`ContentMapReingestTest.참조가 매달린 사라진 기능은 지우지 않고 적용 불가로 내린다` 가
`findByFromSceneIdOrderByIdAsc(scene).single()` 로 간선을 집었다. 그 문서의 `startGame` 이
`kind='scene'` · `target='MapScene'` 효과를 들고 있어, 이제 그 씬에서 나가는 간선이 둘이다.
도착 씬 이름으로 짚도록 바꿨다 — 그 테스트가 확인하려는 것은 "런타임 지식이 주인을 잃지 않았나"이지
"간선이 하나인가"가 아니다.

## Open Questions

- 없음. `given_text` 는 ARTEL-447 이 문장을 만든 뒤에 같은 자리에서 채우면 된다
