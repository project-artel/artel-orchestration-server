# 2026-10-13 — game_build 에 editor 전용 행과 is_editor 컬럼 추가

- Date: 2026-10-13
- Jira: ARTEL-1014
- Status: Reviewed (fast NONPASS → 반영, medium PASS, heavy PASS)

## Goal

SDK 는 Unity editor 와 development build 에서만 돈다. 둘 다 Player Settings 의 같은 `Application.version`
을 보고하므로, 지금은 editor 실행이 같은 버전의 development build 행(`game_build`)에 내려앉아 그 행의
`scene_scan` 과 content map 을 덮어쓴다. editor 는 계속 바뀌는 작업 상태이고 게임 버전이 아니다.

- 프로젝트마다 editor 전용 `game_build` 행을 하나 둔다(`is_editor = TRUE`).
- editor 등록은 그 행 하나를 찾거나 만들고, 등록마다 `version` 과 `scene_scan` 을 보고된 값으로 바꾼다.
- 빌드를 버전 순서로 읽는 곳은 editor 행을 뺀다.

## Non-goals

- 기존 행을 editor 로 다시 분류하지 않는다. 기존 행은 모두 `is_editor = FALSE` 로 남는다.
- SDK · home 변경(다른 Subtask 에서 병렬로 진행).
- authoring 이 어느 content map 을 읽을지(`contentMapIdOf`, `ORDER BY cm.id DESC` 조회들)의 규칙 재설계.

## Context / Constraints

- base 는 PR #301(ARTEL-994) 브랜치다. 그 PR 이 V103 을 더했다. V104 는 먼저 push 된
  ARTEL-1018 · ARTEL-1021 브랜치(`V104__split_test_case_into_identity_and_versions.sql`)가 가져갔으므로 V105 를 쓴다.
- 다른 레포가 기대는 계약(부모 agent 가 정함):
  1. `game_build.is_editor BOOLEAN NOT NULL DEFAULT FALSE`, `uk_game_build_project_version` 삭제,
     `(project_id, version) WHERE is_editor = FALSE` 와 `(project_id) WHERE is_editor = TRUE` partial unique index.
  2. `SdkRegistrationRequest.isEditor: Boolean? = null`. 없으면 false.
  3. `SdkRegistrationResponse.isEditor`, `GameBuildResponse.isEditor`.
  4. `GET /api/projects/{projectId}/game-builds` 는 editor 행을 마지막에 둔다.
- `game_instance.last_game_build_id` 는 editor 인스턴스에서 editor 행을 가리킨다. QA run, scene context,
  evidence · content map upload 와 scan 은 build id 로만 동작하므로 바꾸지 않는다.
- `docs/api/openapi.json` 은 `OpenApiSnapshotTest` 가 생성한다. `docs/api-documentation.md` 는 이 payload
  를 서술하지 않으므로 바꾸지 않는다.

## Approach (Checklist)

- [x] **Step 0: Recon** — `game_build` 를 읽는 모든 곳을 찾았다(아래 표).
- [ ] **Step 1: Migration** `V105__separate_editor_game_build.sql`
  ```sql
  ALTER TABLE game_build ADD COLUMN IF NOT EXISTS is_editor BOOLEAN NOT NULL DEFAULT FALSE;
  ALTER TABLE game_build DROP CONSTRAINT IF EXISTS uk_game_build_project_version;
  CREATE UNIQUE INDEX IF NOT EXISTS uk_game_build_project_version
      ON game_build (project_id, version) WHERE is_editor = FALSE;
  CREATE UNIQUE INDEX IF NOT EXISTS uk_game_build_project_editor
      ON game_build (project_id) WHERE is_editor = TRUE;
  ```
  제약 이름과 같은 이름의 인덱스를 다시 쓰는 것은 제약을 지운 뒤라 충돌하지 않는다.
- [ ] **Step 2: Entity · DTO**
  - `GameBuildEntity.isEditor: Boolean = false` (`@Column("is_editor")`) 와 KDoc.
  - `SdkRegistrationRequest.isEditor: Boolean? = null`, `SdkRegistrationResponse.isEditor: Boolean`,
    `GameBuildResponse.isEditor: Boolean` 와 KDoc.
- [ ] **Step 3: Repository** (`GameBuildRepository`)
  - `findByProjectIdAndVersion` → `@Query` `findNonEditorByVersion(projectId, version)` (`is_editor = FALSE`).
  - 새 `@Query` `findEditor(projectId)` (`is_editor = TRUE`).
  - `findFirstByProjectIdOrderByCreatedAtDescIdDesc` 와 `findByProjectIdOrderByCreatedAtDescIdDesc` 를
    `@Query` `findNonEditorNewestFirst(projectId)` 하나로 합친다. `TestRunService.create` 는 `.firstOrNull()` 을 쓴다.
  - `findAllForMember` 의 `ORDER BY gb.is_editor, gb.created_at DESC, gb.id DESC`. KDoc 에 `contentMapIdOf` 두 곳이
    이 순서의 첫 행을 쓴다고 적는다.
