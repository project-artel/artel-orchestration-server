# 2026-08-19 — evidence 를 content_map 으로 적재한다

- Date: 2026-08-19
- Jira: ARTEL-442
- Status: Reviewed (계획 검토 2회 반영 — fast · medium)

## Goal

등록된 근거 문서를 읽어 **행으로 앉힌다.** 지금 파이프라인은 `문서 등록 → (끊김) → 표 비어 있음`
이다. 조인(ARTEL-485)이 후보를 만들지만 프로덕션 경로에서 아무도 부르지 않는다.

```
content_map_document (ingested_at IS NULL)
  → DocumentStorage 에서 원본 바이트
  → EvidenceParser → EvidenceJoin.candidates()          (ARTEL-485)
  → scene · capability · capability_evidence · capability_effect
  → content_map_document.ingested_by / ingested_at 도장
```

## Non-goals

- 조인 규칙 (ARTEL-485) · 씬 전이 (ARTEL-445) · 설명 생성 (ARTEL-447) · 조회 API (ARTEL-446)
- `watchable` 판정 (ARTEL-452) · gap 으로 실행 축 내리기 (ARTEL-461). 이 이슈는 **첫 판정만** 하고
  그 둘이 나중에 덮어쓴다
- 런타임 관측(`capability_observation` · `screen*`). 그쪽은 pulse 가 붙는 ARTEL-449 이후다

## Context / Constraints

- 스키마 V40~V46 은 develop 에 있다. **이 이슈는 마이그레이션을 만들지 않는다**
- 조인은 PR #149 위에 스택으로 얹는다. 문서 픽스처도 그 브랜치에 있다
- `DocumentStorage` 에 **전체 읽기가 없다** — `readPrefix` 만 있다. 헤더만 읽는 등록 경로와 달리
  적재는 문서 전부가 필요하다. `read(objectKey)` 를 더한다
- 문서 1.4MB 가 메모리를 지나간다. 요청 스레드가 아니라 뒤에서 돈다. 트리거는 다음 이슈다(아래)

## 정해야 하는 것

### 1. `capability_key` 산식

V42 가 적재기에 위임했다. 재적재를 넘어 살아남아야 하고, 범위는 content_map 단위다.

```
capability_key = sha256_hex(
    scene | owner | entry_id | branch_offset | canonical(condition) | input_key
          | control_path | spawned_by_field
)
```

**키는 후보 목록 위에서 단사(injective)여야 한다.** 조인은 후보를 `ControlBinding` 마다 ·
`SpawnOrigin` 마다 낸다. 그래서 앞의 다섯 칸만으로는 충돌한다:

| 충돌하는 자리 | 왜 |
|---|---|
| 한 씬의 두 컨트롤이 같은 메서드에 물림 | 조인이 컨트롤마다 후보를 낸다. `control_path` 가 가른다 |
| 한 레코드가 같은 씬에서 `ENTRY` 와 `ARRIVAL` 둘로 걸림 | 위와 같다 |
| 한 씬에 `SpawnOrigin` 이 둘 | `spawned_by_field` 가 가른다 |
| `owner` 와 `entryId` 의 타입이 어긋난 레코드(실측 318 중 71) | `owner` 가 가른다 |

- **씬을 넣는다.** 같은 타입이 두 씬에 놓이면 branch 가 같아도 다른 기능이다
- **조건을 넣는다.** 실측 `Map.MapMove::ShowBattle` 의 다섯 branch 는 조건 offset 이 **전부 `@3`**
  이다. `branch_offset` 만으로 만들면 다섯이 하나로 눌린다
- `input_key` — `either` 를 쪼갠 두 후보는 조건 트리까지 같을 수 있다
- sha256 hex 는 정확히 64자 — `VARCHAR(64)` 에 그대로 맞는다

**`canonical()` 계약을 못 박는다.** 해시 입력이라 재현 가능해야 한다.

- 입력은 **타입 트리(`ConditionNode`)** 다. `conditionJson` 이 아니다 — 저쪽은 컬럼에 들어갈 원문이고
  공백·키 순서가 SDK 실행마다 흔들린다
- 객체 키는 이름순 정렬, 배열 순서는 보존(`either` 의 순서가 뜻을 갖는다)
- null 은 생략하지 않고 `null` 로 적는다. 없는 것과 빈 문자열이 같은 해시를 갖지 않게 한다
- 테스트: 키 순서만 다른 두 트리가 같은 키를 내는 것, 그리고 **골든 문서의 후보 529건이 서로 다른
  키를 갖는 것**(`candidates().map(key).distinct().size == candidates().size`)

### 2. 재적재 규칙

