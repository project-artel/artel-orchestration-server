# 2026-08-05 — QA Step(조작·사전경로 가이드) 저장·활용·적용

- Date: 2026-08-05
- Jira: ARTEL-206
- Status: In Progress (Orche 측 완료 — 저장·캐리포워드·조회노출·cases 전달. 다음: Agent 브랜치에서 cases 소비 / FE 저작 UI / steps 쓰기 API)

## Orche → Agent 전달 계약 (ARTEL-254, 구현 완료)

QA 세션 오픈 시 Agent에 보내는 `scenario` JSON에 조합을 실어 준다(`ScenarioCompositionService.agentScenario`):

```json
{
  "title": "...", "description": "...",
  "cases": [
    { "position": 0, "title": "상점 진입", "category": "RULE", "precondition": null, "expected": "...",
      "steps": [ { "id":"s1", "kind":"setup", "assert": false, "intent":"상점으로 이동", "hint": null, "input": null, "observe": null } ] },
    { "position": 1, "title": "구매 확인", "category": "UI", "precondition": "...", "expected": "...",
      "steps": [ { "id":"s2", "kind":"guide", "assert": true, "intent":"구매 버튼 누름", "hint":"Enter" } ] }
  ]
}
```

- `cases[]` = position 순서. 각 항목 = TC 내용 + 그 자리의 저작 Step 배열.
- `kind`: setup(사전조건 도달, assert=false·fast-forward) / guide(TC 실행) / verify(검증).
- 추가 필드라 아직 cases를 안 읽는 Agent엔 무영향(기존 `steps`는 비어 no-op).

**Agent 측 할 일(다음 브랜치):** cases를 가공해 실행 스텝 생성 — setup은 fast-forward(판정X), guide는 실행, arrange 실패는 SETUP-FAILED 귀속. 키류 조작(hint)은 게임지식/런타임 해소.

## Goal

QA 저작/실행에 **Step** 계층을 도입해 **저장·사용자 노출·실행 적용**한다. Step은 TS의 각 position(조합 자리)에 붙는 **가볍고 advisory한 가이드**로 두 종류다:
- **① setup** — 사전조건(도달 상태)까지 가는 경로를 단계로 설명.
- **② guide** — 사전조건이 만족된 상태에서 TC를 실행하는 단계를 설명("튜토리얼 누른다 → enter").

**"해보기 전엔 정답을 모른다"** 는 전제로, 과한 인프라 선투자 없이 **먼저 Step을 만들어 실전 테스트로 효용을 검증**하는 것이 이 플랜의 목적이다.

## Non-goals

- **영속 씬 그래프 구축 안 함(폐기).** 콜드스타트(신규 고객 0 데이터)·게임 업데이트 스테일·마이그레이션 비용이 과도하고 Agent의 런타임-우선 설계와 충돌. arrange 도달은 **런타임 실시간 네비 + 개발자 선언 백도어**로. 원칙: **persist는 인간이 선언한 것(백도어·컨트롤맵, 작음)만, 자동학습은 임시 캐시로만 — 마이그레이션 대상 자산을 만들지 않는다.**
- **정확한 키바인딩 자동 매핑 안 함** — 소스가 없음(§9). 필요 시 개발자 선언 컨트롤맵.
- 흔한 컨트롤("Enter=진행")을 Step에 상시 저장 안 함 — 런타임/게임지식 해소 영역.
- TR 단위 실행, Agent 소비 로직 본체 — 별도 트랙(팀원). 본 플랜은 데이터/계약 형태를 정의해 맞물리게 한다.

## 확정 사항 (논의 합의, §1~§9)

1. **재사용 안 함 → 최적화 저장**: `test_scenario_case.steps JSONB`(정규화 X, 행수 불변), 배열 + 내부 `id`.
2. **TC에 안 박음(TC 재사용성)**: Step은 **TS-position 종속**, TC 본체는 깨끗하게 유지.
3. **Agent 초안 + 사용자 추가/수정** 가능.
4. **두 종류**: setup(사전조건 경로) / guide(TC 실행).
5. **setup은 TS 순서 확정 후 제작**(전이는 시퀀스 종속 — 접근 시점에 따라 경로가 달라짐).
6. **guide는 사전조건 만족 상태에서 기술**.
7. **같은 TC라도 스텝이 다를 수 있음 → 비재사용**. setup·guide 둘 다 TS-position에 저장.
8. **TS 없이 TC 실행**: 지금 불가(scenarioId 필수) → **TC를 임시 1-item 시나리오로 래핑**하면 가능.
9. **키바인딩 자동매핑 불가**(소스 없음); **키/클릭 구분은 interactable 유무로 추론 가능**; **값싼 개선**: interactable invokable method를 `scene.render`에 노출(SDK 변경 없음).

