# 2026-08-28 — 화면 판정에 쓸 selector 를 목록으로 두고 목록 밖은 무시한다

- Date: 2026-08-28
- Jira: ARTEL-654
- Status: Implemented

## Goal

`discriminator` 의 기본값을 뒤집는다. 지금은 화면에 있는 조작 가능한 오브젝트가 **전부** 들어가고
무엇을 뺄지를 기계가 규칙으로 정한다. 앞으로는 씬마다 목록을 두고 **목록에 있는 것만** 들어간다.
목록에 없는 selector 는 처음 보는 것이어도 무시한다.

이 작업은 ARTEL-453(PR #201)이 세운 규칙을 그대로 대체하므로, 별도 PR 을 쌓지 않고 그 브랜치에
커밋을 더한다. 같은 이유로 ARTEL-649(PR #205)는 닫히고 그 마이그레이션의 화면 접기 절차만
여기로 흡수한다.

## Non-goals

- 목록을 고치는 tool — agent-server 이슈다.
- 처음 보는 selector 를 agent 에게 물어보는 것, 그리고 목록에서 뺐을 때의 소급 접기 — ARTEL-655.
- 새 기계 추정을 더 만드는 것. 표본이 하나뿐인 상태에서 규칙을 깎는 것이 이 이슈가 멈추려는
  일이다.

## Context / Constraints

실측(`artel_integration`, 2026-08-28):

```
screen 30행 중 29행이 TurnBattleScene 의 것 — MAX_SCREENS_PER_SCENE 32 코앞
capability 472개 중 control_selector 가 있는 것 24개
실제로 화면을 가른 selector 는 넷 — CombineSystem[7]/CombineZone[1] 과 그 아래 Button[2]·Zone1[0]·Zone2[1]
```

무엇을 뺄지 기계가 알아내는 방향으로 후보 셋을 재봤고 셋 다 반례가 나왔다.

| 후보 | 반례 |
|---|---|
| 한 관측 안의 인스턴스 수 | 이름에 카운터가 든 게임(`agent(1)` · `agent(2)`)에서 안 잡히고, 이름이 같은 형제 컨트롤 둘(확인 버튼과 취소 버튼)을 잘못 뺀다 |
| 여러 관측에 걸친 등장 횟수 | 지나고 나서만 알 수 있고, 이름이 매번 바뀌면 표가 플레이 길이만큼 자란다 |
| 조작 없이 변한 것은 안 가른다 | 로딩 화면에서 게임 화면으로 넘어가는 것이 반례다 |

이름만 보고 깎는 것도 위험하다 — `Zone1` 과 `Zone2` 는 끝자리가 숫자여도 서로 다른 오브젝트이고,
실제로 화면을 가른 넷 중 둘이다.

제약:

- **정규표현식을 저장하지 않는다.** 목록은 Kotlin(`ScreenSelectorWhitelist`)과 SQL
  (`screen_defining_selector`) 양쪽에서 평가된다. `java.util.regex` 와 POSIX ARE 는 다르고,
  한쪽에서만 맞는 항목이 하나면 같은 화면이 두 `discriminator` 로 갈린다. 두 번째 이유는 항목을
  LLM 이 쓴다는 것 — 잘못된 정확 문자열은 아무것에도 안 맞고 끝나지만 잘못된 정규식은 전부 맞는다.
- **목록이 빈 씬은 화면이 하나다. 오류가 아니다.**
- **목록에 넣는 것은 소급되지 않는다.** 빠진 selector 가 애초에 기록되지 않았으니 복원할 수 없다.
- `SUBTREE` 는 **마디 경계**로만 맞는다. `contains` 로 하면 `Zone1` 이 `SomeZone1Extra` 에 걸린다.

## Approach (Checklist)

- [x] **Step 1: 표** — `scene_screen_selector(scene_id, match_kind, pattern, source, screen_defining)`.
      `uk_scene_screen_selector(scene_id, match_kind, pattern, source)` 로 출처가 키에 들어가,
      사람이 agent 를 **덮는 것이 아니라 이긴다**.

- [x] **Step 2: 평가** — 우선순위는 출처(human > agent > static-analysis) → 좁기(selector > path >
      subtree) → 긴 pattern → 늦은 id. 맞는 항목이 없으면 `false`.
      - Kotlin: `ScreenSelectorWhitelist.defines`
      - SQL: `screen_defining_selector(scene_id, selector)` — 함수 하나로 모아 ARTEL-655 도 같은
        정의를 쓴다. `LIKE` 대신 `starts_with` 를 쓴다(`%` · `_` 가 메타문자다).

- [x] **Step 3: 배선** — `ScreenFold.discriminate(whitelist)`. `offers` 기반 `advertised` 는 없앤다.
      `screen_capability` 는 `discriminator` 가 아니라 `ScreenFold.activeSelectors()` 에서 읽는다 —
      목록에서 뺀 컨트롤도 그 화면이 제공한 기능이기는 하다.

- [x] **Step 4: 씨앗** — `capability.control_selector` 를 `selector` 항목으로 심는다.
      마이그레이션이 그때 있던 것을, `ScreenObservationService.seededWhitelist` 가 이후 빌드의 것을.

- [x] **Step 5: 소급 접기** — V58 이 이미 쌓인 `screen` 을 새 규칙으로 다시 계산하고, 같아지는 행을
      묶어 `observed_count` · `screen_transition` · `scene_edge` · `knowledge_anchor` 를 옮긴다.
      ARTEL-649 의 절차를 그대로 가져왔다.

- [x] **Step 6: 검증** — `./mvnw test`, 그리고 `pg_dump` 사본에 마이그레이션 실적용.

## Validation

`./mvnw test` — `ScreenObservationTest` 16건 · `ScreenSelectorWhitelistTest` 8건 모두 통과.
전체 1099건 중 66건 error 는 base(ARTEL-453)에서도 같은 10개 클래스에서 같은 수로 난다 —
전역 `DELETE FROM project` · `DELETE FROM game_instance` 가 `qa_run_*_fkey` 에 걸리는 테스트
정리 문제이고 이 변경과 무관하다.

`artel_integration` 의 `pg_dump` 사본에 V58 실적용:

```
screen           30행 → 3행   (TurnBattleScene 29행 → 2행)
screen_transition 39행 → 3행
screen.observed_count 합 54 유지
scene_screen_selector 7행 (씨앗)
```

씨앗만으로 `TurnBattleScene` 이 **2**, 실제로 화면을 가른 넷을 넣으면 **3**(combine panel 닫힘 ·
열림 · 확정 가능). 둘 다 테스트로 못을 박았다.

Kotlin 과 SQL 이 같은 결과를 내는지는 실측 selector 49개 전부를 세 대상 · 세 출처 · 제외 항목이
섞인 목록에 통과시켜 양쪽을 맞대 본다(`목록 적용이 Kotlin 과 SQL 에서 같은 결과를 낸다`).

## Risks

- 빌드가 바뀌어 계층 index 가 흔들리면 `selector` 씨앗이 아무것에도 안 맞고 그 씬은 화면 하나가
  된다. 눈에 보이고 `path` 항목을 더하면 복구된다. 반대쪽 실패(잘못 뭉친 화면)는 조용하므로 이
  방향을 골랐다.
- 목록을 사람이나 agent 가 넓게 쓰면 화면이 다시 폭발한다. `MAX_SCREENS_PER_SCENE` 경고가 그
  자리를 알려주고, 그때 의심할 것은 상한이 아니라 항목의 넓이다.
