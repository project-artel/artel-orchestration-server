# 2026-08-07 — QA Step 모델 재설계 후속 단위

- Date: 2026-08-07
- Jira: None (ARTEL-206 계열)
- Status: Draft

## Goal
PR #97(시나리오=Step 리스트 재설계 + Orche QA 실행 전달 계약)이 정의한 새 모델 위에서, QA 저작·실행·FE·정리를 완결한다. 모델: **시나리오 = 순서 있는 Step 리스트, 각 Step은 행위 하나 + 옵션 `caseId`. 연속 동일 caseId = 한 TC 검증 구간(precondition→action들→expected).**

## Non-goals
- TC 스펙(CSV: 씬/사전조건/테스트스텝/기대결과/근거) 자체의 최종 확정(별도 트랙; 확정되면 U7).
- 역방향/커버리지 질의(당장 불필요; 필요 시 U6).
- "TC 선택 → 스텝 로드" 역방향 뷰(보류).

## Context / Constraints
- #97이 Orche 저장(payload.steps)·실행 전달(`agentScenario` steps+TC 리졸브)·reconcile을 담음(develop 기준, 397 그린).
- 실행 계약: `{title, description, steps:[{action, case_id, hint, input, case:{id,scene,precondition,test_step,expected}|null}]}`. `case` 필드명은 TC 스펙(CSV) 미러.
- 구모델(cases 조합) PR #90/#92/#94/#58/#59/#34는 #97로 대체 → Close.
- 런 단위 실행(#93 259 qa_run / #57 258 run loop / #33 268 run FE)은 cases 모델 위에 있어 새 모델과 재정합 필요.
- 로컬 핵: 임베딩 버퍼는 #89(278) 머지됨; 챗봇 모델 hack만 남음(stash `local-hacks`).

## Approach (Checklist)
- [ ] **U1 — Agent: steps 소비 실행** — 실행 계약(steps[] + case 리졸브)을 소비. 연속 동일 case_id를 한 TC 구간으로 묶어 [precondition 검증 → action들 실행 → expected 판정]. 사전조건 미충족=BLOCKED(결함 아님), 기대결과=PASS/FAIL. case_id=null 스텝은 수행만. (구 261/281-agent 대체)
- [ ] **U2 — Agent: 시나리오 저작 시 steps 생성** — 사용자 자연어→steps[] 초안(행위 나열 + 검증 지점에 caseId 매핑). scenario_id로 추가/수정. (구 281-agent authoring 대체)
- [ ] **U3 — FE: 시나리오 Step 편집 UI** — 시나리오=steps 리스트 편집(추가/순서/편집), 스텝에 TC 연결(TC 검색·선택 picker), 연속 caseId를 TC 박스로 그룹 표시. 챗봇 제안/커밋 steps[] 왕복. (구 280 대체)
- [ ] **U4 — Orche: 런 단위 실행 재정합** — #93(qa_run 라이프사이클)·#57(run loop)을 새 steps 계약 위로 재구성(런이 시나리오들을 순차 실행, 각 시나리오는 steps 계약으로 Agent에 전달). 필요 시 259/258 재작업.
- [ ] **U5 — Orche: test_scenario_case DROP 마이그레이션** — 코드에서 폐기된 조합 테이블을 스키마에서 제거(다음 Flyway 슬롯). 잔여 데이터 확인 후 DROP.
- [ ] **U6 — (필요 시) 역방향/커버리지 질의** — "이 TC를 쓰는 시나리오" / 커버리지가 필요해지면 payload JSONB GIN 인덱스 + containment 질의. 없으면 스킵.
- [ ] **U7 — TC 스펙 확정 후 재구조화** — TC를 CSV 형태(scene/precondition/test_step/expected/basis)로. 확정되면 `agentScenario`의 case 임베드 매핑만 갱신(계약 필드명은 이미 그 이름).
- [ ] **U8 — FE: TR 이름 인라인 편집 재추출** — 구 #34(280)에 있던 TR rename(모델과 무관, 유효)을 별도로 재PR.
- [ ] **U9 — 로컬 핵 정리** — 챗봇 모델 hack(TestScenarioAgentService 기본모델) 정식화 또는 제거(stash `local-hacks`).

## Validation
- **Commands to run:** 각 유닛별 `./mvnw -o test`(Orche) / `pytest`(Agent) / `npm run build`(FE).
- **Expected output:** 전 테스트 그린. E2E: 챗봇 "시나리오 짜줘"→steps 자동생성→FE 확인/편집→QA 실행 시 steps 계약으로 Agent 전달→구간 판정.

## Risks & Rollback
- **Risks:** ① 런 단위(#93/#57)와 새 계약 재정합 시 충돌(cases→steps 전환점). ② test_scenario_case DROP 전 잔여 데이터. ③ TC 스펙 미확정 상태에서 case 임베드 매핑 임시(scene←category, test_step←title).
- **Rollback:** #97은 단일 커밋이라 `git revert` 가능. DROP 마이그레이션은 별도 슬롯이라 독립 롤백.

## 결정 (2026-08-07)
- 구모델 PR 전량 Close 완료: #90/#92/#94(orche), #58/#59(agent), #34(home) — cases 모델 대체. **런 단위 #93(259)/#57(258)/#33(268)도 Close** — cases 전제라, 새 모델 위에서 **새 PR로 재작업**(U1/U3/U4). 유지 PR: #97(재설계), #91(207 삭제).

## Open Questions
- U8 TR rename을 독립 PR로 뺄지, U3(FE) PR에 포함할지.
