# TS 재사용 · 버전별 TC 검증 상태 · QA 중 TC 상태 갱신 (팀원 구조 반영판)

## Context

실험 브랜치가 길어지면서 원래 기획에 있던 기능 넷이 밀렸다: TS 재사용, 게임 버전별 TC, TS 관리, QA 중 TC 상태 갱신.

메인 폴더(`~/project/Artel/artel-orchestration-server`)는 다른 세션이 실험 브랜치로 쓰고 있다. 그래서 **별도 워크트리에서만** 작업한다.

그 사이 팀원 PR 이 develop 에 머지됐다.

| PR | 내용 | 이 작업에 주는 영향 |
|---|---|---|
| #290 | test case 일괄 삭제 API | — |
| #292 | `V99` `test_case_folder` 와 `test_case.folder_id` | 다음 마이그레이션 번호가 V99 다음이 된다 |
| #293 | 적재가 TC 를 만들지 않는다. `POST /api/projects/{p}/game-builds/{b}/content-map/test-cases` 로 **build 마다** TC 를 만든다. 결과는 `"<label> content map"` 폴더에 들어간다. `rewrite` 범위는 `metadata.content_map_id` 하나다 | **같은 케이스가 build 마다 다른 행이다.** build 를 넘어 같은 케이스인지는 `metadata.case_key` 로만 잇는다 |

home 쪽은 #128 → #129(TC 폴더 tree: `FolderPane`, `testCaseFolders.ts`, `FolderPicker`) → #130(생성 버튼) 이 스택으로 아직 OPEN 이다.

**사용자 결정**

- 버전 방식은 **팀원 구조를 따른다.** build 마다 따로 있는 TC 행이 곧 그 버전의 TC 이고, 그 행의 상태가 그 버전의 검증 결과다. 앞 버전 행은 그대로 남아 이력이 된다. 따로 이력 테이블을 두지 않는다.
- TC 상태값은 원래 계획대로 2개를 추가한다.
  - `FAILED`: QA 실패.
  - `REGRESSION`: 앞 버전에서 판정이 났고 이번 버전에서 재검증이 필요한 상태.
  - `BROKEN` 은 지도 재생성으로 깨진 TC 에만 쓴다.
- TS 가져오기는 **복사본**이다.
- TR 에 **어느 게임 버전에서 만들었는지** 남긴다(버전 단위로 TR 째 재사용하게 될 것이라 봄).
- FE 는 **시나리오 생성 / TR 화면에 먼저** 적용한다. 트리는 **모달**로 만든다. 팀원 트리는 지금 TC 라이브러리에서만 쓰인다.

agent-server 는 바꾸지 않는다. 이미 스텝마다 `case_id`, `is_verification`, COMPLETED/FAILED 를 보낸다.

## Jira · 브랜치 · 워크트리

- **스토리**는 레포 두 개에 걸친다(`issue.md` Multi-Repository).
  - parent 는 Epic ARTEL-12. 머지 순서는 orche → home 이다.
  - Subtask 는 2건이다: orche(`orchestration-server`, `feat`)와 home(`home`, `feat`).
  - 할당자는 komcss121908@gmail.com 이다.
  - `Blocks` 링크는 inward=orche, outward=home 으로 걸고, 다시 읽어 방향을 확인한다.
- **진행 중 전환은 orche Subtask 만 지금 한다.** 자동 브랜치가 생긴다(from develop). home Subtask 는 orche 작업이 끝날 때 전환한다. 그 시점에 #128~#130 이 머지됐으면 develop 기반이 맞다. 아니면 자동 브랜치를 #130 브랜치(`feat/home-content-map-화면에-test-case-생성-버튼-추가-ARTEL-972`) 위로 다시 올리고 base 도 그쪽으로 둔다.
- **워크트리** 위치는 `~/project/Artel/.worktrees/` 이다. 메인 폴더는 checkout 하지 않는다.
  - `git -C ~/project/Artel/artel-orchestration-server worktree add ~/project/Artel/.worktrees/orche-<KEY> <자동 브랜치>`
  - home 도 같은 방식이다.
  - 이후의 빌드·테스트·서버 기동은 전부 워크트리 안에서 한다.
- 플랜 사본은 orche 워크트리 `.plan/general/` 에 둔다. 커밋까지만 하고 멈춘다. PR 은 지시가 있을 때만 연다.

## 1. orche (Subtask 1)

### 1-1. 마이그레이션 `V103__version_test_runs_and_record_case_verdicts.sql`

