# 2026-10-22 — 런 구성 변경 SSE와 공용 상태 갱신 추가

- Date: 2026-10-22
- GitHub Issue: None
- Status: 구현 및 검증 완료, draft PR 준비

## Goal
저장된 런 구성이 바뀌면 QA 화면을 떠나지 않아도 서버 집계가 Workspace에 반영된다.

## Non-goals
DB 개수 저장, SSE 데이터 payload 확장, 실행 엔진 변경은 하지 않는다.

## Context / Constraints
기존 프로젝트 SSE와 useProjectChange의 ready/reconnect 및 300ms 병합을 재사용한다. ARTEL-1215 아래 서버 ARTEL-1216, home ARTEL-1217로 추적한다. 기존 home PR #214를 갱신한다.

## Approach (Checklist)
- [x] **Step 0: Recon** 기존 SSE, 런 연결 저장 및 삭제 경로 확인.
- [x] **Step 1: Implementation** TEST_RUNS topic 추가. 런 생성·수정·삭제·구성 교체, 시나리오 가져오기·삭제·agent 저장/합치기 뒤 알린다. 트랜잭션 완료 이후 발행한다. Workspace가 목록/커버리지를 재조회하고 QA는 공용 목록을 사용한다. 최초 진입 재조회와 reconnect 보정, SSE 중단 시 polling을 유지한다. 중첩 응답은 최신 요청만 반영한다.
- [x] **Step 2: Tests** 이벤트 수신 후 GET 결과, FREE/ORDERED 구성 교체, 삭제, 잘못된 요청에서 이벤트 없음, rollback, 재연결/중복 병합 및 프런트 응답 경쟁 검증.
- [ ] **Step 3: Rollout / Rollback** 서버 먼저 배포. home은 미지원 서버에서 최초 조회로 동작한다. 변경 커밋으로 rollback 가능.

## Validation
- **Commands to run:** 서버 관련 Maven tests, home npm test/typecheck/build 및 변경 파일 lint, 브라우저 SSE fixture.
- **Expected output:** 저장 완료 후 이벤트를 받은 조회는 최신 개수를 읽는다. 화면 선택값은 background refresh 중 유지된다.

## Risks & Rollback
- **Risks:** 저장 경로 누락, commit 전 알림, 늦은 응답의 덮어쓰기.
- **Rollback steps:** 서버/클라이언트 변경 커밋을 되돌린다.

## Open Questions
- 없음.

## 계획 검토 반영
- ScenarioAbsorbService는 항목별 성공 트랜잭션 직후 각각 발행한다. 이후 항목 실패도 이전 commit 알림을 막지 않는다.
- 최초 조회·진입 조회·SSE·polling은 같은 런 조회 함수를 사용한다. 최신 요청만 공용 목록/커버리지를 갱신하며 이전 응답이 덮어쓰지 않는다.
- testScenarioUpdate와 testScenarioApprove도 커버리지 변경을 알린다.


## 검증 진행
- 제품 코드 Maven compile 통과. Plan review 및 pair review PASS.
- 테스트 전용 CHECK 제약으로 링크 INSERT 실패를 유도하고 DELETE rollback 및 이벤트 미발행을 확인한다.
- home 검증: 1,459개 테스트, build, 변경 파일 ESLint, SSE 브라우저 fixture 통과.


- 최종 서버 검증: ./mvnw -q -Dtest=ProjectEventStreamIntegrationTest test — 6개 통과, 실패/오류 0.
- 브라우저와 실제 서버를 함께 연결한 E2E는 수행하지 않았다. 서버 통합 테스트와 브라우저 fixture를 각각 검증했다.

