# TC 내용 수정과 재검증 상태

TC의 내용 품질은 build별 게임 판정(`VERIFIED`, `FAILED` 등)과 별도로 관리한다. 사람이 검증 시도를 `TC_CONTENT_ISSUE`로 평가했을 때만 수정 요청을 만든다. Agent의 `INCONCLUSIVE`만으로는 변경하지 않는다.

## 상태 전환

| 계기 | 내용 상태 |
| --- | --- |
| 현재 TC 내용에 대한 사람의 TC 내용 문제 평가 | `NEEDS_REVISION` — 수정 필요 |
| scene·precondition·step·expected value·구조 조건/기대 결과·functions 실제 변경 | `NEEDS_REVALIDATION` — 재검증 필요 |
| 수정 이후 시작하고 현재 내용과 일치하는 새 시도의 PASSED 또는 FAILED | `READY` — 사용 가능 |

실패로 끝난 재검증도 TC 내용을 검증할 수 있었다는 뜻이므로 내용 상태는 사용 가능이다. 게임 실패 판정과 게임 실패 issue는 그대로 별도 관리한다. 판단 불가·중단, 수정 전에 시작한 시도, 옛 snapshot, 시작 시각을 모르는 legacy 시도는 재검증 대기를 해제하지 않는다.

내용이 없는 no-op 저장이나 검증 상태만 바꾼 저장은 전환하지 않는다. 과거 snapshot에 대한 TC 내용 문제 평가는 평가 이력에 남기고 현재 TC 상태를 되돌리지 않는다. 현재 source 평가를 내용 수정 전에 철회하면 사용 가능으로 바꾸지만, 이미 내용을 고친 뒤의 재검증 대기는 과거 평가 수정으로 해제하지 않는다. 여러 보고 중 현재 source를 바꿔도 기존 평가 수정 이력은 보존한다.

## API와 증거 연결

`GET /api/projects/{projectId}/qa-evaluation/case-content-reviews?testCaseId=...`는 프로젝트 멤버에게 내용 상태, 이유, 원본 log·attempt·try·run ID와 수정/완료 시각을 반환한다. TC 삭제 후에도 평가 이력은 남으며 삭제된 TC의 현행 상태는 목록에서 제외한다. 이유는 기존 평가 원칙대로 선택 입력이다.

화면은 원본 attempt와 scenario를 지정해 QA history의 해당 검증 영상을 연다. 영상이 30일 후 삭제돼도 평가·이유·출처 링크는 남는다. 자동 TC 수정 issue는 만들지 않는다.

## 빌드별 내용 수정

지도에서 생성한 TC를 수정할 때 `TestCaseUpdateRequest.gameBuildId`를 보내면 해당 프로젝트의 해당 build snapshot만 수정한다. 다른 build의 내용은 보존한다. 최신 identity는 기존 `syncIdentities` 규칙으로 갱신한다. 수동 TC는 기존 identity 편집 경로를 사용하고, scope 없는 기존 client의 동작도 유지한다.

현재 AgentCase 계약인 scene/precondition/test_step/expected/functions를 정규화해 snapshot을 비교한다. DB snapshot이 구조 필드 condition/expected_items도 포함한 경우 추가로 비교한다. Agent snapshot에는 이 구조 필드가 없으므로 텍스트·functions로 전달된 현재 계약의 재검증을 의미한다.

## 신뢰도와 기존 issue

TC 내용 문제는 검토 건수에 포함하되 오탐·미탐의 분자와 분모에는 포함하지 않는다. 실패 issue에서도 유효한 게임 실패로 세지 않는다. 기존 Boolean 평가 응답에는 TC 내용 문제를 실패로 변환하지 않고 humanDecision을 null로 둔다. 원본 agent 판정과 검토 완료 건수는 보존한다.

## 검증

관련 서버 회귀 테스트64개, Maven package, Flyway 버전 충돌 검사를 수행한다. 클라이언트의 상태/영상 링크/타입 파싱은 home 회귀 테스트와 별도 로컬 검증용 TC의 화면 캡처로 확인한다. 실제 게임 QA를 실행한 것과 합성 검증 데이터를 구분해 PR에 기록한다.
