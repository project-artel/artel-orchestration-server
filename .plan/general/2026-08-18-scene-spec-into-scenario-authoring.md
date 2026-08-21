# 2026-08-18 — TS 저작에 씬 명세를 물려 도달 불가 경로를 없앤다

- Date: 2026-08-18
- Jira: ARTEL-466 (A) · ARTEL-467 (B) · ARTEL-468 (C)
- Status: A·B·C 구현 완료(로컬 실측 대기)

## Goal

저작이 만든 시나리오에서 **스텝 사이가 실제로 이어지게** 한다.

지금은 에이전트가 `스테이지 위치가 2로 설정된 맵 화면에 진입한다`처럼 **상태를 만들지 않고
그렇다고 치는 스텝**을 쓴다. 실행하면 반드시 막힌다. 프로토타입에서 이 비율을 재고 없앴다.

| 조건 | 상황 1<br>(같은 씬 상태 전이) | 상황 2<br>(씬 4개 건넘) |
|---|---:|---:|
| 지금 (씬 명세 없음) | 66.7% | 100.0% |
| 씬 명세를 프롬프트에 실음 | 75.0% | — |
| 씬 명세를 툴 뒤에 둠 | **0.0%** | 94.3% |
| + 근거 필드 필수 | — | 72.7% |
| + 코드가 검사하고 **삽입** | — | **0.0%** |

`A 20/30 vs C 0/40` → Fisher p = 1.9e-10 · `A 4/5 vs C 0/10`(실행 단위) → p = 0.0037

## Non-goals

- **TC 생성에는 손대지 않는다.** `app/specs_v2`가 결정적으로 66건을 내고 있고, content map으로
  대체하면 재현성이 0%가 된다(같은 입력 5회 → 3·3·18·3·15건, 전 회차 공통 0건). 별도 문서로 정리함.
- **씬 명세를 우리가 만들지 않는다.** ARTEL-440/441이 적재를 담당한다. 우리는 읽기만 한다.
- **경로 탐색을 모델에게 시키지 않는다.** 프롬프트에 그래프를 실으면 오히려 나빠졌다(75%).
- **케이스 순서 자체의 오류는 고치지 않는다.** `position=2`에서 `position==1`을 요구하는 케이스를
  쓴 것 같은 실수는 판단이라 모델·사람의 몫이다. 코드는 사이만 메운다.
- FE 변경 없음. `basis`는 저장·검수용이고 화면에 새로 그릴 것이 없다.

## Context / Constraints

### 이미 develop에 있는 것 (ARTEL-403, #126 머지됨)

```
ScenarioCoverageAudit           unreviewed · missing · ghost · excess 를 저장 전에 센다
ScenarioReconcileService        ReconcileOutcome(applied, findings). 실패면 한 줄도 저장 안 함
TestScenarioAgentService        applyOrRepair · repairPrompt · mergeVerdicts (MAX_REPAIR_ATTEMPTS=1)
ReviewedCases {in, out}         전 건 판정
list_uncovered_cases            에이전트 → WS `uncovered_cases` → orche 가 DB로 답하는 툴
```

**이 작업은 위 구조의 확장이다.** 새 검수 축을 더하고, 새 툴을 하나 더 붙인다.

### 팀이 만들고 있는 것 (읽기만 한다)

| PR | 내용 | 우리가 쓰는 것 |
|---|---|---|
| #135 ARTEL-440 | `V40__create_content_map.sql` — 12테이블 + 뷰 2개 | `v_content_map_capability` |
| #136 ARTEL-441 | evidence 문서를 받아 저장 | 적재 경로 |
| #138 ARTEL-443 | 근거 문서를 의사-C로 렌더 | — |

`v_content_map_capability`가 **"TC 생성기가 읽는 유일한 창구"**로 설계됐다. 저작도 같은 창구를 쓴다.

읽어야 할 컬럼:

