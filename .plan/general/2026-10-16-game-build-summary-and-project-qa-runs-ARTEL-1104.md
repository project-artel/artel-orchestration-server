# 2026-10-16 — 게임 빌드 요약 API 와 프로젝트 QA run 목록 API 추가

- Date: 2026-10-16
- Jira: ARTEL-1104
- Status: Draft (ARTEL-1053 / PR #314 위에 쌓은 branch)

## Goal

artel-home 의 빌드 화면(ARTEL-1105)이 한 번에 그릴 수 있게 읽기 전용 endpoint 둘을 더한다.

1. `GET /api/projects/{projectId}/game-builds/summary` — 빌드마다 인스턴스 수, 연결 수, cheat · behavior 수, QA run 수, 가장 최근 run.
2. `GET /api/projects/{projectId}/qa-runs?gameBuildId=&size=` — 프로젝트의 run 을 새 것부터. `QaRunResponse` 에 `gameBuildId` 추가.

## Non-goals

- migration 없음. `game_build`, `qa_run.game_build_id`, `qa_try.steps_*` 는 이미 있다.
- `/game-builds/{buildId}/...` 경로 변경 없음.

## Approach (Checklist)
- [x] Recon: `GameBuildController`, `GameBuildService`, `QaTryService.toRunResponse`, `QaRunRepository`.
- [x] `GameBuildSummaryRepository` — `DatabaseClient` 한 문장. 빌드 행에 `LEFT JOIN LATERAL` 셋(인스턴스 집계, 최근 run, 그 run 의 try 합)을 붙이고 run 수는 scalar subquery. 소켓 연결은 메모리(`SessionManager`)에만 있어 인스턴스 id 를 `STRING_AGG` 로 받아 서비스가 센다.
- [x] 접근 확인은 기존 목록과 같게 `ProjectRepository.findAccessibleById` (비참여자 · 없는 프로젝트는 404).
- [x] `QaRunRepository.findByProject` (`test_run.project_id` 조인, `gameBuildId` 선택), try 는 `findByQaRunIdInOrderById` 한 문장.
- [x] `size` 는 1..100 밖이면 400, `gameBuildId` 가 숫자가 아니면 400.
- [x] Tests: `GameBuildSummaryIntegrationTest`, `OpenApiSnapshotTest` 로 `docs/api/openapi.json` 갱신.

## Validation
- `./mvnw -o test -Dkotlin.compiler.daemon=false -DfailIfNoSpecifiedTests=false -Dtest=GameBuildSummaryIntegrationTest,OpenApiSnapshotTest,...`

## Risks & Rollback
- Risks: `/game-builds/summary` 가 `/{buildId}` 와 겹칠 수 있다 — literal path 가 이기고 테스트로 확인한다. `QaTryService` 생성자에 `ProjectRepository` 가 늘었다(직접 생성하는 테스트 없음).
- Rollback: `git revert`. 스키마 변경이 없다.

## Review (self)
plan-review 와 pair-review 를 subagent 없이 직접 했다. 짚은 것: 삭제된 인스턴스 제외, editor 행 정렬 유지, 단독 실행 qa_run(`game_build_id` null) 이 목록에는 나오고 어느 빌드 집계에도 안 잡힘, `jsonb_array_length` 가 배열이 아닌 값에서 오류를 내므로 `jsonb_typeof` 로 거름. 남은 지적 없음.