- [ ] **Step 4: 등록** (`SdkRegistrationService.findOrCreateBuild`)
  - 함수 하나에 `isEditor` 인자를 더한다. 조회만 갈린다: editor 면 `findEditor`, 아니면 `findNonEditorByVersion`.
  - 없으면 만든다. 있으면 `version` 이 같고 스캔이 없을 때 그대로 쓰고, 아니면
    `existing.copy(version = version, sceneScan = sceneScan ?: existing.sceneScan, updatedAt = now)` 로 저장한다.
    non-editor 는 조회가 같은 version 이라 지금과 같다. editor 가 version 을 바꾸면서 스캔을 보내지 않으면 앞
    스캔을 남긴다 — 스캔을 생략한 등록이 스캔을 지우지 않는 지금 규칙과 같다.
  - `DataIntegrityViolationException` 재시도 루프는 그대로 둔다. KDoc 의 "테스트가 H2 에서 돈다" 는 틀렸으므로(테스트는 PostgreSQL) 고친다.
- [ ] **Step 5: 버전 순서로 읽는 곳** — 아래 표의 "바꿈" 행.
- [ ] **Step 6: Tests**
  - `SdkRegistrationIntegrationTest` 에 (a) editor 와 development build 가 같은 버전 문자열이어도 두 행,
    (b) editor 재등록은 한 행을 유지하고 `version` · `scene_scan` 을 바꾼다, (c) `isEditor` 없는 요청은
    지금처럼 version 으로 묶이고 `isEditor=false` 로 응답한다, (d) 목록에서 editor 행이 마지막.
  - `ScenarioReuseAcrossBuildsIntegrationTest` 에 (e)
    - 나중에 만든 editor 행이 있어도 TR 은 최신 non-editor build 를 갖는다. editor 행만 있으면 TR 의 build 는 null.
    - build A(VERIFIED) → editor(content map 생성) → build B 순서에서 editor 의 TC 는 REGRESSION 이 붙지 않고,
      build B 의 직전은 editor 가 아니라 A 다. verdict history 에 editor 행이 없다. library 에 editor 가 없다.
  - `OpenApiSnapshotTest` 로 `docs/api/openapi.json` 갱신.
- [ ] **Step 7: Rollout / Rollback** — 컬럼 추가는 기본값이 있어 구버전 서버와도 맞는다. 되돌릴 때는 editor 행
  을 지우거나 version 이 겹치지 않음을 확인한 뒤 제약을 다시 걸어야 한다(아래 Risks).

### `game_build` 를 읽는 곳 — 결정

| 위치 | 결정 | 이유 |
|---|---|---|
| `GameBuildRepository.findByProjectIdAndVersion` (등록) | 바꿈: `is_editor = FALSE` | editor 행의 version 이 development build 와 같아도 매칭되면 안 된다 |
| `GameBuildRepository.findAllForMember` (`GameBuildService.list`) | 바꿈: editor 마지막 | 계약 5 |
| `TestRunService.create` → `findFirstByProjectIdOrderByCreatedAtDescIdDesc` | 바꿈: non-editor 최신 | TR 의 버전은 게임 버전이다. editor 만 있으면 null("버전 없음") |
| `ScenarioLibraryService.library` → `findByProjectIdOrderByCreatedAtDescIdDesc` | 바꿈: non-editor 만 | 트리의 버전 축. TR 은 editor 행을 받지 않으므로 빠져도 잃는 TR 이 없다 |
| `TestCaseRepository.markRegressionFromPreviousBuild` | 바꿈: scalar subquery 안에 `pb.is_editor = FALSE` 와 `cur.is_editor = FALSE`. editor `cur` 이면 subquery 가 NULL 이라 `IN` 이 참이 되지 않는다 | editor 판정은 버전 이력이 아니다. editor 가 `cur` 이면 버전 순서에 자리가 없으므로 아무 행도 REGRESSION 으로 바꾸지 않는다 |
| `TestCaseRepository.findVerdictHistory` | 바꿈: `gb.is_editor = FALSE` | build 순서의 버전 이력 |
| `ScenarioPathService.contentMapIdOf`, `ScenarioCaseFactService.contentMapIdOf` (`findAllForMember().firstOrNull()`) | 코드는 그대로, 동작은 바뀜 | 목록 순서를 따르므로 최신 non-editor 를 고르고, editor 만 있는 프로젝트에서는 editor 를 고른다. 지금은 가장 늦게 만든 행이 이긴다. 시나리오의 `case_id` 가 TR 의 build(non-editor) 행을 가리키므로 같은 쪽을 고르는 편이 맞다. 별도 테스트 없이 (d) 의 목록 순서로 덮는다 |
| `TestCaseRepository.findTwinsInBuild` | 그대로 | 대상 build 를 인자로 받는다. TR 의 build 는 non-editor 다 |
| `TestCaseRepository.findValuesChangedByCases`, `findEntrySceneName`, `findStartingValues`, `findValueRaisers` 류 | 그대로 | build 를 프로젝트 범위를 거는 데만 쓴다(`ORDER BY cm.id DESC` 또는 순서 없음). content map 은 대부분 editor 에서 만든다(`capture` 값 `editor`, `editor-play`). editor content map 을 빼면 editor 만 쓰는 프로젝트의 authoring 이 지도를 잃는다 |
| `GameInstanceRepository.findByLastGameBuildIdForMember` (원격 scan 대상) | 그대로 | build id 하나로 인스턴스를 찾는다. editor 행에도 editor 인스턴스가 걸려야 scan 이 간다(계약 7) |
| `ContentMapTestCaseService.buildNameOf` (TC 폴더 이름) | 그대로 | 폴더 이름은 만들 때의 `label ?: version` 으로 굳는다. editor 행의 version 이 바뀌어도 폴더 이름은 처음 값으로 남는다. 이름을 바꾸는 것은 범위 밖이고 PR 에 적는다 |
| `ContentMapRepository` 의 project 조회(l.400) | 그대로 | build → project 대조 |
| `findAccessibleById`, `findAccessibleByIdForMember`, `findById` (content map, scan, evidence, macro, scene context, test case 생성) | 그대로 | build id 로 한 행을 다룬다. editor 행에서도 같아야 한다(계약 7) |
| `QaTryService.createRun` (`qa_run.game_build_id = last_game_build_id`), `TestCaseVerdictService` | 그대로 | 판정이 어느 행에서 났는지의 기록. editor 실행이면 editor 행이 맞다 |
| `SdkPerformanceRepository` build trend / summary upsert | 그대로 | build 하나에 대한 조회이고 이미 `s.is_editor IS DISTINCT FROM TRUE` 로 editor run 을 뺀다. editor 행의 trend 는 빈 목록이 된다 |

