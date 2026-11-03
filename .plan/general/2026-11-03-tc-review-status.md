# 2026-11-03 — CLI·MCP TC 검토 상태 추가

- Date: 2026-11-03
- Jira: ARTEL-1316 (server ARTEL-1317, CLI ARTEL-1318, MCP ARTEL-1319)
- Status: Implemented; draft PR pending

## Goal
TC 검토 상태 reviewStatus(DRAFT/READY)를 status 및 verificationStatus와 별도로 저장한다. CLI·MCP에서 신규 생성 기본은 draft이며 명시적으로 해제하면 READY다.

## Non-goals
UI 변경과 배포, 기존 TC 검토 상태 초기화는 포함하지 않는다.

## Context / Constraints
기존 TC와 화면·지도 생성은 READY로 유지한다. 서버 create 요청 draft는 생략하면 false(기존 호환), CLI·MCP는 true를 명시한다. MCP batch draft 기본 true는 신규 TC에만 적용하고 수정 시 기존 검토 상태를 보존한다. draft false는 검증 통과를 뜻하지 않는다.

## Approach (Checklist)
- [x] **Step 0: Recon** 최신 서버 #393, MCP #10 위에 쌓고 CLI main을 사용한다. 기존 작업 checkout을 건드리지 않는다.
- [x] **Step 1: Implementation** test_case.review_status NOT NULL DEFAULT READY 및 CHECK, create draft boolean, update reviewStatus 검증. 응답·목록·버전·계약 목록에서 상태 반환. CLI create --draft/--no-draft(기본 true), update JSON reviewStatus 승인 지원. MCP save_test_cases draft=true와 preview 값 보존, 상태 표시 및 검토 상태 변경 도구.
- [x] **Step 2: Tests** 기본·명시 false 생성, 기존 데이터 호환, 업데이트 승인, boolean 유효성, batch·preview 보존. 서버에서 draft를 저작 후보·검색·커버리지에서 제외하고 수동 참조 및 QA 실행에 포함된 draft를 거절한다. 상태는 TC identity 단위다.
- [ ] **Step 3: Rollout / Rollback** 서버 migration/API 먼저, CLI·MCP 다음. draft PR만 생성. API 미배포 시 클라이언트가 조용히 READY TC를 생성하지 않도록 생성 전 GET /api/projects/{projectId}/test-cases/review-status-options 로 지원 확인({statuses:[DRAFT,READY]}). 미지원이면 POST 전에 실패. CLI JSON draft는 거절하며 명령 flag가 배치 전체를 정한다. 모든 수정·재생성은 명시적 검토 상태 변경 외에는 기존 검토 상태를 유지.

## Validation
- **Commands to run:** CLI npm test/typecheck/lint/build; MCP uv run pytest/ruff; 서버 mvnw focused integration + OpenAPI snapshot.
- **Expected output:** 초안이 저장·조회되지만 승인 전 저작·QA 사용 불가, READY+verificationStatus DRAFT는 정상 사용 가능.

## Risks & Rollback
- **Risks:** 사용 후보 조회·수동 참조 누락, MCP preview 옵션 유실, 구서버가 새 필드 무시. 기존 기록 조회는 유지한다.
- **Rollback steps:** 클라이언트부터 revert, 서버 revert 후 review_status 컬럼은 무시하거나 삭제한다.

## Open Questions
없음. 검토 상태는 생성 시와 명시적 변경에서만 바뀐다.

## Plan Review
FAST NONPASS: 생성 후 검출을 생성 전 지원 조회로 변경하고 flag 우선순위와 각 경로 기본값 테스트를 명시했다. MEDIUM PASS: 서버가 후보 제외를 소유한다. HEAVY PASS 및 독립적인 구현 pair review PASS.

## Scope Update
MCP 추천 TC 저장도 신규 생성 경로이므로 draft 기본 true와 false 옵션을 적용한다. 서버 추천 저장 API는 기존 UI 호환을 위해 draft 생략 시 false다. 추천이 가리키는 시나리오 참조는 유지하고, 승인 전 QA 실행은 거절한다. Flyway migration은 열린 PR #373의 V155와 충돌하지 않도록 V156을 사용한다.


## Server Validation Results
- WSL Java 21의 native checkout에서 관련 10개 클래스의 고유 테스트 65개가 통과했다. 최초 실행 54개 통과 후 fixture 및 JUnit 반환형을 수정한 두 클래스 12개를 재실행했다(중복 1개 제외).
- 최초 명령: `./mvnw -o -q -Dkotlin.compiler.daemon=false test -Dtest=TestCaseReviewStatusIntegrationTest,QaDraftReviewIntegrationTest,AllTestCasesIntegrationTest,TestCaseVersionLibraryIntegrationTest,CaseContractServiceIntegrationTest,OpenApiSnapshotTest,TestCaseVectorSearchIntegrationTest,McpAccessTokenIntegrationTest,CaseRecommendationIntegrationTest,QaRunTakeoverIntegrationTest`.
- 수정 후 명령: `./mvnw -o -q -Dkotlin.compiler.daemon=false kotlin:test-compile surefire:test -Dtest=TestCaseReviewStatusIntegrationTest,TestCaseVectorSearchIntegrationTest` — 12개 통과.
- 벡터 검색 테스트는 TC 번호 counter의 project FK를 만족하도록 실제 프로젝트를 생성한다. 새 coroutine 테스트 네 개는 반환형을 명시적으로 Unit으로 두어 JUnit 발견 여부를 확인했다.
- 런타임 OpenAPI snapshot과 V156 migration 적용을 확인했다. base migration 검사와 git diff --check도 통과했다.
- 실제 게임 QA, 운영 배포, PR merge는 수행하지 않았다. 서버를 먼저 반영한 뒤 CLI·MCP를 반영한다.