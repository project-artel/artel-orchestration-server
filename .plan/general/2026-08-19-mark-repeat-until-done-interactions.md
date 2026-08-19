# 2026-08-19 — 반복해야 도달하는 조작을 표시한다

- Date: 2026-08-19
- Jira: ARTEL-473
- Status: Implemented

## Goal

"대사가 끝날 때까지 아무 키를 반복해서 누른다" 같은 조작을 **스텝으로** 표현할 자리를 만든다.
지금은 표현할 자리가 없어 그 전제가 사전조건으로 밀려나고, `StoryScene` · `EndingScene` 이
`capabilities: []` 로 남는다(기존 산출물은 같은 두 씬에서 13건).

## Non-goals

- `capability.interaction` CHECK 확장. **건드리지 않는다** — enum 확장은 이 값을 읽는 모든
  소비자를 깨지만 형제 컬럼은 깨지 않는다.
- 적재기의 반복 판정 구현. 채우는 것은 ARTEL-442 몫이다.
- 렌더 문구("더 진행되지 않을 때까지 반복한다")는 ARTEL-443.

## Context / Constraints

기획: `.plan/general/2026-08-18-extend-content-map-schema-v7.md` (P0 (b))

**V43 을 새로 낸다. V40 본문은 건드리지 않는다.** base 가 ARTEL-470 이고 그쪽이 이미 V40 을
갖고 있어, 본문을 고치면 `check-flyway-migrations.sh` 가 checksum 변경으로 막는다.
V41 · V42 는 각각 ARTEL-441 · ARTEL-470 이 쓰고 있어 다음 빈 번호가 43 이다.
이 브랜치는 ARTEL-470 위에 스택된다. 병합 순서: 440 → 441 → 470 → **473** → 478 → 479.

이슈가 착수 시 정하라고 남긴 것 셋을 여기서 정한다.

1. **"아무 키" 의 `input_key` 는 `any`.**
   `ck_capability_press_needs_key` 가 `(interaction = 'press') = (input_key IS NOT NULL)` 이라
   press 로 적는 이상 값이 있어야 한다. 실제 키 하나를 고르면(`Return` 등) 거짓이 된다 — 근거는
   특정 키를 지목하지 않았다. `any` 는 이 스키마의 다른 어휘(`unity-event` · `not-a-step`)와 같은
   소문자 토큰이고, 실행 에이전트가 "아무거나 골라 보내라"로 읽을 수 있는 유일한 값이다.
2. **반복 판정 기준은 적재기가 본다.** 같은 입력이 상태를 한 칸씩 진행시키고 **종료 조건이 코드에
   있는** 형태. 스키마는 판정 결과를 받는 자리(`repeat_until_done`)만 갖는다.
3. **종료 조건은 `capability_effect` 에 적는다.** `given_text` 가 아니다. 반복이 끝내 만드는 효과가
   곧 종료 조건이고, 그것은 이미 `then` 에 적히는 값이다. `given_text` 에 두면 "대사를 모두 넘긴
   상태" 같은 **실행 에이전트가 확인할 수 없는 전제**가 되는데, 그것이 이 이슈가 없애려는 바로
   그 모양이다. 새 컬럼을 만들지 않는다.

## Approach (Checklist)

- [x] **Step 1: V43 신규 마이그레이션** — `V43__mark_repeat_until_done_interactions.sql`
  - `capability.repeat_until_done BOOLEAN NOT NULL DEFAULT FALSE`
  - `ck_capability_repeat_needs_interaction` — 반복은 조작이 있어야 성립한다.
    `interaction = 'none'` 인 행은 반복일 수 없다(타이머·코루틴은 누르는 것이 아니다)
  - `v_content_map_capability` 에 `repeat_until_done` 노출. 새 칸이 목록 가운데 들어가
    `CREATE OR REPLACE VIEW` 가 안 통하므로 `DROP` 후 `CREATE` 한다
- [x] **Step 2: 엔티티** — `CapabilityEntity.repeatUntilDone`, `ContentMapCapabilityRow`,
      `Interaction.Companion.ANY_INPUT_KEY` 상수
- [x] **Step 3: 테스트**
  - `press` + `input_key='any'` + `repeat_until_done=true` 가 저장된다
  - `interaction='none'` 인데 `repeat_until_done=true` 면 거절된다
  - 기본값이 `false` 라 기존 적재 경로가 그대로 동작한다
- [x] **Step 4: 검증** — flyway 체크, 스키마 테스트, 전체 테스트

## Validation

- **Commands to run:**
  - `./scripts/check-flyway-migrations.sh`
  - `./mvnw -o -Dtest='ContentMapSchemaTest' test`
  - `./mvnw -o test`
- **Expected output:** 전부 통과했다 — flyway `OK: no version collisions.`,
  `ContentMapSchemaTest` 23 tests / 0 failures, 전체 564 tests / 0 failures.

## Risks & Rollback

- **Risks:**
  - `input_key='any'` 는 sentinel 이다. 실행 에이전트가 이 값을 실제 키 이름으로 오해하면 `any`
    라는 키를 찾다 실패한다. 어휘를 코드(`InputKey.ANY`)에도 못 박아 오독을 줄인다.
  - `repeat_until_done` 을 모르는 소비자는 기본값 `false` 를 보고 기존과 같이 동작한다 — 반복
    구간을 한 번짜리 조작으로 읽는다. 덜 정확할 뿐 깨지지는 않는다.
  - `v_content_map_capability` 를 통째로 다시 낸다. 위 스택(478 · 479)이 같은 뷰를 또 다시
    내므로 정의가 네 번 반복된다 — 한 번 틀리면 그 뒤가 전부 그 정의를 잇는다.
- **Rollback steps:** `git revert`. 기본값이 있어 기존 행이 깨지지 않는다.

## Open Questions

- 없음. 이슈가 남긴 결정 셋은 위에서 정했다.