구현 중 develop 이 V100(macro history)·V101·V102 까지 갔다(#295 · #296). 그래서 V103 이다.
`qa_log.type` 에 `CASE_VERDICT` 도 더한다 — SSE 가 qa_log 를 polling 하므로 화면에 닿으려면 표에 적혀야 한다.

```sql
ALTER TABLE test_run ADD COLUMN game_build_id BIGINT REFERENCES game_build(id) ON DELETE SET NULL;
ALTER TABLE qa_run   ADD COLUMN game_build_id BIGINT REFERENCES game_build(id) ON DELETE SET NULL;
-- verification_status CHECK 를 다시 건다: + FAILED, REGRESSION
-- 백필: test_run.game_build_id ← 그 TR 의 마지막 qa_run 인스턴스의 last_game_build_id. 없으면 NULL("버전 없음")
-- 조회용: (project_id, (metadata->>'case_key')) 식 인덱스
```

### 1-2. QA 실행 중 TC 상태 갱신

- `VerificationStatus.kt` 에 `FAILED` 와 `REGRESSION` 을 추가한다.
- `QaTryService.createRun` 은 `qa_run.game_build_id = instance.lastGameBuildId` 로 고정해 둔다.
- 새 서비스 `testcase/service/TestCaseVerdictService.recordVerdict(qaTryId, testCaseId, passed)`
  - `case_id` 가 가리키는 TC 행의 `verification_status` 를 VERIFIED 또는 FAILED 로 바꾼다. `last_verified_build_id` 는 qa_run 의 build 로 채운다.
  - BROKEN 인 행은 건드리지 않는다.
  - TC 가 qa_try 의 프로젝트에 없으면 무시하고 로그만 남긴다.
- 호출 지점은 `QaAgentInboundRouter.routeStatus` 의 스텝 프레임 분기다.
  - 조건: `is_verification=true` 이고, `case_id` 가 있고, status 가 COMPLETED 또는 FAILED.
  - `qa_log` 저장과 publish 는 지금 그대로 둔다.
- 반영된 결과는 같은 qa_try SSE 에 live 전용 `CASE_VERDICT {case_id, verification_status}` 프레임으로 내보낸다. qa_log 에는 쓰지 않는다.

### 1-3. 새 버전의 REGRESSION 시작

- `ContentMapTestCaseService` 의 생성 트랜잭션에서 `rewrite` 가 끝난 직후 처리한다.
- 대상은 이번 content map 에서 **새로 생긴 행**(created)이다.
- 같은 프로젝트에서 `case_key` 가 같은 행 가운데 직전 build(같은 프로젝트에서 이 build 보다 먼저 만든 build 중 최신) 쪽 행이 VERIFIED 또는 FAILED 면, 새 행을 `REGRESSION` 으로 둔다.
- 앞 버전 행은 바꾸지 않는다. 그 행들이 이력이다.
- 생성 응답에 `regression: n` 을 추가한다.
- `MapTestCaseWriter` 는 실험 브랜치와 겹치는 파일이다. 손대지 않고 서비스에서 후처리한다.

### 1-4. TR 버전 · TS 가져오기 · TC 이력 API

- **TR 생성.** `game_build_id` 를 프로젝트 최신 build 로 채운다. TR 응답에 `gameBuild {gameBuildId, version, label} | null` 을 넣는다.
- **`GET /api/projects/{projectId}/test-scenario-library`** 는 모달 트리 데이터다.
  - 구조는 `[{gameBuild|null, testRuns:[{testRunId, name, testScenarios:[{testScenarioId, title, stepCount, updatedAt}]}]}]` 이다.
  - 버전은 최신순이다. 어느 TR 에도 없는 TS 는 `testRunId:null` 묶음에 넣는다.
- **`POST /api/projects/{projectId}/test-runs/{testRunId}/scenarios/imports`** 의 body 는 `{testScenarioIds}` 다.
  - 각 TS 를 복사해 대상 TR 의 끝 position 에 붙인다.
  - 복사할 때 steps 의 `case_id` 를 **대상 TR build 의 같은 `case_key` 행으로 바꿔 끼운다.** 그래야 QA 판정이 이번 버전 행에 쌓인다.
  - 짝이 없는 case_id 는 그대로 두고 응답의 `unmatchedCaseIds` 로 알린다. 대상 TR 의 build 가 null 이면 매핑하지 않는다.
  - 다른 프로젝트의 TS id 는 `common/error` 타입 예외로 거절한다.
  - TR 통째 가져오기는 FE 가 그 TR 의 TS id 를 전부 보내는 것으로 처리한다.
- **`GET /api/projects/{projectId}/test-cases/{testCaseId}/verdict-history`** 는 같은 `case_key` 를 가진 build 별 행을 build 순서로 돌려준다: `[{testCaseId, gameBuildId, version, verificationStatus}]`.
- 마무리로 SecurityConfig 경로를 확인하고 openapi 스냅샷을 다시 생성한다.

### 1-5. 테스트 (Testcontainers 통합 테스트. 팀원 PR 이 쓴 `-Dtest=` 묶음 방식)

- 인바운드 라우터
  - 검증 스텝 → VERIFIED/FAILED 와 `last_verified_build_id` 가 바뀐다.
  - 검증 스텝이 아니거나 case_id 가 없으면 무시된다.
  - BROKEN 행은 그대로다.
- 생성 API: build A 에서 VERIFIED 였던 case_key 가 build B 생성에서 REGRESSION 이 된다. build A 행은 그대로다.
- import: 복사본이 생긴다. 원본은 변하지 않는다. position 이 이어진다. case_id 가 build B 행으로 바뀐다. 짝이 없으면 `unmatchedCaseIds` 에 나온다. 다른 프로젝트 id 는 거절된다.
- library / history API 의 묶음과 순서.
- `scripts/check-flyway-migrations.sh` 와 `scripts/verify-flyway-upgrade.sh` 를 돌린다.

## 2. home (Subtask 2)

- **진입점.**
  - 시나리오 생성 쪽: `ScenarioList.tsx` 헤더의 `＋` 옆에 `📂` 불러오기 버튼을 둔다. `?run=` 안에서만 보인다.
  - TR 쪽: `RunEditPage.tsx` 빈 TR 화면의 "새 시나리오" 옆에 같은 버튼을 둔다.
- **모달** `src/testScenarios/ScenarioImportDialog.tsx`
  - 바탕은 `design-system/primitives/Dialog.tsx` 다.
  - 트리는 `📁 버전` → `📁 TR` → `📄 TS` 의 파일 아이콘 구조다. 팀원 #129 의 트리 렌더링(`FolderPane`, `testCaseFolders.ts` 의 tree 만들기·들여쓰기)을 재사용할 수 있으면 쓰고, 폴더 API 에 묶여 있으면 같은 모양으로 맞춰 별도 컴포넌트로 만든다(구현 시 #129 코드를 읽고 정한다).
  - TS 단위로 체크하고, TR 노드에서 "TR 전체" 를 고를 수 있다. 확인하면 import API 를 부른다. 이어서 목록을 갱신하고 첫 복사본으로 이동한다. `unmatchedCaseIds` 가 있으면 "이 버전에 없는 TC N건" 알림을 띄운다.
- **TR 버전 칩.** `RunListPanel` 행과 TR 헤더에 붙인다. `testRunApi.ts` 파서에 `gameBuild` 를 추가한다.
- **TC 상태**
  - `VerificationStatus` 에 `FAILED` 와 `REGRESSION` 을 추가하고, `vdot` 색·i18n·`TestCaseEditor` 선택지에도 넣는다.
  - `ScenarioStepEditor` 의 "TC n" 박스에 배지를 단다.
  - `TestCaseModal` 에 버전별 이력 표를 넣는다.
  - QA 화면(`useQaTry`/`qaProgress.ts`)은 `CASE_VERDICT` 를 받는 즉시 배지를 바꾼다.
- 테스트는 `node --test` 로 한다: 트리 변환, import 응답 파서, `CASE_VERDICT` 반영.

## 범위 밖

- 게임 정보 diff 로 버전을 자동으로 올리는 일. 버전은 지금처럼 SDK 가 보고하는 version 문자열로 정해진다.
- TS 폴더 관리. 이번에는 가져오기 트리로 둘러보는 것까지만 한다.
- `MapTestCaseWriter` 내부 수정. 실험 브랜치와 겹친다.

## 검증

1. orche 워크트리: `./mvnw -o test -Dtest='<위 테스트 묶음>,OpenApiSnapshotTest'`. 전체 suite 는 원래 깨진 것이 100건 안팎이라 판단 근거로 쓰지 않는다.
2. home 워크트리: `npm test`, `npm run build`.
3. 로컬 E2E
   - 같은 Postgres 에 **별도 DB**(`artel_ts_reuse`)를 만든다. 서버는 **다른 포트**(8091)로 띄운다. 실험 세션의 DB(V100 적용)와 8081 은 건드리지 않는다.
   - `artel-jwt` 로 토큰을 만든다.
   - 확인 순서
     1. build A 에서 TC 를 생성하고, TR/TS 를 만든 뒤 QA 를 1회 돌린다. 배지가 실시간으로 VERIFIED/FAILED 로 바뀌고 DB 에도 반영되는지 본다.
     2. build B 를 등록하고 TC 를 생성한다. REGRESSION 이 붙는지 본다.
     3. build B 로 새 TR 을 만든다.
     4. `📂` 모달에서 build A 의 TS 를 가져온다. case_id 가 B 행으로 바뀌었는지 본다.
     5. 이력 표를 확인한다.
   - QA 실행에 공용 agent-server 를 쓸지는 그 시점에 묻는다.

## 구현 중 바뀐 것 (최종)

- `CASE_VERDICT` 는 live 전용이 아니라 qa_log 행으로 남긴다(SSE 가 표를 읽는다).
- 가져오기 트리 시나리오에 `caseCount`(서로 다른 TC 수)를 싣는다 — 모달 목록의 TC 열.
- home 모달은 사용자 시안 리뷰를 거쳐 "버전·런 탐색기"(고른 것 줄 + 왼쪽 버전→런 + 오른쪽 시나리오 목록)로 바뀌었다. 아이콘은 Tabler `playlist-add`(MIT), Lucide `clipboard-list`(ISC).
- home 은 develop 의 새 QA 화면(`QaScenarioSteps`)에 TC 저장 상태 점을 붙였다(`QaStepTimeline` 은 develop 에서 지워졌다).