```
capability            id · scene_id · status · given_text · control_selector · control_path
                      control_label · interaction · input_key · input_phase · merged_into
capability_effect     category(observable|availability|state) · kind · target · detail · watchable
scene_edge            from_scene_id · to_scene_name · to_scene_id · capability_id
                      given_text · source(static|runtime) · verified_at · observed_count
scene                 id · name · summary
```

`capability.merged_into IS NULL`인 행만 살아 있다(뷰가 이미 걸러 준다).

### 프로토타입 (`artel-agent-server` `proto/scene-spec-tool`, 커밋 안 함)

포팅할 것과 버릴 것이 갈린다.

| 프로토타입 | 실제 |
|---|---|
| `app/agents/scenario/scenegraph.py` — 경로 탐색 | **orche 로 옮긴다** (Kotlin) |
| 씬 명세를 `game_context`에 실어 보냄 | **orche 가 DB에서 읽는다** |
| `find_path`가 agent 로컬 계산 | **WS 툴** (`list_uncovered_cases`와 같은 자리) |
| `basis: str` 한 필드에 문자열 | **필드 셋으로 나눈다** (아래) |
| agent 안에서 검사·삽입 | **orche 검수에서 한다** |

**agent-local로 둔 것은 싸게 만들려던 편의였고, 그 때문에 `unknown` 검증을 못 한다** —
검사하는 쪽이 그래프를 가져야 "정말 모르는 길인가"를 대조할 수 있다. 안 그러면 전부
`unknown`으로 적는 것이 가장 싼 통과 방법이 된다.

### 이름 (팀과 맞춘 것)

```
edge → capability        팀 용어를 쓴다
basis → stepSource       `test_case.step`·`scene_edge.source`와 겹치지 않게 접두를 붙임
SAME → NOT_REQUIRED      "사이에 스텝이 필요한가"라는 한 질문에 답하도록
```

---

## 이슈 분할

한 PR로 하기엔 크다. 셋으로 나누고 **1 → 2 → 3 순서로 쌓는다.**

| # | 제목 | 레포 | 선행 |
|---|---|---|---|
| **A** · ARTEL-466 | 저작이 씬 명세에 경로를 물어본다 | orche + agent | #135 머지 |
| **B** · ARTEL-467 | 시나리오 스텝마다 근거를 남기고 검수가 본다 | orche + agent | A |
| **C** · ARTEL-468 | 메우지 못한 자리를 코드가 채운다 | orche | B |

**C가 이 작업의 결론이다.** A·B는 그것을 가능하게 하는 배관이다. A만 해도 상황 1은 해결되고,
C까지 가야 상황 2가 해결된다.

---

## Approach (Checklist)

### Step 0: Recon

- [x] #135(ARTEL-440) 머지 확인. `v_content_map_capability` 뷰가 develop에 있어야 시작한다
- [ ] 프로젝트 3에 content map이 적재돼 있는지 확인 (`SELECT count(*) FROM capability`)
      — 없으면 골든 맵(`~/Downloads/golden-content-map.json`)을 #136 경로로 넣는다.
      **#136 미머지라 로컬 실측은 SQL 직접 적재가 필요하다** — 남은 유일한 검증 항목
- [x] ~~`TestCaseEntity.metadata->'source'->>'evidence'`와 `capability_evidence.entry_id`의
      형식이 붙는지 확인~~ — **막는 것이 아니었다.** `findPath`는 케이스를 evidence 로 잇지 않고
      그 케이스 자신의 `scene`·`precondition`·`metadata.source.state_after` 로 푼다. 실제로 맞물려야
      하는 이름은 **씬 이름**(그대로 맞는다)과 **변수 이름**(마지막 마디로 맞춘다)이다

---

### A. 저작이 씬 명세에 경로를 물어본다

#### A-1. orche — 경로 계산 (신규)

- [x] `testscenario/service/ScenarioPathService.kt` (신규)