| 대상 | 규칙 |
|---|---|
| `origin='evidence'` | 키로 upsert. **이번 문서에 없으면 지운다** — 코드에서 사라진 기능이 표에 남으면 TC 가 없는 것을 시험한다 |
| `observed` · `inferred` · `human` | **건드리지 않는다.** 스캔이 관측을 지우면 축이 둘인 전제가 무너진다 |
| 내용이 달라진 evidence 행 | `verification` 을 `unverified` 로 되돌린다 |
| `scene` | 이름으로 upsert. `walked` · `image_object_key` · `first_seen_qa_run_id` 는 런타임이 쓴 값이라 보존 |
| `capability_effect` | 안정 키가 없다. 그 capability 의 `origin='evidence'` 행만 지우고 다시 넣는다 |

**`evidence_digest` 로는 판정할 수 없다.** 등록 경로(`EvidenceDocumentService.upsertContentMap`)가
워커보다 **먼저** `content_map.evidence_digest` 를 새 값으로 덮는다. 적재 시점에는 옛 값이 없다.
`capability_evidence` 에는 digest 칸이 없고 `content_map_document` 는 `content_hash`(파일 해시)를 든다.

그래서 판정을 **행 단위**로 내린다 — 기존 `capability_evidence` 행과 이번에 만든 값을 비교해
달라졌을 때만 `verification` 을 `unverified` 로 되돌린다. 마이그레이션 없이 되고, "문서가 바뀌었지만
이 기능은 그대로"인 흔한 경우에 확인을 헛되이 버리지 않는다.

### 3. 사라진 기능을 지우는 규칙

`capability.id` 를 가리키는 것이 여섯이고 삭제 동작이 갈린다:

| 가리키는 곳 | 삭제되면 |
|---|---|
| `capability_evidence` · `capability_inference` · `capability_effect` | CASCADE — 같이 지워진다. 재계산 가능 |
| `capability_observation` · `screen_capability` | **CASCADE — 런타임 관측이 사라진다** |
| `scene_edge.capability_id` · `screen_transition.capability_id` · `capability.merged_into` | SET NULL — 런타임 지식이 주인을 잃는다 |

씬 이름이 바뀌거나 SDK 가 조건 모양을 바꾸면 그 씬의 키가 통째로 갈리고, 평범한 재적재가 **대량
삭제**가 된다. 그래서 두 갈래로 나눈다:

- **참조가 없으면 지운다.** 관측도 화면도 전이도 안 붙은 evidence 행은 재계산 가능한 파생물이다
- **참조가 있으면 남기고 `applicability='not-applicable'` 로 내린다.** V45 어휘로 "이 빌드엔 적용되지
  않는다"이고, `status` 가 `unreachable-precondition` 으로 유도돼 TC 창구에서 빠진다. 행이 살아 있어
  런타임 지식이 매달린 채로 남는다

### 4. 쓰기 전에 DB CHECK 를 만족시키는 것

계획 검토가 세 자리를 짚었다. 조인의 후보를 그대로 넣으면 INSERT 가 거절된다.

| CHECK | 적재기가 해야 하는 것 |
|---|---|
| `ck_capability_spawn_has_no_control` (V46) | `spawn != null` 이면 `control_*` 셋을 NULL 로, `interaction='none'`, `input_phase=NULL`, `actionability='not-a-step'` 로 **강제한다.** 후보의 다른 칸을 믿지 않는다 |
| `ck_capability_evidence_call_path_or_gap` (V42) | `call_path` 가 비면 `gaps` 에 `call-path-missing` 을 **적재기가 넣는다.** 조인은 문서의 gap 만 싣는다 |
| `ck_capability_evidence_method_id_or_gap` (V42) | `method_id` 가 비면 `method-id-missing`. 파서가 `methodId` 를 필수로 요구하므로 오늘은 안 걸리지만, 규칙은 같이 둔다 |

`repeat_until_done` 은 **전부 `false`** 로 둔다. 후보에 그 칸이 없고(ARTEL-473 이 스키마만 만들었다),
`ck_capability_repeat_needs_interaction` 이 `interaction='none'` 인 행의 `true` 를 막는다. 값을 지어내지 않는다.

`capability` 는 명시 upsert 가 필요하다 — `ON CONFLICT (content_map_id, capability_key) DO UPDATE`.
`capability_evidence` 의 기존 upsert 와 같은 모양이고, **`verification` 을 UPDATE 절에서 제외**하는 것이
2절의 "달라졌을 때만 되돌린다"가 사는 자리다.

### 5. 트랜잭션 경계

**문서 하나가 한 트랜잭션이다.** 중간에 죽으면 아무것도 안 남아야 한다 — 절반만 upsert 된 상태에서
"이번 문서에 없는 행"을 지우면, 아직 처리 안 한 살아 있는 기능이 사라지고 그 CASCADE 로 관측까지
날아간다. 도장(`ingested_at`)도 같은 트랜잭션 안에서 찍어, 도장이 있으면 행이 다 있다는 뜻이 되게 한다.

### 6. 축 첫 판정

`status` 는 생성 컬럼이라 축을 안 정하면 기본값이 정한다 — 그리고 **기본값이 틀린 답을 낸다.**
`observability` 기본이 `unknown` 이라 V45 규칙상 전부 `needs-probe` 가 된다.

