# 2026-08-11 — QA 스텝 기대 판정 라벨과 결정적 채점자

- Date: 2026-08-11
- Jira: ARTEL-301
- Status: Draft

## Goal

QA 에이전트의 스텝 판정은 **자기채점**이다. 에이전트가 "이 스텝 통과"라고 말하면 그 값이 그대로
지표가 된다 — ARTEL-299가 승격한 `steps_passed`가 바로 그 값이다. 관대한 모델이 점수가 높게
나오고, "전부 통과"라고 답하는 전략이 만점이다. 이 숫자로 모델 순위를 매기면 틀린다.

작성 시점에 사람이 각 스텝에 **통과해야 하는지 실패해야 하는지**를 라벨링하고, 실행 결과를 그
라벨과 대조하면 그 게임이 끝난다. 실패해야 하는 스텝이 섞여 있으면 "전부 통과" 전략이 **최악**
점수가 된다. 게임 빌드에 버그를 심는 방식보다 싸다 — 작성 화면에서 끝난다.

2연속 PR 중 두 번째다. 앞 PR(ARTEL-299)이 `qa_try_score`라는 그릇을 만들었고 여기가 첫 채점자를
넣는다.

## Non-goals

- `artel-agent-server` 수정. **에이전트는 라벨을 몰라야 하므로 그쪽에 들어갈 것이 없다.**
- LLM 심판, seeded-bug 빌드.
- 점수를 보여주는 대시보드 — 후속.
- 작성 화면 UI — 별건(ARTEL-302, artel-home).
- 실험 엔티티, 지식창고.

## Context / Constraints

라벨 누출이 이 작업의 **유일한 치명적 실패**다. 나머지는 틀려도 고칠 수 있지만, 누출된 채로
돌아간 런의 데이터는 버려야 한다. 그리고 그 사고는 조용하다 — 점수가 좋아 보일 뿐이다.

`ScenarioStep`(저장 모델)이 쓰이는 곳을 전수 조사한 결과:

| 경로 | 라벨이 가도 되나 |
|---|---|
| `test_scenario.payload` 저장 | 여기가 집 |
| `ScenarioResponse.payload` (FE 읽기) | 필요 |
| `Create`/`Update`/`Approve` 요청 (FE 쓰기) | 필요 |
| `AgentScenario`/`AgentStep` (QA 실행 계약) | 이미 **별도 타입** — 구조적으로 안 샌다 |
| `CurrentScenario.steps` → 작성 챗봇 컨텍스트 | **누출** |
| `ScenarioResult.steps` → 챗봇 출력 → payload 통째 교체 | **사람이 단 라벨이 지워진다** |

마지막 것은 원래 스펙에 없던 문제다. `ScenarioReconcileService`가 에이전트 턴마다 payload를 통째로
갈아치우므로, 챗봇으로 시나리오를 한 번 고치면 그 시나리오의 정답지가 전부 사라진다.

## 설계 판단

### 1. 라벨은 nullable 3상태, 기본값 없음

`expected_passed: Boolean?` — `null`은 "채점하지 않음"이지 "통과해야 함"이 아니다. 기본값을 true로
두면 라벨을 안 단 스텝이 전부 통과 기대로 세어져 정확도가 부풀려지고 그 오류는 조용히 지나간다
(V27의 `cited`와 같은 규율). payload가 JSONB라 마이그레이션은 없고, 기존 시나리오는 전부 null이며
**백필하지 않는다** — 사람이 판단해야 하는 값을 기계가 지어내면 정답지가 오염된다.

### 2. 에이전트 경로는 `@JsonIgnore`가 아니라 **타입**으로 가른다

같은 클래스에 `@JsonIgnore`를 걸 수는 없다 — 저장 직렬화에는 그 필드가 **있어야** 하기 때문이다.
Jackson view는 한 곳만 틀려도 조용히 새고, 그 사실이 리뷰에 보이지 않는다.

그래서 작성 챗봇 계약에 `ChatScenarioStep`을 따로 둔다. QA 실행 계약이 이미 `AgentStep`으로 갈려
있는 것과 같은 판단이고("직렬화 계약을 타입 있는 DTO로 드러내 필드명·구조 변경을 컴파일이 잡도록"),
여기는 그 판단을 **두 번째** 에이전트 경로에 적용한 것이다. 새 작성 필드가 늘어도 이 타입에
명시적으로 더하지 않는 한 나갈 수 없다.

