# 2026-11-03 — QA 로그의 TC 표시 번호 통일

- Date: 2026-11-03
- Jira: ARTEL-1339; parent ARTEL-1337
- GitHub Issue: None
- Status: Implemented; draft PR pending

## Goal

사용자에게 보이는 QA 판정 로그의 TC 식별 번호를 기존 프로젝트 영구 번호 TC-00n으로 통일한다. 내부 ID 20을 TC-020으로 포맷하지 않고 해당 프로젝트의 번호 14를 찾아 TC-014로 표시한다.

## Non-goals

TC 재번호 부여, 과거 DB 로그 일괄 수정, API 내부 ID 교체, 모델·사용자 자유 문장 정규식 치환, 실행 중 QA #11 재시작.

## Context / Constraints

Home은 이미 useTestCaseLabel과 프로젝트 identifier provider를 사용하지만 판정 로그 본문은 log.message를 그대로 표시한다. 서버는 TestCaseVerdictService에서 내부 ID로 message를 생성한다. test_case_identifier는 삭제 후에도 번호를 보존한다. 두 PR은 독립 적용할 수 있다.

## Approach (Checklist)

- [x] **Step 0: Recon** — 로그 생성, Flow·Raw·접힌 이벤트 경로와 기존 식별 번호 API 확인.
- [x] **Step 1: Implementation** — 서버의 프로젝트·TC ID로 번호를 조회하는 내부 helper와 formatter를 기존 TestCaseIdentifiers에 둔다. 새 CASE_VERDICT message는 이 label로 생성하고 payload.case_id는 유지한다. 선택적 case_number를 payload에 실어 새 로그는 번호 조회 전에도 표시할 수 있게 한다. 없으면 TC-…로 표시한다.
- [x] **Step 1: Implementation** — Home의 공통 CASE_VERDICT 본문 renderer는 프로젝트 resolver를 우선하고 새 payload.case_number를 fallback으로 사용한다. 과거 로그도 payload.case_id로 같은 label을 표시한다. Flow·Raw 행·접힌 이벤트의 동일 renderer를 사용하며 구조화 payload 원문은 그대로 둔다. 다른 로그 종류·수량·step 순서는 바꾸지 않는다.
- [x] **Step 2: Tests** — 내부 ID/표시 번호 차이, 1000 이상 번호, 미로딩·없는 번호, 기존 로그와 새 payload, 프로젝트 구분·삭제 번호, Flow·Raw·접힌 이벤트와 링크 대상 유지 검증.
- [ ] **Step 3: Rollout / Rollback** — 별도 draft PR 두 개를 만들고 Home 변경만 현재 로컬 웹에 적용해 QA #11 화면을 확인한다. 서버 재시작 없이 기존 run을 유지한다. 각 commit revert로 되돌린다.

## Validation

- **Commands to run:** Home의 node test hooks 기반 QA timeline·TC identifier 테스트, npm run lint/typecheck/build. 서버의 TestCaseIdentifiersIntegrationTest 및 CaseVerdictFromQaIntegrationTest.
- **Expected output:** 같은 케이스가 모든 제품용 판정 본문·배지에서 TC-014로 표시되고 case_id=20 및 링크 대상은 유지된다.

## Risks & Rollback

- **Risks:** 과거 로그에 case_id와 상태 payload가 없으면 프로젝트 번호를 신뢰성 있게 복원할 수 없다. 정상 CASE_VERDICT 계약만 변환하며 원문 JSON은 디버깅에 보존한다. 삭제된 TC도 tombstone 번호를 조회한다.
- **Rollback steps:** repo별 commit revert. DB migration 없음.

## Open Questions

- 없음. 표시 번호가 준비되지 않으면 내부 ID로 임시 번호를 만들지 않는다.

## Plan Review

- FAST REVIEW: PASS — Flow의 일반 로그와 접힌 이벤트까지 같은 renderer를 사용한다. 과거 payload의 ID·상태가 없으면 임의 추정하지 않는다.
- MEDIUM REVIEW: PASS — payload.case_number fallback은 양의 정수 문자열만 허용하고 프로젝트 resolver가 있으면 우선한다. 표시 번호 조회 오류는 TC-…로 처리해 판정 저장·로그 발행을 유지하되 coroutine cancellation은 전파한다.
- HEAVY REVIEW: PASS — 프로젝트 영구 번호와 내부 ID를 구분하고 구조화 판정 로그만 변환한다.
- Pair review critic: VERDICT PASS — 구현 diff와 테스트 검토 완료.

## Validation Results

- Home: 확장 회귀 테스트 75개 통과. 변경 파일 ESLint와 production build 통과.
- Home 전체 lint: 변경하지 않은 파일의 기존 오류 5개와 경고 3개로 실패. authoringFrames.ts, useCaseListNav.ts, RunListPanel.ts, RunNameCrumb.ts, useStepEditor.ts에 기존 오류가 있다.
- Server: formatter·identifier·verdict 통합 테스트 41개 통과.
- 로컬 QA #11: Home HMR 적용 후 TC-014 → VERIFIED 표시 확인. 실행과 입력 중 답변 유지. 서버·agent·게임 재시작 없음.
