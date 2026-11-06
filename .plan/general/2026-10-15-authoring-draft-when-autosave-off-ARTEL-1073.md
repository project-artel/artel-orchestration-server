# 자동저장을 끈 저작 결과를 draft 로 (ARTEL-1073 · 1074 · 1075)

## 왜

`autoApply=false` 는 원래 "저장하지 않고 카드로 낸다"였다. 시나리오를 하나씩 받는 구조(`submit_scenario`)로
바뀐 뒤 `result` 의 `scenarios` 가 비어서 오고, orchestration 은 `session.submitted` 를 아무 데도 내보내지 않는다.
home 은 빈 배열로 카드를 만들지 못한다. 그래서 끄면 결과가 사라진다.

## 결정 (사용자, 2026-10-15)

| 물음 | 답 |
|---|---|
| draft 를 어디 두나 | 서버 메모리(저작 세션). 마이그레이션 없음. 세션이 닫히거나 서버가 다시 뜨면 사라진다 |
| draft 가 있는 채로 다시 말하면 | draft 위에서 이어 고친다 — Agent 는 draft 가 적용된 모습을 현재 상태로 본다 |
| 승인 단위 | 시나리오별 승인 · 버리기 + 모두 승인 |

## orchestration (ARTEL-1074)

- `AgentSession.draft` — `draftId` 로 찾는 항목들. 항목 = 고칠 대상(`scenarioId` 또는 새 것) · 검수를 지난 본문 · 흡수할 번호.
  - 기존 시나리오 수정: `edit-<scenarioId>`. 새 시나리오: `new-<n>`.
- 제출(`submitOne`)이 `autoApply=false` 면 검수(`save=false`)를 지난 것을 draft 에 넣고 `draft` SSE 를 낸다.
  결과 봉투에 실려 온 시나리오(옛 길)도 같은 길로 넣는다.
- 다음 턴의 `currentScenarios` 에 draft 를 겹친다. 새 draft 는 음수 번호(`new-3` → `-3`)로 보인다.
  Agent 가 그 번호를 되돌려 주면 같은 draft 를 고친 것으로 받는다. 저장 경로로는 음수 번호를 넘기지 않는다.
- API (`/api/projects/{projectId}/test-runs/{testRunId}/chat/draft`)
  - `GET` — 지금 draft
  - `PUT /{draftId}` — 사용자가 고친 본문으로 바꾼다
  - `POST /apply` — 고른 draftId 들을 자동저장과 같은 엔진(`reconcile` + `absorb`)으로 반영하고 draft 에서 뺀다
  - `POST /discard` — 고른 draftId 들을 버린다
- 자동저장을 켠 턴은 바뀌지 않는다.

## home (ARTEL-1075)

- 카드(`proposals`)를 걷고 `draft` 로 바꾼다. SSE `draft` + 로드 시 `GET`.
- 왼쪽 목록에 draft 를 겹쳐 그린다: 새로 생길 것(맨 위, 강조) · 바뀔 것(그 줄 강조) · 사라질 것(취소선).
  줄마다 승인 · 버리기, 머리에 모두 승인 · 모두 버리기.
- draft 줄을 누르면 가운데에서 draft 본문을 연다. 편집은 서버 draft 로 저장된다(시나리오에는 안 쓴다).

## 하지 않는 것

- 턴끝 전 건 판정(누락 · 미판정) 감사는 draft 에서 돌리지 않는다 — 지금 `autoApply=false` 도 돌리지 않는다.
- 과거 대화 기억(ARTEL-942).

## 검증

- orche 단위 테스트: draft 넣기 · 겹치기 · 음수 번호 되받기 · apply/discard.
- 로컬 E2E: 자동저장 끄고 한 턴 → 목록에 draft → 고쳐 승인 → 이어 말하기.