## Context / Constraints (코드 확인 2026-08-05)

- 재사용은 TC(의도)에 산다. Step은 시퀀스/컨텍스트 전용 글루라 비재사용(정상). 상세 메모: `qa-step-layer-design-decision`.
- 저작 세션 open이 `game_context`(=최신 빌드 씬 스캔)를 프롬프트에 주입 중. 저작 프롬프트가 "의도로 쓰고 컨트롤/식별자 금지, 바인딩은 실행 시점" 강제 → **저작=의도/실행=바인딩** 이미 구현.
- 씬 스캔 = `{scenesInBuild:[씬이름=노드], scannedScenes:[{name,UI}]}` — **노드만, 엣지(전이) 없음**(그래프 부재).
- 실행 계약: `CreateQaTryRequest.testScenarioId` **필수**, QaContext = `test_scenario_id + ScenarioDraft(steps)`. Agent는 `ScenarioStep{step,title,state,action,expected}` 단위 실행(`app/agents/qa/runner.py`).
- ⚠️ 단절: `ScenarioReconcileService`가 payload를 `{title,description}`만 저장, case 링크(`test_scenario_case`)는 실행에 안 물림 → 챗봇 저작 새 시나리오 steps 비어 실행 불가. **Step 계층이 이 "TC조합→실행step 변환"을 채움.**
- `interactables`는 클릭 근거 제공(id/name/type/label/rect). 원시 씬 extra의 invokable method는 `scene.render`가 현재 버림. 키보드/전역 입력은 씬에 없음(사각지대).
- SDK 조작 메서드 어휘: `button_click / enter_text / key_click / key_down·up / mouse_down·up / move_mouse / capture_screen / pause·resume_time`.
- 결과 타입: `RunResult=PASSED|FAILED`, `StepStatus=STARTED/COMPLETED/FAILED/CANCELLED` — BLOCKED/SETUP-FAILED 없음.

## Step 데이터 형태

`test_scenario_case.steps JSONB DEFAULT '[]'` — 한 position에 스텝 배열:

```json
[
  { "id": "uuid", "kind": "setup",  "assert": false, "intent": "3번씬으로 이동", "hint": "load_scene(3) 또는 포탈" },
  { "id": "uuid", "kind": "guide",  "assert": true,  "intent": "튜토리얼 진행", "hint": "Enter", "input": "keyboard" },
  { "id": "uuid", "kind": "verify", "assert": true,  "intent": "골드 감소 확인", "observe": "gold" }
]
```

- `kind`: setup(도달) / guide(실행) / verify(검증, 선택).
- `assert`: setup은 false(판정 안 함, fast-forward). guide/verify는 true.
- `intent`: 자연어 의도(코드 식별자 금지). `hint`: 선택적 근거(키/백도어). `input`: keyboard|click(추론값, 선택). `observe`: verify가 볼 대상.
- advisory: 씬과 다르면 Agent가 무시·자기판단(프롬프트 프레이밍으로 강제).

## Approach (Checklist)

- [ ] **Step 0: Recon** (완료 — 위 Context가 결과)

- [ ] **Step 1: 저장 (DB)**
  - Flyway: `ALTER TABLE test_scenario_case ADD COLUMN steps JSONB NOT NULL DEFAULT '[]'`(develop 충돌 없는 다음 버전).
  - `TestScenarioCaseEntity`에 steps 필드, 조합 저장/조회 경로(`ScenarioCompositionService`, `ScenarioReconcileService`) 왕복 반영.
  - 앱 레벨 검증(kind enum, assert 필수, id 부여). 정규화 `test_step` 테이블 지양.

- [ ] **Step 2: 활용 (저작 근거)**
  - 저작 Agent가 `game_context`(씬 스캔=노드/UI) 근거로 **position별 steps 초안(의도 레벨)**. setup은 TS 순서 확정 후.
  - 클릭류는 interactable에서 근거 잡음. 키류는 자동 근거 없음 → **사람이 hint에 채우거나 런에서 학습**(테스트 단계 현실).
  - 저작 결과 계약: `ScenarioResult`에 position별 steps 필드 추가(또는 별도 커밋 경로). 프롬프트에 "Step은 advisory·의도 레벨, 근거 없으면 비워라" 명시.