```
actionability  = not-a-step  (interaction = none)      | runnable (그 밖)
observability  = observable  (observable/availability 효과가 하나라도 있음) | unknown
applicability  = applies
```

ARTEL-461 이 gap 으로 실행 축을 내리고, ARTEL-452 가 watch 대조로 관측 축을 정한다. 여기서는
**근거에 있는 것만** 보고 첫 값을 준다.

### 7. `summary` 문구

`NOT NULL` 이라 비울 수 없고, 생성은 ARTEL-447 몫이다. 그때까지는 **식별자를 남긴 한 줄**을 만든다 —
`` `Canvas/continue` 클릭 → `Core.SaveLoadController.SavePlayData()` ``. 말로 옮기지 않는다.
`MapMove.position` 을 "캐릭터가 옆으로 이동"으로 바꾸는 것이 이 시스템에서 가장 비싼 거짓 명세다.

## Approach (Checklist)

- [ ] **Step 1: 스토리지** — `DocumentStorage.read(objectKey)` + S3 구현
- [ ] **Step 2: 키** — `CapabilityKey.of(candidate)` 와 canonical 직렬화
- [ ] **Step 3: 적재기** — `ContentMapIngestService.ingest(document)`
      씬 upsert → 후보 → capability upsert → evidence/effect 행 → 사라진 evidence 행 삭제 → 도장
- [ ] **Step 4: 대기 문서 소비** — `ContentMapIngestService.ingestPending(limit)`.
      **스케줄러·프로퍼티는 이 PR 에 넣지 않는다** — 아래 `## 트리거는 다음 이슈로` 참고
- [ ] **Step 5: 테스트**
  - 골든 문서 적재 → 씬 7 · `v_content_map_capability` 에 조작 가능한 행 · 스폰 행 제외
  - 두 번 적재해도 행 수와 키가 그대로
  - `observed`/`inferred`/`human` 이 살아남는다
  - 내용이 달라진 기능만 `verification` 이 `unverified` 로 돌아간다
  - 사라진 기능 중 **참조 없는 것만** 지워지고, 관측이 붙은 것은 `not-applicable` 로 남는다
  - 스폰 행이 V46 CHECK 를 통과한다

## 트리거는 다음 이슈로

계획 검토가 짚었다: 스케줄러를 기본 off 로 넣으면 **아무 환경에서도 안 돈다.** 읽는 쪽(ARTEL-446)도
아직 없어, 지금 넣는 트리거는 관측 가능한 것이 없는 세 파일이다.

이 PR 은 `문서 → 행` 을 완성하고 골든 테스트로 증명한다. 트리거(등록 직후 비동기 dispatch 또는
스케줄러)는 **읽는 쪽이 생기기 직전에** 별도 이슈로 붙인다. 그때 무엇이 플래그를 켜는지도 함께 정한다.

## 병렬 트랙

Step 2·3 은 내가 쓴다(키와 쓰기 규칙이 서로를 물어 쪼개면 두 벌이 된다). 그 뒤 겹치지 않는 셋:

| 트랙 | 파일 |
|---|---|
| 스토리지 전체 읽기 | `DocumentStorage.kt` · `S3DocumentStorage.kt` · `FakeDocumentStorage.kt` · `S3DocumentStorageTest.kt` |
| 효과 행 매핑 | `CapabilityEffectRows.kt` + 그 단위 테스트 |
| 골든 케이스 테스트 | `ContentMapIngestGoldenTest.kt` |

## Validation

- **Commands to run:** `./mvnw -Dtest='*Ingest*' test` · `./mvnw -Dtest='kr.artel.orchestration.contentmap.**' test`
- **Expected output:** 신규 테스트 통과 + 기존 176개 그대로

## Risks & Rollback

- **골든 수치가 이슈의 기대와 다를 수 있다.** 이슈는 `runnable` 13 · `needs-probe` 2 · `not-a-step` 3 을
  적었지만 그것은 사람이 손으로 옮긴 기준이고, 조인 실측은 후보 529건(조작 24+27)이다. **문서가
  이기고**, 차이가 나면 이슈 쪽을 고친다
- 1.4MB 파싱이 워커 스레드를 문다. `fixedDelay` 라 tick 이 겹치지는 않는다
- 재적재 삭제가 `capability_observation` 을 CASCADE 로 날린다. 3절의 "참조가 있으면 안 지운다"가
  그 방어이고, 골든 테스트에 관측이 붙은 행을 넣어 살아남는 것을 본다
- **Rollback:** `git revert`. 이 PR 은 트리거를 넣지 않아 되돌려도 도는 것이 없다

## Open Questions

- 트리거를 등록 직후 비동기 dispatch 로 할지 스케줄러로 할지는 다음 이슈에서 정한다. 조회
  API(ARTEL-446)가 `ingested_at IS NULL` 을 보고 "아직 적재 전"을 답할 수 있으므로 어느 쪽이든 된다