```kotlin
enum class ScenarioPathResult { KNOWN, NOT_REQUIRED, UNKNOWN }

data class ScenarioPathAnswer(
    val result: ScenarioPathResult,
    val capabilityIds: List<Long> = emptyList(),   // KNOWN. 순서대로
    val actions: List<String> = emptyList(),       // capabilityIds 와 같은 길이
    val blockedBy: String? = null,                 // UNKNOWN. 씬 쌍 또는 변수명
)

suspend fun findPath(projectId: Long, fromTestCaseId: Long, toTestCaseId: Long): ScenarioPathAnswer
```

알고리즘 (프로토타입 `scenegraph.py` 이식, 5단계):

1. 두 케이스를 `(씬, 상태)`로 푼다.
   출발 = `precondition`의 가드 ∪ `metadata.source.state_after`
   도착 = `precondition`의 가드
2. **씬이 다르면** `scene_edge`에서 직접 간선을 찾는다. 없으면 `UNKNOWN(blockedBy="A→B")`.
   있으면 그 조작을 경로에 넣고 **알던 변수 값을 전부 버린다** — 화면을 넘으면 무엇이 유지되는지
   보장할 수 없다
3. 도착 가드 중 **어긋나는 것**만 남긴다. 값을 모르는 변수는 어긋난다고 보지 않는다
4. 어긋난 변수마다: ① `capability_effect`에 그 값으로 만드는 것이 있나 → ② `+1`/`-1`로만
   쓰이는 변수면 "되풀이해 옮길 수 있다"로 본다 → ③ 둘 다 없으면 `UNKNOWN(blockedBy=변수명)`
5. 전부 풀리면 `KNOWN` + `capabilityIds`

- [x] **가드 평가는 부등식까지 본다.** 프로토타입은 `==`만 봐서 실제 사전조건의 **58%를 놓쳤다**
      (`==` 33 · `>` 19 · `!=` 10 · `>=` 6 · `<=` 6 · `<` 4). `>=`/`<`/`!=`를 평가하고,
      비교할 수 없으면 위반이라 말하지 않는다

- [x] **일부러 안 하는 것을 KDoc에 적는다** — 씬 간선 1홉만, 한 조작이 두 변수를 바꾸는 경우
      무시, 값을 다단계로 밀지 않음. 깊은 탐색은 없는 지식을 있는 것처럼 보이게 한다

- [x] `testscenario/repository/ScenarioPathRepository.kt` (신규, 계획의 `ContentMapReadRepository`) —
      **쓰기 없음.** 질문 하나(`findEffectsWriting`)만 둔다. 씬·간선·기능 조회는 팀이 만든
      `contentmap` 리포지토리를 그대로 쓰고, 여기 남긴 것은 이름 맞추기 규칙이 그쪽과 다른
      **효과 조회뿐**이다 — 마지막 마디로 맞춰야 해서 남의 계약을 이 사정에 끌어올 수 없다

#### A-2. orche — 툴 프레임

- [x] `dto/AgentSessionDtos.kt`에 `ScenarioPathResultFrame` 추가
      (`UncoveredCasesResultFrame`과 같은 모양: `type`·`correlationId`·본문)
- [x] `TestScenarioAgentService.handleInbound`에 `find_path` 분기 추가
      — `uncovered_cases`/`test_case_search`와 같은 자리. **실패해도 절대 throw하지 않는다**
- [x] `handleFindPathRequest(sessionKey, session, node)` — 성공도 로그를 남긴다
      (ARTEL-403에서 성공 로그가 없어 "안 불렀다"와 "알 수 없다"가 같아 보였던 전례)

#### A-3. agent — 툴

- [x] `app/sessions/channel.py` — `fetch_path(from_case_id, to_case_id)` 추가.
      `fetch_uncovered`와 같은 모양(waiter + correlationId + `_search_timeout`)
- [x] `app/agents/scenario/tools.py` — `find_path` 툴. `build_tools`에 조건부로 붙인다
      (content map이 없는 프로젝트는 툴을 주지 않는다 → 롤백 경로)
