# 2026-07-28 — TestCase/TestScenario/Run 재설계 (개요)

- Date: 2026-07-28
- Jira: None
- Status: Draft (설계 논의 확정, 구현 착수 전)

## 명칭 확정 (2026-07-28)
`Run`은 "묶음(정의)"과 "실행(인스턴스)"을 겸해 모호했다 → 분리한다:
- **TestSuite** = 여러 TestScenario를 묶은 **재사용 검증 세트(정의)**. (기존에 "Run"이라 부르던 것)
- **TestRun** = TestSuite를 1회 실행한 **인스턴스**(상태·결과). (신규 개념)
- **`qa_try`는 그대로 유지** — 이름·구조 안 건드림. TestRun ↔ qa_try 배선/관계는 **P4**에서 정한다.
- 관계: **TestSuite → TestRun**.

## Goal
"TestScenario 하나"만 있던 구조를 **계층**으로 재편한다.
```
TestSuite (검증 세트=정의)  ─┐  묶인 시나리오 집합 + (실행 시) 순회·리셋 전략
  └ TestScenario ───────────┤  의미 있는 실유저 여정 = 케이스의 순서형 조합(충실도)
        └ TestCase ──────────┘  특정 기능 1개 검증(재사용, 회귀에 유리)
   (TestSuite 실행 → TestRun 인스턴스 생성; qa_try 계열)
```
- **TestCase**: "특정 버튼을 누르면 특정 기능이 활성화" 수준의 **기능 단위 검증**. 프로젝트 전반 재사용. ⚠️ **Agent 산출은 대분류·제목·사전조건·기대효과가 전부 "자연어"** — 구조화 술어가 아님(P2에 직접 영향).
- **TestScenario**: TestCase들을 **의미 있는 순서**로 엮은 여정("상점에서 물건 구매"). 순서 자체가 가치.
- **TestSuite**: 검증할 시나리오 묶음(정의). 실행(TestRun) 시 **어떻게 물리적으로 순회할지**(리셋 최소화)가 얹힘.

## 핵심 설계 원칙 (논의로 확정)
1. **의미(WHAT) ↔ 실행경로(HOW) 분리.** 시나리오는 깨끗한 현실 흐름(선언)으로 두고, 리셋·순회·최적화는 **Run/실행 계층**이 담당한다. → 시나리오 충실도가 커버리지 최적화 때문에 희석되지 않는다.
2. **커버리지 최적화는 Run 계층에 산다.** Run만이 여러 시나리오를 한눈에 보므로 그 안에서 **시나리오 간(cross-scenario) 순회**를 최적화한다. ("Run끼리 최적화"가 아님 — Run 내부.)
3. **세분화 기준 = 기능 1개 상호작용 + 관측 가능한 결과(들).** 동작(action)과 검증(assertion)을 섞지 않는다. "구매 버튼→①골드 차감 ②아이템 획득"이 **1개** 케이스.
4. **조합 견고성 = precondition/postcondition(상태 술어).** 케이스에 전제/사후를 명시해야 시나리오 조립·리셋 최소화(MBT)가 가능.

## 근거 데이터의 3축 (경쟁 아니라 상보)
| 데이터 | 역할 | 단계 |
|---|---|---|
| **빌드타임 메타데이터**(어셈블리 전 코드 주입, 팀원 작업) | **스키마·구조** — 어떤 기능/메서드/관측치가 존재하나 → TestCase 분해 | 작성(pre-check) |
| **Agent 산출 = TestCase(구조는 정규화, 값은 자연어)** | 필드 = **대분류·제목·사전조건·기대효과**로 **구조는 정규화**(→ 컬럼 저장 가능) 되지만, **사전조건·기대효과의 값은 자연어 텍스트**. ⚠️ 즉 기계-비교 가능한 술어가 아님 → P2가 이걸 직접 못 씀(정규화 or LLM 오라클 필요) | 작성 결과 |
| **knowledge 도메인**(문서: RULE/OBJECTIVE 등) | **의미** — 그 기능이 무엇을 해야 하나(expected) | 작성 |
| **GAME_STATE**(런타임 SDK) | **인스턴스·값** — 실제 관측치로 expected 대조 | 검증(runtime) |

→ 메타데이터 = 스키마, GAME_STATE = 값. **같은 계약의 양끝.** knowledge는 expected 의미 보강.