## Validation

- `./scripts/check-flyway-migrations.sh feat/orchestration-TS-가져오기와-버전별-TC-검증-상태-갱신-ARTEL-994` → 0
- `./scripts/verify-flyway-upgrade.sh feat/...-ARTEL-994` → 0
- `MAVEN_OPTS="-Xmx2g -XX:MaxMetaspaceSize=768m" ./mvnw -o test -Dkotlin.compiler.daemon=false -Dtest='SdkRegistrationIntegrationTest,GameBuildIntegrationTest,ScenarioReuseAcrossBuildsIntegrationTest,CaseVerdictFromQaIntegrationTest,ContentMapTestCaseGenerationIntegrationTest,OpenApiSnapshotTest' -DfailIfNoSpecifiedTests=false`
- 전체 suite 는 원래 100개 안팎이 실패하므로 판단 근거로 쓰지 않는다.

## Risks & Rollback

- **Risks:**
  - 구버전 SDK 는 `isEditor` 를 보내지 않으므로 editor 실행이 계속 development build 행에 내려앉는다. SDK
    Subtask 가 배포돼야 해결된다.
  - 이미 섞인 기존 행은 그대로다(계약 1).
  - 등록 재시도는 트랜잭션 안에서 일어나므로 Postgres 에서는 충돌 후 같은 트랜잭션이 abort 된다. 지금 코드와
    같은 성질이고 계약이 이 패턴을 유지하라고 정했다. editor partial index 가 같은 경합 경로를 하나 더할 뿐이다.
  - editor 여부 신호가 둘이다. 행은 등록의 `isEditor` 가 정하고, 성능 요약(`sdk_performance_run_summary.is_editor`)
    은 `sdk_device_context.is_editor` 가 정한다. 둘은 독립이고, 어긋나도 서로를 읽지 않으므로 다른 쪽이 깨지지 않는다.
- **Rollback steps:** `git revert`. DB 는 V105 를 되돌리는 새 마이그레이션이 필요하다: editor 행을 지우거나
  version 이 겹치지 않음을 확인한 뒤 두 index 를 지우고 `uk_game_build_project_version` UNIQUE 제약을 다시 건다.
  editor 행을 지우면 `content_map`(V40 `ON DELETE CASCADE`)과 그 아래가 함께 지워지고 `test_run` · `qa_run` 의
  `game_build_id` 는 NULL 이 된다.

## Rejected feedback

- fast: project 범위 content map 조회에 `is_editor = FALSE` 를 걸자 — 거절. 버전 순서가 아니고, editor content map
  이 authoring 의 주 입력이다(표 참고).
- fast: `contentMapIdOf` 전용 테스트 — 거절. 그 함수는 private 이고 `findAllForMember` 첫 행을 그대로 쓴다. 순서는 (d) 가 덮는다.
- fast: editor 행 label · notes 수정 테스트 — 거절. `GameBuildService.update` 는 `is_editor` 를 보지 않고, `copy` 가 컬럼을 보존한다.
- fast: 재시도를 트랜잭션 밖으로 — 거절. 계약이 기존 패턴 유지를 정했고, 등록 전체의 트랜잭션 구조를 바꾸는 일이다.
- heavy: 동시 editor 등록 테스트 — 거절. 재시도가 같은 트랜잭션 안이라 Postgres 에서는 abort 되어 검증할 수 있는 동작이 없다(Risks).
- medium: `findOrCreateBuild` 를 하나로 — 반영. 메서드 수 줄이기 — 반영. editor `cur` 의 REGRESSION — 반영(아무것도 바꾸지 않음).

## Open Questions

- 없음