- [x] 툴 설명(`FIND_PATH_DESCRIPTION`)에 세 답과 각각의 처리를 적는다.
      **`UNKNOWN`일 때 지어내지 말고 모른다고 말하라**를 명시

#### A-4. 프롬프트

- [x] `app/prompts/scenario/v5/system.md` — 스텝 사이가 바뀔 때 `find_path`를 부르라는 지시.
      v6를 파지 않는다(v5가 아직 배포 전이면. 배포됐으면 v6)
- [x] `prompts-lock.json` 재생성

#### A-5. 검증

- [x] `ScenarioPathServiceTest` — 정답을 아는 사례로 고정
      - 같은 씬·가드 충돌 없음 → `NOT_REQUIRED`
      - 씬 간선 있음 → `KNOWN` + capabilityId
      - 진입 경로 없는 씬 → `UNKNOWN(blockedBy="A→B")`
      - 값을 바꿀 수단 없음 → `UNKNOWN(blockedBy=변수명)`
      - **부등식 가드** → `>=`가 어긋나면 `KNOWN`/`UNKNOWN`
- [x] `TestScenarioReconcileIntegrationTest`에 `find_path` 왕복 추가
      (기존 `인입 test_case_search에 결과 프레임으로 답한다`와 같은 모양)

---

### B. 스텝마다 근거를 남기고 검수가 본다

#### B-1. 계약 (agent → orche)

- [x] agent `app/agents/scenario/schemas.py` — `AuthoredStep`에 필드 셋 추가

```python
step_source: Literal["case", "capability", "unknown"]   # 필수
step_source_capability_id: int | None = None            # capability 일 때
step_unknown_reason: str | None = None                  # unknown 일 때
```

**한 문자열이 아니라 셋으로 나눈 이유** — 프로토타입은 `basis: "unknown:StagePosition을…"`
한 필드였는데 파싱이 필요했고, `case_id`가 있는데 `basis: edge`인 계약 위반이 8건 나왔다.
`step_source_capability_id`를 `int`로 두면 `capability.id`와 타입이 맞고 검사도 조건문 셋으로 끝난다.

- [x] orche `dto/ChatScenarioContract.kt` — `ChatScenarioStep`에 같은 셋 추가 (snake_case 매핑)
- [x] orche `dto/ScenarioModels.kt` — `ScenarioStep`에 같은 셋 추가.
      **JSONB로 저장돼야 한다** — 실행 에이전트가 "이 브리지가 무엇으로 펼쳐지는지"를 알아야 한다
- [x] `ScenarioStep.toChatStep()` — 새 필드도 넘긴다. `expectedPassed`는 여전히 떨어뜨린다

> ⚠️ **`expectedPassed`는 절대 `ChatScenarioStep`에 넣지 않는다.** 두 타입이 갈려 있는 이유가
> 그것이고(ARTEL-301), 새 필드를 추가하며 실수로 합치면 QA 측정 전체가 무의미해진다.

#### B-2. 검수 확장

- [x] `ScenarioCoverageAudit.Findings`에 추가

```kotlin
val ungroundedSteps: List<Int>       // stepSource가 없거나 어긋난 스텝의 위치
val falseUnknowns: List<Int>         // unknown 인데 명세는 아는 길
val unreachableGaps: List<String>    // 메우지 못한 자리. blockedBy 값
```

- [x] 검사 셋 (전부 결정적. **판단 없음**)
      1. `caseId != null` ⟺ `stepSource == CASE`
      2. `stepSource == CAPABILITY` → `stepSourceCapabilityId`가 실재하고 `merged_into IS NULL`
      3. `stepSource == UNKNOWN` → `findPath`가 정말 `UNKNOWN`을 답하는가.
         **아는 길이면 거짓이므로 거부** ← 이게 없으면 전부 `unknown`으로 적는 게 최적 전략이 된다
- [x] `rejectionMessage()`·`summary()`에 새 사유를 넣는다

