# 2026-08-31 — TC를 조작과 관측으로 가르고 기능 칸을 트리거 성격으로 짓는다

- Date: 2026-08-31
- Jira: None
- Status: Draft

## Goal

TC 한 건이 **사전조건 · 기능 · 기대결과** 셋으로 온전히 서게 한다.

지금은 가운데 칸이 조작으로 채워져 있어서 `Map_scene` 의 케이스 열둘이 이름이 같다
(`Return 키를 누른다`). 그래서 저작이 "첫 스테이지"를 고를 때 5스테이지 케이스를 집었다
(런 265). 조건은 프롬프트에 다 갔는데도 틀렸으므로, 이름을 고쳐서 될 일이 아니라 **고를
필요가 없게** 만드는 일이다.

## Non-goals

- 저작(시나리오 생성) 쪽 손대기. 오늘 넣은 것들(지도 접기·바닥 들고 걷기·케이스로 메우기·
  묻기)은 그대로 둔다.
- 기능 이름을 개발자 메서드명에서 뽑기. **하지 않는다** — 메서드 이름은 의도를 주장하는
  것이라 개발자 상태에 따라 틀린다.
- 전투 중 사건(TakeHit·Attack·OnTrigger) 관측을 지금 넣기. 스텝으로 적을 시점이 없다.

## Context / Constraints

### 오늘 실측으로 확인한 것

```
지도 31 · 기능 404개
  runnable  · observable    31개   효과 31개    ← 조작
  not-a-step · observable  137개   효과 137개   ← 관측
  not-a-step · unknown     216개   효과 71개    ← 볼 수 없다
  needs-probe               20개   효과 8개     ← 확인 못한 것

지금 케이스 43건이 붙어 있는 자리
  runnable    기능 17개 → 18건
  needs-probe 기능  5개 → 25건    ← 절반이 넘는다
  관측 기능 137개    →  0건        ← 하나도 없다
```

### 사고의 코드상 출처

`MapTestCaseGenerator` 가 효과 0건인 기능에 대해 `borrowed()` 로 **남의 효과를 빌려 온다.**
`Return`(9876, `CharacterMove()`, 효과 0건)이 `ShowBattle()`(9857, 관측)의 효과를 빌렸고,
그래서 조작 이름에 관측 결과가 붙었다. 거기에 `withBranchesApart` 가 조건 갈래마다 케이스를
내어 열둘이 됐다.

### 문구를 바꿔도 저작은 안 흔들린다

`ARTEL-627`(015fdff)에서 이미 갈라 뒀다 — `condition` JSONB 에 구조를 싣고 `precondition`
문장은 **표시 전용**으로 내렸다. 저작 프롬프트도 `needs: StagePosition >= 1` 처럼 구조에서
나온 줄을 읽지 문장을 파싱하지 않는다. 예전에 "말이 달라 저작이 어려웠다"는 것은 문구 문제가
아니라 **문장을 코드가 되읽던** 문제였고 그건 고쳐져 있다.

### 구버전(specs_v2)이 이미 하던 방식

`app/specs_v2/render.py::trigger_text` 가 **트리거 성격**으로 문장을 짓는다. 개발자 이름을
안 쓴다.

```
control        → "{씬}에서 {대상}를 클릭한다"
control_check  → "{씬}에서 {대상}의 표시 상태를 확인한다"
input          → "{씬}에서 {키} 입력을 한다"
scene_entry    → "{씬}에 진입해 관찰한다"
continuous     → "{씬}에 머무르며 관찰한다"
collision      → "{씬}에서 {이벤트} 충돌이 발생한다"
pointer        → "{씬}에서 드래그를 끝낸다"
그 밖          → "{씬}에서 `{이벤트}` 이벤트 이후 관찰한다"
```

구버전 출력 153건이 `.plan/general/2026-08-31-oldgen-reference.json` 에 남아 있어 대조할 수
있다. 거기서는 `Return` 이 한 건이고 배경 관측이 진행도별로 갈려 있다.

### content_map 에는 그 분류가 얇다

```
capability.interaction              none 353 · press 27 · click 24
capability_evidence.trigger_kind    lifecycle · unity-event
call_path 의 뿌리                    Start · Update · OnTriggerEnter2D · OnMouseEnter · Attack …
```

`interaction` 은 조작만 가른다. 나머지는 **call_path 뿌리의 Unity 콜백 이름**이 구버전의
`trigger.kind` 에 해당한다 — 개발자가 아니라 Unity 가 정한 이름이라 흔들리지 않는다.

## Approach (Checklist)