- [ ] **Step 3: 적용 (실행)**
  - `QaTryService`가 Agent로 보낼 `ScenarioDraft` 조립 시 **TC조합 + position steps를 평탄화해 1..N 시퀀스**로 변환(빠진 변환을 여기서 구현).
  - 실행부(팀원): `assert:false`(setup)=fast-forward·판정 안 함; verify=어디 볼지만(verdict는 Agent); arrange 실패=**SETUP-FAILED**.
  - envelope 결과 타입에 `BLOCKED/SETUP-FAILED` 추가(또는 summary 구분) — arrange 실패 ≠ 테스트 실패.

- [ ] **Step 4: 노출 (FE)**
  - 조합 UI에서 position별 Step 목록 표시·편집(추가/순서/삭제, kind·intent·hint). 챗봇 초안 → 사용자 검수.

- [ ] **Step 5: TC 단독 실행 (§8)**
  - `CreateQaTryRequest`에 `testCaseId` 분기(또는 별도 진입점): TC `{precondition,title,expected}` → **임시 1-step ScenarioDraft**로 변환해 기존 실행부에 태움.
  - 단독은 "게임 현재 위치에서 시작" — 전제 가벼운 TC 스모크 테스트용.

- [ ] **Step 6: 근거 개선 (§9, 값쌈)**
  - `scene.render`에 interactable의 **invokable method 노출**(원시 extra엔 이미 있음, SDK 변경 X) → 클릭 근거 정밀화 + 키/클릭 추론 보조.

- [ ] **Step 7: 실전 테스트 (핵심 — 이 플랜의 목적)**
  - Step 유/무로 **Agent 실행 성공률·경로탐색·오판율** 비교. setup이 arrange 실패를 줄이나, guide가 키류 조작을 뚫나 측정.
  - 결과로 다음 결정: Step 계층 유지·확장 여부, 게임지식/컨트롤맵 투자 여부, 백도어 요청 우선순위.

## Validation

- **Commands to run:**
  - Orche: `TESTCONTAINERS_RYUK_DISABLED=true ./mvnw -o test` (마이그레이션 체인 + 조합 steps 왕복 + 변환 + TC단독 래핑)
  - Agent: `uv run pytest` (평탄화 계약·setup fast-forward·arrange 귀속·render 개선)
  - FE: `npm run build`
- **Expected output:** 조합에 steps 왕복, QaTry가 steps 포함 ScenarioDraft 전달, setup은 판정 없이 통과·실패 시 SETUP-FAILED, TC 단독 실행 성립.

## Risks & Rollback

- **Risks:**
  - Step이 "가벼운 가이드"→"강제 스크립트" 드리프트(거의 모든 position에 스텝 / 같은 가이드 반복 / 어기면 fail). → 경보 감시; 반복 가이드는 게임지식으로 승격.
  - 키류 근거 부재로 guide 품질 낮음(§9). → 테스트에서 사람 hint로 보강, 필요 실측 시 컨트롤맵 논의.
  - 실행부 계약(팀원)과 평탄화/플래그 불일치. → Step 3 계약 선합의.
  - JSONB 무결성 부재 → 앱 레벨 검증.
- **Rollback steps:** 컬럼 additive(`DEFAULT '[]'`) — 실행부가 steps 무시하면 기존 동작 유지. 변환 단계에서 steps 무시 토글, 컬럼 남겨도 무해.

## Open Questions (상당수는 Step 7 테스트가 답함)

- Step이 실제로 Agent 성공률을 올리나? (테스트로 판정 — 이 플랜의 핵심 가설)
- 키류 조작을 사람 hint로 채우는 게 지속가능한가, 아니면 컨트롤맵(개발자 선언)이 필요한가?
- 결과 타입 확장 범위: `SETUP-FAILED`만 vs `BLOCKED/SKIPPED`까지 — 실행부(팀원)와 합의.
- SDK 백도어(상태-셋업/텔레포트) 노출을 제품 요청으로 올릴지 — 깊은 씬 arrange의 유일한 실질 해법.
- 저작 결과 계약: steps를 `ScenarioResult`에 실을지 별도 커밋 경로로 둘지.
