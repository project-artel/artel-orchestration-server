# 2026-11-03 — TC 명세 등급 status 제거

- Date: 2026-11-03
- GitHub Issue: None
- Jira: ARTEL-1321 (상위 ARTEL-1320)
- Status: Implemented

## Goal
TC 명세 등급을 DB·서버·생성·API·화면에서 제거.

## Non-goals
QA verificationStatus, content map status 및 원본 scenario quality 변경.

## Context / Constraints
기존 미커밋 변경을 보존하고 최신 develop의 별도 worktree에서 이번 변경만 적용했다.

## Approach (Checklist)
- [x] **Step 0: Recon**: 전체 TC 입출력과 export 참조 확인.
- [x] **Step 1: Implementation**: TC 엔티티·DTO·생성기·조회 projection·명세 적재·XLSX 출력에서 status 제거. V163으로 test_case와 test_case_version의 status 컬럼 삭제. runtime OpenAPI snapshot 갱신.
- [x] **Step 2: Tests**: Maven 서버·테스트 컴파일 통과. TC·XLSX·시나리오·OpenAPI 관련 256개 중 244개 통과. TestCaseEmbeddingBackfillIntegrationTest 5개와 TestCaseVectorSearchIntegrationTest 7개는 fixture가 project를 만들지 않고 TC를 저장해 test_case_number_counter_project_id_fkey 오류 발생. PostgreSQL 임시 테이블 migration 검증 통과: 두 컬럼 삭제 및 VERIFIED/FAILED 판정 보존.
- [x] **Step 3: Rollout / Rollback**: 서버·DB 계약 변경 후 소비자를 적용한다. 운영 배포는 수행하지 않는다.

## Validation
Maven 서버·테스트 컴파일 통과. TC·XLSX·시나리오·OpenAPI 관련 256개 중 244개 통과. TestCaseEmbeddingBackfillIntegrationTest 5개와 TestCaseVectorSearchIntegrationTest 7개는 fixture가 project를 만들지 않고 TC를 저장해 test_case_number_counter_project_id_fkey 오류 발생. PostgreSQL 임시 테이블 migration 검증 통과: 두 컬럼 삭제 및 VERIFIED/FAILED 판정 보존.

## Risks & Rollback
서버 배포와 Flyway 적용을 함께 진행해야 한다. 과거 등급 값은 삭제되므로 rollback은 nullable status 컬럼 복구 migration과 이전 서버 배포가 필요하다. OpenAPI snapshot은 runtime 생성 결과이며 develop에 이미 반영된 demonstration 제거 등의 기존 drift도 포함한다.

## Open Questions
없음. 알려진 검증 제한은 위에 기록했다.

CI migration 검사에서 다른 미병합 브랜치의 V156과 충돌해, 전체 remote branch의 최대 예약 번호 V162 다음인 V163로 이동했다.
추가 remote fetch에서 V162도 예약된 것을 확인했으므로 최종 번호는 V163이다.