방어 테스트는 필드를 하나하나 확인하지 않는다 — **직렬화 결과 문자열 전체에 그 키가 없음**을
단언한다. 중첩된 자리로 새는 경우까지 잡히고, 나중에 필드가 늘 때 이 테스트가 걸린다.

### 3. 챗봇이 고친 시나리오의 라벨은 **자리가 그대로일 때만** 살린다

에이전트는 라벨을 본 적이 없으므로 돌려줄 것도 없다. 저장 직전에 옛 payload에서 건져 얹되,
**같은 인덱스에서 `action`과 `caseId`가 모두 같을 때만** 얹는다.

위치만 보고 옮기지 않는 이유: 에이전트는 스텝을 끼워 넣고 지우고 순서를 바꾼다. 인덱스만으로 이으면
라벨이 한 칸씩 밀려 엉뚱한 스텝에 달라붙는다. **잘못 달린 라벨은 없는 라벨보다 나쁘다** — 기계가
지어낸 정답지가 되고 채점은 그것을 사람의 판단으로 믿는다. 살릴 수 있는 것만 살리고 나머지는
사람이 다시 단다.

### 4. 채점 입력은 요약이 아니라 **스텝 판정 프레임**이다

종단 요약(`summary.steps.items[]`)에도 같은 데이터가 있지만, 소켓이 죽은 런에는 요약이 없다.
요약만 보면 그런 런이 통째로 미보고가 되어, 실제로 절반을 판정하고 죽은 런과 아무것도 못 한 런이
같아진다. `qa_log`의 스텝 판정 프레임은 두 경우 모두에 남아 있다 — 종료 경로와 무관하게 한 가지
소스를 쓴다.

같은 스텝이 두 번 보고되면 나중 것이 이긴다. 에이전트가 재시도하고 다시 판정할 수 있고, 그때 그
스텝에 대한 최종 입장은 마지막 것이다.

### 5. 혼동행렬을 스칼라로 접지 않는다

오탐(멀쩡한 것을 실패라 함)과 미탐(실패해야 할 것을 통과라 함)은 무게가 다르다. QA 에이전트에게
미탐이 훨씬 나쁘다 — 못 찾은 버그는 출시된다. 정확도 하나로 접으면 그 방향이 사라져 미탐이 많은
모델과 오탐이 많은 모델이 같은 점수로 보인다. 네 칸을 그대로 남기고, 스칼라가 필요한 화면이
자기 가중치로 파생시킨다.

**미보고는 세 번째 상태다.** 일치로 세면 일찍 죽은 런이 만점이 되고, 불일치로 세면 죽었다는 사실이
스텝 수만큼 이중 계산된다. 라벨이 null인 스텝은 분모에도 들지 않는다.

### 6. 채점은 **모든 종료 경로**에서 돈다

COMPLETED / FAILED / CANCELLED / 소켓 사망 넷 다. 정상 종료에만 걸면 잘 죽는 모델의 최악 런이
통째로 빠져 그 모델이 실제보다 좋아 보인다 — ARTEL-299가 커버리지를 센 것과 같은 편향이다.

호출부가 넷이라 **삼킴을 채점자 안에 둔다.** 호출부마다 try/catch를 적으면 그중 하나가 언젠가
빠지고, 그 하나가 WebSocket 수신 체인 안이면 이미 끝난 런이 실패로 뒤집힌다.

### 7. 지표 컬럼은 승격하지 않는다

V25는 "컬럼은 GROUP BY용 사본이고 진실은 JSONB"라고 했고, 승격은 **집계가 실제로 그 축으로 팔 때**
하는 것이다. 지금 축별 집계는 점수를 읽지 않는다 — 점수 화면 자체가 후속이다. 어느 칸으로 접을지
모르는 채로 컬럼을 만들면 소비처가 생길 때 다시 골라야 하고, 그 사이 모든 행에서 0으로 읽히는
컬럼이 하나 늘 뿐이다. 점수 화면이 어떤 컷으로 그룹핑하는지 본 뒤에 올린다.

### 8. `detail`에 기대 벡터를 스냅샷으로 박는다

