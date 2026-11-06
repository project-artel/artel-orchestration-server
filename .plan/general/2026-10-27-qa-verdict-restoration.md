# 2026-10-27 — QA 재진입 시 TC 판정 복원 수정

- Date: 2026-10-27
- GitHub Issue: None
- Status: Implemented and tested locally

## Goal
QA 화면 재진입 시 과거 판정을 로그 페이지와 무관하게 복원한다.

## Non-goals
DB 변경, 사용자 문구 변경, 기존 작업 변경은 포함하지 않는다.

## Context / Constraints
최근 로그 50개만 조회하므로 오래된 판정이 빠진다. 기존 qa_log를 사용하고 실행 접근 권한을 검사한다.

## Approach (Checklist)
- [x] **Step 0: Recon**: 저장 및 재진입 경로 확인.
- [x] **Step 1: Implementation**: 판정 전용 API와 별도 프런트 상태 추가. 로그 cursor 확보 후 판정을 조회한다.
- [x] **Step 2: Tests**: 과거 판정 복원, 실시간 중복 및 최신 판정, 조회 순서, 접근 권한과 로그 필터 검증.
- [x] **Step 3: Rollout / Rollback**: 배포 순서 정리. 서버를 먼저 배포한 뒤 프런트를 배포한다. 배포는 수행하지 않았다.

## Validation
- **Commands to run:** 프런트 회귀 테스트, typecheck, 서버 관련 테스트, diff check.
- **Expected output:** 최근 50개에 없는 판정도 재진입 후 동일하게 표시된다.
- **Results:** 프런트 관련 테스트 37개, 변경 파일 ESLint, 전체 typecheck, diff check 통과. 서버 QaTryDetailIntegrationTest는 WSL 임시 복사본과 테스트용 PostgreSQL/Redis에서 통과. 독립 검토 PASS.
- **Limits:** API 응답을 사용하는 재진입 로딩 함수의 회귀 테스트이며 실제 브라우저 재마운트 수동 검증은 수행하지 않았다. 기존 Windows target 쓰기 권한 문제로 서버 테스트는 소스 복사본에서 실행했다.

## Risks & Rollback
- **Risks:** 조회 시점 차이는 SSE replay로 보완해야 한다. 판정 조회 실패를 빈 판정으로 취급하지 않는다.
- **Rollback steps:** 프런트 변경을 먼저 되돌린 뒤 추가 API를 제거한다.

## Open Questions
- 없음.