#### B-3. 프롬프트·검증

- [x] `system.md` — 모든 스텝이 셋 중 하나를 가져야 한다는 규칙
- [x] `ScenarioCoverageAuditTest`에 케이스 추가 (기존 9건 옆에)
- [x] 통합 테스트 — 근거 없는 스텝이 오면 저장되지 않는가

---

### C. 메우지 못한 자리를 코드가 채운다

**이 작업의 결론.** 지적으로는 안 고쳐진다는 것이 실측됐다 — 재작성 2회를 더 시켜도
위반 해소 **0/3**, 오히려 늘어난 경우 **2/3**.

#### C-1. 삽입

- [x] `ScenarioReconcileService`에 `repairByInsertion(scenarios): Pair<List<ScenarioResult>, List<String>>`

```
검증 스텝 사이를 훑는다
  findPath(직전, 다음)
    NOT_REQUIRED → 그대로 둔다
    KNOWN
      사람이 쓴 브리지가 있으면 → 문장은 살리고 stepSource 만 계산값으로 교정
      없으면                    → findPath 의 actions 를 스텝으로 삽입
    UNKNOWN     → `unknown` 브리지를 하나 넣는다. **빈 자리로 두지 않는다**
caseId 가 있는데 stepSource != CASE → 덮어쓴다
```

- [x] 교정 내역을 로그로 남긴다 (`교정 · 브리지 삽입 → capability:1013`)
- [x] **케이스 순서는 고치지 않는다.** 판단이라 여기서 하지 않는다는 것을 KDoc에 적는다

#### C-2. 사용자에게 알린다

- [x] `unknown` 브리지가 들어갔으면 `notice`로 사용자에게 말한다.
      프로토타입에서 **코드가 조용히 채우자 "모른다"고 말한 실행이 4/5 → 1/5로 줄었다.**
      스텝에만 있고 메시지에 없으면 사용자가 모른다
- [x] 문구는 무엇이 막는지를 담는다 —
      `StoryScene→Map_scene 구간은 명세에 없어 실행 방법 미상으로 두었습니다. 알려주시면 채웁니다.`

#### C-3. 검증

- [x] `ScenarioReconcileService` 단위 테스트 — 삽입 전후 스텝 배열
- [x] 통합 테스트 — 씬을 건너뛴 결과가 오면 브리지가 삽입되어 저장되는가
- [x] **회귀 기준 두 개** (프로토타입에서 정답을 아는 사례)
      - `TC136 → TC137` (StagePosition 1→2) → 반복/불가로 판정되어 브리지가 들어가야 함
      - `TC141 → TC142` (position 0→1) → `NOT_REQUIRED` 또는 `KNOWN`

---

## Validation

### 명령