시나리오 라벨은 나중에 고쳐진다. `grader_version`만으로는 부족하다 — **라벨은 시나리오마다 다르기
때문**이다. 무엇과 대조해 나온 점수인지가 행 안에 있어야 옛 점수와 새 점수를 비교할 수 있다.

## Approach (Checklist)

- [x] **Step 0: Recon** — `ScenarioStep` 전수 조사, agent 쪽 `QaStepResult`/`build_summary`,
      `routeStatus`의 2-scope, 종료 경로 목록
- [x] **Step 1: 라벨 필드** — `ScenarioStep.expectedPassed`
- [x] **Step 2: 누출 차단** — `ChatScenarioContract.kt`, `CurrentScenario`/`ScenarioResult` 타입 교체,
      `RunScenarioReader` 투영
- [x] **Step 3: 라벨 보존** — `ScenarioReconcileService.carryLabels`
- [x] **Step 4: 채점자** — `ExpectedStepsGrader`, `QaTryScoreEntity`/`QaTryScoreRepository`,
      종료 경로 네 곳 배선
- [x] **Step 5: 테스트** — 누출 방어 6건, 채점 9건
- [x] **Step 6: 리베이스** — ARTEL-299(develop 머지 + V33→V35 renumber) 위로 옮겼다.
      충돌 셋 다 "양쪽이 같은 자리에 각자 한 줄을 더한" 모양이었다:
      - `QaExecutionFailureService` — ARTEL-293의 인용 확정과 이 PR의 채점이 종료 경로 세 곳에서
        만난다. **기록이 먼저, 파생이 나중** 순으로 둘 다 남겼다.
      - `RunScenarioReader` / `ScenarioReconcileService` — ARTEL-291이 `payload` JSONB를
        `title`/`description`/`steps` 컬럼으로 승격하며 `toDraft`/`withDraft`를 냈다. 라벨 보존을
        그 위로 옮겼고, `draftJson`(JSON 조립)은 `draftFor`(ScenarioDraft 반환)로 바뀌었다.
      - 채점자도 `scenario.payload` 대신 `scenario.toDraft(objectMapper)`를 읽는다 — 저장 형태를
        아는 쪽은 저장 계층 하나로 둔다.

## Validation

- **Commands to run:**
  - `./mvnw -o test -Dtest=ExpectedLabelLeakIntegrationTest`
  - `./mvnw -o test -Dtest=ExpectedStepsGradingIntegrationTest`
  - `./mvnw -o clean test`
  - `./scripts/check-flyway-migrations.sh` (리베이스 후)
- **Expected output:** 전부 통과. 특히:
  - 실행 계약·챗봇 컨텍스트 직렬화에 `expected_passed`가 없다
  - 라벨은 저장 payload에는 남는다
  - 챗봇이 고쳐도 그대로인 스텝의 라벨은 살고, 바뀐/밀린 스텝에는 안 붙는다
  - 혼동행렬 네 칸이 각각 세어진다
  - 미보고가 일치로도 불일치로도 안 세어진다
  - null 라벨이 분모에서 빠진다
  - 라벨을 고쳐도 옛 점수의 스냅샷이 그대로다
  - 소켓 사망·취소 경로에서도 채점이 돈다
  - 채점 실패가 런을 죽이지 않는다

## Risks & Rollback

- **Risks:**
  - **머지 순서 의존이 남는다.** base가 ARTEL-299라 그쪽이 먼저 머지돼야 한다. 그때 GitHub이
    base를 develop으로 자동 재지정한다. 이 브랜치는 마이그레이션을 추가하지 않으므로 Flyway
    쪽으로는 더 걸릴 것이 없다(`check-flyway-migrations.sh` 통과).
  - `ScenarioResult.steps` 타입이 바뀌었다. 와이어 형태는 그대로라 에이전트 계약은 무손이지만,
    이 DTO를 코틀린에서 직접 만드는 코드는 컴파일이 걸린다(의도).
- **Rollback steps:** `git revert`. 라벨은 payload의 선택 필드라 없어도 읽는 쪽이 깨지지 않고,
  `qa_try_score` 행은 소비처가 없어 남아도 동작을 바꾸지 않는다.

## Open Questions

- 없음. 작성 화면(3상태 컨트롤)은 ARTEL-302로 분리했고, 그쪽은 스텝 에디터가 있는
  artel-home `feat/frontend-scenario-step-editor-ARTEL-289` 위에 쌓는다.