- [ ] **Step 0: Recon**
  - `MapTestCaseGenerator.kt` — `merged()` · `borrowed()` · `withBranchesApart()` · `draftsOf()`
  - `MapTestCasePhrasing.kt` — `step()` · `expectedWithSource()`
  - `MapTestCase.kt` — 칸
  - 구버전 `render.py::trigger_text` 를 옆에 두고 대조

- [ ] **Step 1: 생성기 (DB 안 건드림, 미리보기까지)**
  - **①** `borrowed()` 를 쓰지 않는다. 효과 0건이면 케이스를 안 낸다.
        빠지는 것은 `확인 못한 기능` 으로 따로 센다(지도 31 기준 12개).
  - **②** 관측 기능도 케이스를 낸다.
        `not-a-step` + `observable` + 눈에 보이는 효과(`ui-value`·`active-state`·`transform`·`scene`)
        + call_path 뿌리가 `Start`/`Awake`/`Update` 인 것.
        `OnMouse*`·`OnDrag`·`CardAlignment` 는 뺀다(조작의 부수 효과).
        전투 중 사건은 이번에 안 넣는다.
  - **③** `기능` 문장을 트리거 성격으로 짓는다(위 표). 조작이 없으면 관찰 문구.
  - **④** `withBranchesApart` 기준을 **조건 갈래**에서 **효과가 다른가**로 바꾼다.
        효과가 같고 조건만 여럿이면 `또는` 로 묶은 한 건.
  - **⑤** `기대결과` 를 줄 목록으로 낸다(지금은 ` / ` 로 이은 한 덩어리).
        QA 실행이 하나씩 판정해야 한다는 요구.

- [ ] **Step 2: 미리보기와 대조**
  - 지도 31로 생성만 하고 파일로 뽑는다. 적재하지 않는다.
  - 구버전 153건과 케이스별 대조표: 빠진 것 · 새로 생긴 것 · 문장이 달라진 것
  - `Map_scene` 의 `Return` 이 한 건이 되는지, 배경 관측이 진행도별로 갈리는지 확인

- [ ] **Step 3: 확정 뒤에만**
  - 마이그레이션(`feature` 칸이 필요하면) · 적재 · 저작 한 판 · 숫자 다시 잡기
  - 저작 프롬프트 케이스 목록에 `기능` 한 줄 · 프론트 케이스 카드에 `기능` 줄

## Validation

- **Commands to run:**
  - `./mvnw -o -q -Dkotlin.compiler.execution.strategy=in-process -Dtest='MapTestCase*Test' test`
  - 미리보기 뽑기(적재 없음) → `.plan/general/2026-08-31-newgen-preview.json`
  - 구버전 대조 스크립트

- **Expected output:**
  - `Map_scene` 의 `Return` 케이스가 12건 → 1건
  - 배경 관측이 진행도 1~4 로 갈린 별도 케이스로 나온다
  - 효과 0건 기능에 붙은 케이스 0건, `확인 못한 기능` 12개가 따로 보고된다
  - 전체 건수 43 → 100~150 사이

## Risks & Rollback

- **Risks:**
  - 건수가 세 배가 된다. 저작이 감당하는지는 **모델에게 조작만 보내고 관측은 코드가 붙이는**
    구조로 막기로 했는데, 그건 이번 범위 밖이라 적재 전까지는 검증되지 않는다.
  - `withBranchesApart` 기준을 바꾸면 지금 잘 나오던 케이스(화살표 이동 9건)도 모양이 바뀔 수
    있다. 대조표로 본다.
  - 관측을 넣으면 커버리지 분모가 커져 지금까지의 저작 숫자(43/43·어긋남 2건·3판)의 기준이
    달라진다. 적재 시점에 기준을 다시 잡는다.

- **Rollback steps:**
  - Step 1·2 는 DB 를 안 건드리므로 `git revert` 로 끝난다.
  - Step 3 이후는 적재 되돌리기가 필요하다 — 그 전에 확정한다.

## Open Questions

- `기능` 을 새 칸으로 둘지, `step` 칸의 내용을 바꾸는 것으로 둘지. 사용자에게 보이는 칸 이름은
  기존(사전조건/기능/기대결과)을 따르기로 했으므로 **`step` 을 그대로 쓰고 내용만 바꾸는 쪽**을
  기본으로 본다 — 마이그레이션이 없다.
- 전투 중 사건 관측 45건을 언제 넣을지. QA 실행이 어떤 시점을 잡을 수 있는지 본 뒤.
- `확인 못한 기능` 12개를 어디에 보여줄지(적재 리포트 · 프로젝트 화면).
