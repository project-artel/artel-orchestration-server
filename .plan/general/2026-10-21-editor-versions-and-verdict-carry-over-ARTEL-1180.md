# 2026-10-21 — 에디터 버전도 TC 버전으로 만들고 앞 버전 검증 결과를 이어받는다

- Date: 2026-10-21
- Jira: ARTEL-1180 (home: ARTEL-1181)
- Status: Draft

## Goal

게임 개발자는 QA 를 빌드보다 Unity 에디터에서 더 많이 돌린다. 에디터가 보고하는 Player Settings Version
(`Application.version`) 마다 TC 버전을 만들 수 있게 하고, 새 버전(또는 같은 버전의 덮어쓰기 생성)에서 앞 버전의
판정을 이어받을지 사용자가 고르게 한다.

- 에디터 행을 버전마다 하나: `(project_id, version) WHERE is_editor = TRUE`. 같은 버전 재등록은 지금처럼 그 행의
  scan 을 덮어쓴다. 에디터가 같은 버전 빌드 행을 덮어쓰지 않는다는 ARTEL-1014 의 의도는 그대로다.
- 에디터 행도 버전이다: TC 생성, 앞 버전, 판정 이력, TR 버전, 가져오기 트리, TC 폴더의 최신 버전에 넣는다.
- 판정 이어받기: TC 생성 요청에 `verdicts` = `NONE` | `SAME_CONTENT` | `SELECTED`(+ `testCaseIds`).
  - `SAME_CONTENT`: 이번 버전 몫이 `REGRESSION` 이고 앞 버전 몫이 `VERIFIED` · `FAILED` 이며 내용이 같으면 그
    판정을 가져온다. 지도 TC 는 같은 정체(`case_key`)라서 내용이 같고, 사용자 생성 TC 는 장면 · 스텝 · 사전조건 ·
    기대값이 같을 때만이다.
  - `SELECTED`: 고른 케이스만 위처럼 가져오고, 고르지 않은 이번 버전의 판정(덮어쓰기 생성에서 남아 있던 것)은
    `REGRESSION` 으로 돌린다.
  - `NONE`: 이번 버전에 남아 있던 판정까지 `REGRESSION` 으로 돌린다(전부 재검증).
  - 주지 않으면 지금과 같다(새 몫은 `REGRESSION`, 덮어쓰기에서 남는 몫은 판정 유지).
- 생성 뒤 선택 이어받기: `POST /api/projects/{projectId}/game-builds/{gameBuildId}/test-cases/verdict-carry-over`
  `{testCaseIds}`. 이번 버전에서 직접 판정이 난 몫은 덮지 않는다.
- 출처: `test_case_version.verdict_carried_from_build_id`. 이어받으면 앞 버전 build, 직접 판정이나 `REGRESSION`
  으로 돌리면 null.

## Non-goals

- SDK 변경(이미 `isEditor` 와 version 을 보낸다).
- Unity 값을 건드리지 않는 "지금 상태를 새 버전으로 기록".
- 성능 추세(`sdkperf`)는 지금처럼 에디터 런을 뺀다.
- home 화면(ARTEL-1181).

## Context / Constraints

- develop 최신 마이그레이션은 V132 → V133.
- `findAllForMember` 의 첫 행을 저작(`contentMapIdOf`)이 "지금 build" 로 쓴다. 에디터 행이 버전이 되므로 정렬에서
  `is_editor` 를 빼 최신 버전이 첫 행이 되게 한다.
- `TestCaseReader.judgedBuildId`: 에디터 build 에 버전 몫이 있으면 그 build, 없으면 지금처럼 같은 version 의 빌드.

## Approach (Checklist)

- [ ] V133: editor partial unique index 교체, `verdict_carried_from_build_id` 추가.
- [ ] 등록: `findEditorByVersion`.
- [ ] 버전 순서에서 editor 제외 제거(GameBuildRepository · TestCaseVersionRepository · TestRun · Library · QaLaunchRead · Assistant).
- [ ] 생성: editor 거부 제거, 후보에 editor 표시, `verdicts` 요청 · 응답 수.
- [ ] `VerdictCarryOverService` — 생성 후처리와 선택 API 가 같은 규칙.
- [ ] 직접 판정이 출처를 지운다(`TestCaseVerdictService`).
- [ ] 테스트: 기존 editor 제외 테스트를 새 규칙으로 바꾸고, 이어받기 세 모드 · 선택 API · 등록을 더한다. openapi 스냅샷.

## Validation

- `./mvnw -o test -Dtest='SdkRegistrationIntegrationTest,ContentMapTestCaseGenerationIntegrationTest,ScenarioReuseAcrossBuildsIntegrationTest,TestCaseVersionLibraryIntegrationTest,CaseVerdictFromQaIntegrationTest,GameBuildSummaryIntegrationTest,VerdictCarryOverIntegrationTest,OpenApiSnapshotTest'`
- 로컬: word-venture 에디터 1.4.0 으로 붙여 TC 생성 → QA.

## Risks & Rollback

- 기존 단일 editor 행은 그 행의 마지막 version 으로 남는다. 되돌리려면 editor 행이 프로젝트당 하나인지 확인한 뒤
  인덱스를 다시 건다.
- 이어받은 판정은 실제로 이 버전에서 본 것이 아니다. 출처 칸으로 화면이 구분한다.