```bash
# orche
./mvnw -o test -Dtest='ScenarioPathServiceTest,ScenarioCoverageAuditTest,TestScenarioReconcileIntegrationTest'
./mvnw -o test                      # 전체

# agent
uv run pytest tests/

# 실측 (로컬 스택)
ARTEL_AGENT_MODEL='openai/gpt-5.6-luna' ./mvnw -o spring-boot:run
.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

### 기대값 — 프로토타입 수치를 재현해야 한다

| 상황 | 요청 | 기준 |
|---|---|---|
| 1 | `맵 화면에서 스테이지 1부터 5까지 순서대로 이동하며 각 스테이지 위치 표시를 검증하는 시나리오를 만들어줘` | 가정함 **0%**, 10회 |
| 2 | `게임을 새로 시작해서 스토리를 지나 첫 전투를 클리어할 때까지의 흐름을 검증하는 시나리오를 만들어줘` | 가정함 **0%**, 5회 |
| 3 | `게임 클리어 화면과 엔딩 화면의 표시를 검증하는 시나리오를 만들어줘` | `unknown` 브리지 + 사용자 통보 |

**가정함** = 어떤 스텝이 요구하는 상태가 직전 스텝이 남긴 것과 다른데 사이에 아무 스텝도 없는 것.
채점기는 `scratchpad/score3.py`에 있다(변수 별칭 표 포함).

---

## Risks & Rollback

| 위험 | 완화 |
|---|---|
| **content map이 얇다.** golden 맵은 capability 18개, `StoryScene`·`EndingScene`이 0개다 | 그 구간은 `UNKNOWN`으로 정직하게 나온다. 프로토타입에서 실제로 그렇게 동작했고 사용자에게 물었다 |
| **`evidence` 키가 안 붙는다.** TC는 실제 메서드를, content map은 `Update`를 가리킨다 | Step 0에서 먼저 확인. 안 붙으면 A를 시작하지 않고 팀원과 정리 |
| **변수 이름이 세 가지다** (`StagePosition`·`MapMove.StagePosition`·`StageDataSingleton.StagePosition`) | 별칭 표를 `ScenarioPathService`에 두고 **판단임을 주석에 명시**. 장기적으로는 content map이 declare해야 함 |
| **삽입한 브리지가 틀릴 수 있다** | `stepSourceCapabilityId`가 남으므로 사후 추적 가능. 실행이 실패하면 그 capability를 의심할 수 있다 |
| **재작성 루프와 충돌** | 삽입은 검수 **전에** 돈다. 삽입으로 메워지면 검수가 통과하므로 재작성이 안 돌고, 못 메우면 기존 루프가 그대로 동작 |
| 씬 간선 1홉만 봄 | 지금 규모(6씬)에서는 문제없었다. 씬이 늘면 `UNKNOWN`이 늘 뿐 틀린 답을 내지 않는다 |

### 롤백

```
C 만 되돌리기    repairByInsertion 호출 한 줄 제거 → B 상태로
B 까지 되돌리기  step_source 를 optional 로. null 이면 검사 건너뜀
A 까지 되돌리기  build_tools 에서 find_path 를 빼면 툴이 사라짐
전부             content map 이 없는 프로젝트는 처음부터 툴을 받지 않으므로 무영향
```

**세 단계 전부 "새 필드가 null이면 예전과 같다"로 되돌아간다.** ARTEL-403의 `reviewed`가
null이면 검사를 건너뛰는 것과 같은 규율이다.

---

## Open Questions

1. **`evidence` 키를 맞추는 일은 누가 하나.** 기존 TC는 `CharacterMove`, content map은
   `Update`(호출 진입점)를 가리킨다. content map 쪽을 `evidence.method`가 가리키는 실제
   메서드로 바꾸는 게 자연스러운데, 그건 #440 계열의 변경이다.

2. **`repeat_until_done`을 content map에 실을 수 있나.** `app/specs_v2/model.py:43`에 이미
   구현돼 있는데 content map에 담길 자리가 없다(`interaction`이 `click`/`press`/`none`뿐).
   실리면 반복 구간의 `UNKNOWN`이 `KNOWN`으로 바뀐다 — **저작만으로도 값이 있는 요청.**

3. **`capability_effect.detail`의 `+1` 같은 상대값을 어떻게 다룰지.** 프로토타입은
   "되풀이해 올릴 수 있다"로 봤는데(`incrementable`), 몇 번인지는 실행에 남겼다.
   그 판단을 유지할지 결정 필요.

4. **A만 하고 멈출지.** A만으로 상황 1(같은 씬)은 0%가 된다. 상황 2까지 필요하면 C가 있어야 한다.
   시간이 촉박하면 **A → 실측 → 판단**도 가능하다.

---

## 근거

- 프로토타입: `artel-agent-server` `proto/scene-spec-tool` (커밋 안 함)
- 실험 스크립트: `scratchpad/{build_spec2,adapt_contentmap,experiment2,score3,repeat_tc}.py`
- 실행 33–105 (프로젝트 3, word-venture)
- 리포트: 씬 명세 저작 정확도 검증 · TC 생성을 에이전트에게 맡길 수 있는가