## 관측/조작 표면 (코드 확인 완료 — 이미 풍부함)
- **관측**(`AgentGameState`): `scene` + `interactables[{id,name,type,actions:[String],label,...}]` + **`observables: Map<name,{value,type}>`** + `recentActions[{target,name,success,returnValue,error}]` + 원본 `SdkComponent.states[{tag,name,type,value}]`.
- **조작**(`ActionItemDto`): JSON-RPC `{method, params}`. 각 interactable이 `actions:[String]`로 호출 가능 메서드를 **자기서술**.
- → **TestCase의 action·precondition·expected가 한 어휘로 표현 가능**: action=`{interactable,method,params}`, pre/expected=`observables/states/recentActions`에 대한 술어.
- ⚠️ **`observables`는 free-form 맵(스키마 없음)** → 빌드타임 메타데이터가 **observable/action 레지스트리(이름+타입)** 를 제공해야 작성 단계가 관측 가능 표면 안에서 이뤄진다. **관측 천장 = 주입 getter가 이 맵을 무엇으로 채우나.**

## 주입 훅의 3중 임무 (팀원 작업 확장 여지 — 대비만)
1. 메타데이터 추출(있음) → 기능 분해·레지스트리
2. **getter** → `observables`/`states`를 채워 **관측성** 확보
3. **setter** → 상태 초기화/전제 점프. 단 **게임 자체 변경 메서드 래핑**(필드 직접쓰기 금지, desync 위험), **clean slate는 재시작 병행**, 점프 후 **무결성 체크**.

## 단계 = 브랜치 (각각 별도)
| 단계 | 내용 | 플랜 파일 |
|---|---|---|
| **P1** | Run→Scenario→Case 상하관계 + DB 스키마 | `2026-07-28-run-scenario-case-hierarchy-and-schema.md` |
| **P2** | 시나리오 내부 순서 보존 + 시나리오 간 배치로 리셋 최소화. ⚠️ **자연어 사전조건이라 Orche 단독 기호 알고리즘 불가**. MVP=최적화 없이 시나리오 사이 재시작; 최적화는 **(a) NL→구조화 정규화** 또는 **(b) LLM 호환성 오라클**이 준비된 뒤 얹는 후속. Orche는 정렬 메커니즘만 소유, 호환성 판정 입력은 (a)/(b)가 공급 | `2026-07-28-scenario-ordering-reset-minimization.md` |
| **P3** | FE(artel-home): Canvas 시나리오 도면 + TestCase 사이드바 DnD (기존 DnD/챗봇 유지) | `2026-07-28-frontend-canvas-testcase-dnd.md` |

## 이 밖에 필요한 작업 (질문 "다른 수정사항?"에 대한 답 — 별도 브랜치 후보)
- **P4 · QA 실행 파이프라인 개편**: 현재 `qa_try`는 **단일** `test_scenario_id`를 실행하고 `QaAgentSessionContext.scenario`(단일 JsonNode)를 Agent로 넘긴다. Run(복수 시나리오/케이스) 실행으로 바꾸려면 qa_try↔Run 매핑 + Agent `/qa-sessions` 계약 변경 필요. **P1 스키마에 의존, 실행은 P2와 맞물림.** (규모 큼 — 독립 브랜치)
- **P5 · TestCase 검증 생명주기**: `DRAFT→VERIFIED→BROKEN` + `last_verified_build`. 실행 결과로 상태 갱신. (스키마는 P1에 필드로, 갱신 로직은 P4/P5)
- **dep · 메타데이터 수집 파이프라인**: 빌드타임 메타데이터를 Orche로 수집(knowledge 적재 패턴 유사) + observable/action 레지스트리 저장. **팀원 작업 스펙 확정 후.**
- **dep · Agent 작성 계약 변경**: 챗봇이 `ScenarioDraft{steps}` → **TestCase 생성 + TestScenario 조합** 산출로. Agent `/sessions` 계약. (Agent팀 협의)
- **dep · 기존 데이터 마이그레이션**: 현 `test_scenario.payload`(inline steps) → 새 모델. 하위호환/전환 전략.

## 교차 결정 (착수 전 합의 필요 — 각 플랜의 Open Questions로도 반복)
1. **스냅샷 vs 참조**: 시나리오가 TestCase를 복사(고정)하나, 라이브 참조하나? (회귀 안정성 vs 즉시 반영)
2. **술어 언어**: precondition/expected를 어떤 형식으로? (단순 `key op value` vs 표현식 트리)
3. **qa_try ↔ Run 매핑**: 실행단위를 Run으로 승격할지, qa_try를 Run에 종속시킬지.
4. **메타데이터 레지스트리 계약**: 팀원 주입이 뽑는 관측치·메서드 목록 형식.

## Non-goals (이번 재설계 범위 밖)
- 실제 빌드타임 주입 구현(팀원 작업). 우리는 **수집·소비 구조 대비**만.
- 완전한 MBT 최적화(P2는 그리디/폴백부터). 
- Run 위 상위개념(TestPlan 등) — 관망(설계상 여지만).

## Open Questions
- P4/P5를 P1~P3와 병행할지, P1 머지 후 순차로 갈지.
- 메타데이터 미완성 상태에서 어디까지 선구현할지(스키마·구조는 선행 가능, 자동작성은 대기).
