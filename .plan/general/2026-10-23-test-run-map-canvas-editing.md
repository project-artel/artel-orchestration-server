# 2026-10-23 — 테스트 런 맵 캔버스 재설계와 맵 위 편집

- Date: 2026-10-23
- Jira: ARTEL-1243 (스토리) · ARTEL-1244 (orchestration-server) · ARTEL-1245 (home) · 이후 ARTEL-1246 · ARTEL-1247 (동시 보기)
- Status: Draft

## Goal

테스트 런 맵을 시안(https://claude.ai/artifact/KdFCMxaUA438HiULGiEgd6 v10) 대로 다시 만들고, 맵에서 바로 고칠 수 있게 한다.

- TS 하나 = 자유롭게 끄는 프레임. 처음엔 런 순서 자동 배치(세 칸, 가장 짧은 칸에 다음 TS), 접기 · 자동 배치, 옮긴 자리 · 접힘은 런마다 저장한다.
- 프레임 안: 스텝 점을 화살표로 잇는다. 한 줄 8스텝, 줄이 바뀌면 오른쪽으로 빠져 줄 사이를 지나 다음 줄 왼쪽 밖에서 들어오는 각진 선. 씬은 색이 아니라 구간 이름 + 번갈아 명암. 색은 GAP · 고른 것에만.
- 스텝 마우스 카드(동작 · 대상 · 원문 · 씬 · TC), 클릭하면 서랍(씬 구간별 스텝 전부, 이전 · 다음 TS, 스텝 추가, TC 가져오기, 편집 열기).
- 상단 툴바(선택 · 손 · TS · 메모 · 글 · 사각형 · 원 · 화살표 · 그림 · 되돌리기 · 다시 하기)와 우클릭 메뉴(빈 곳 · TS · 스텝 · 주석마다 다름), 단축키(V H S N T R O A P, ⌘Z, ⇧⌘Z, ⌫, ⌥←→).
- TS 편집: 스텝 추가 · 앞뒤 옮기기 · 지우기, TC 가져오기, 새 TS · 복제 · 런에서 빼기.
- 메모 · 글 · 사각형 · 원 · 화살표 · 그림은 런 맵 주석으로 저장한다.

## Non-goals

- 동료 커서 · 보는 중 표시: ARTEL-1246(서버 웹소켓) · ARTEL-1247(home). 이 플랜의 다음 단계다.
- 씬 전이 그래프(화면 지도의 몫), TS 사이를 이어 실행 순서를 바꾸는 것(TS 는 각자 초기화 후 돈다).
- 동시 편집 자동 병합. 나중 저장을 409 로 막고 다시 읽게 한다.
- 도형 크기 조절 핸들, 주석과 TS 를 잇는 연결선(시안에도 없다).

## Context / Constraints

- 지금 맵은 `src/testRuns/RunMapPage.tsx`(home) 하나. TS 를 세로로 쌓고 펼치면 스텝을 옆으로 늘어놓는다. agent 바인딩 다섯(`TEST_RUN_SCENARIOS_FIELD`, `OPEN_EDITOR`, `SELECT_SCENARIO`, `OPEN_SCENARIO`, `START_QA`)이 이 페이지에 붙어 있다. 이름과 동작을 그대로 둔다.
- TS 데이터는 이미 있는 경로로 저장한다. 맵이 새로 만드는 TS 데이터 저장소는 없다.
  - 스텝: `PUT /api/test-scenario/{id}` (편집 화면 자동 저장과 같은 경로, 기대 판정 라벨은 서버가 보존)
  - 런 구성(새 TS 넣기 · 복제 · 빼기): `PUT /api/projects/{projectId}/test-runs/{runId}/scenarios`
  - 새 TS: `createTestScenario`
  - TC 고르기: 편집 화면의 `AddCasesDialog` + `useProjectCases`
- 새 저장소는 맵 배치 · 주석 하나뿐이다(ARTEL-1244).
- 런 · 시나리오 변경은 `ProjectChangeTopic.TEST_RUNS` 로 이미 알린다(ARTEL-1216). 맵 저장도 같은 topic 으로 알린다.
- Flyway: develop 의 마지막은 V136. V137 은 ARTEL-1134 브랜치가 이미 쓰고 있어 이 브랜치는 V138.

## Approach (Checklist)

- [ ] **Step 0: Recon** — 끝냄. `TestRunService.accessible`(프로젝트 멤버 확인), `ProjectChangeStreamManager.notify`, JSONB 는 `io.r2dbc.postgresql.codec.Json`, 런 삭제는 `TestRunService.delete` 트랜잭션.

- [ ] **Step 1a: orchestration-server (ARTEL-1244)**
  - `V138__create_test_run_map.sql`: `test_run_map(test_run_id BIGINT PRIMARY KEY REFERENCES test_run(id) ON DELETE CASCADE, layout JSONB NOT NULL DEFAULT '{}', annotations JSONB NOT NULL DEFAULT '[]', version INT NOT NULL DEFAULT 0, updated_by BIGINT, updated_at TIMESTAMPTZ NOT NULL DEFAULT now())`. 런이 지워지면 함께 지워진다.
  - `testrun/controller/TestRunMapController.kt`: `GET` · `PUT /api/projects/{projectId}/test-runs/{runId}/map`. 경로의 `projectId` 와 런의 프로젝트가 다르면 404.
  - `testrun/dto/TestRunMapDtos.kt`
    - 요청 `{ version, layout: { "<testScenarioId>": { x, y, folded } }, annotations: [...] }`
    - 주석은 `type` 으로 갈린다: `note{ id, x, y, text, color }` · `text{ id, x, y, text }` · `rect` · `ellipse{ id, x, y, w, h }` · `arrow{ id, x1, y1, x2, y2 }` · `pen{ id, points:[[x,y]] }`. 모든 주석에 `createdBy` · `createdAt` 은 서버가 채운다(새 주석) 또는 보존한다(있던 주석).
    - 응답 `{ runId, version, layout, annotations, updatedAt, updatedBy }`. 맵이 없으면 `version 0` 과 빈 값.
  - `testrun/service/TestRunMapService.kt`
    - 버전 확인은 SQL 한 번으로: 맵이 없고 `version == 0` 이면 `INSERT ... ON CONFLICT DO NOTHING`, 있으면 `UPDATE ... WHERE test_run_id = :id AND version = :version`. 바뀐 행이 0이면 `ConflictException(code = "run_map_stale")`.
    - 상한: 주석 1,000개, 글 2,000자, 그림 점 2,000개, 좌표 ±1,000,000, layout 은 런에 든 TS 만(나머지는 버린다). 넘으면 `BadRequestException`.
    - 저장 뒤 `projectChanges.notify(projectId, TEST_RUNS)`.
  - `docs/api/openapi.json` 에 두 경로를 더한다.

- [ ] **Step 1b: home (ARTEL-1245)** — `src/testRuns/runMap/` 로 나눈다.
  - 순수 함수(테스트 대상)
    - `stepLabel.ts`: `verbOf` · `targetOf`(시안 규칙)
    - `sceneSegments.ts`: 씬 구간(씬 모르는 스텝은 앞 구간에 붙임)과 TC 구간 번호
    - `frameGeometry.ts`: 프레임 높이, 스텝 좌표, 화살표 경로(곧은 · 각진), 자동 배치
    - `runMapHistory.ts`: 되돌리기 · 다시 하기 스택(한 동작 = 맵 상태 · TS 스텝 앞뒤 한 쌍)
    - `runMapApi.ts`: 맵 GET · PUT, 파서
  - 상태
    - `useRunMapData.ts`: 런 · TS · 맵을 읽고, 프로젝트 이벤트 `TEST_RUNS` 가 오면 다시 읽는다(내가 저장 중이면 건너뜀).
    - `useRunMapSave.ts`: 배치 · 주석 저장은 600ms 모아 PUT, 409 면 최신을 다시 읽고 "다른 사람이 먼저 고쳤습니다" 알림. TS 스텝 저장은 동작마다 바로 PUT.
  - 화면
    - `RunMapPage.tsx`(머리줄 · 편집/맵 전환 유지)
    - `RunMapCanvas.tsx`(팬 · 줌 · 미니맵 · 끌기, 기존 `useCanvasViewport` 를 쓸 수 있으면 쓴다)
    - `TsFrame.tsx` · `RunMapToolbar.tsx` · `RunMapContextMenu.tsx` · `StepHoverCard.tsx` · `RunMapDrawer.tsx` · `RunMapAnnotations.tsx`
  - TC 가져오기: `AddCasesDialog` 를 그대로 띄우고, 고른 케이스를 편집 화면과 같은 방식으로 스텝으로 만든다(같은 변환 함수를 찾아 쓴다).
  - i18n `scenarios.map` 에 ko · en 을 더한다. 화면 문구는 대시 · 줄표 없이.
  - CSS 는 서비스 토큰, 다크 · 라이트.

- [ ] **Step 2: Tests**
  - orche 통합 테스트(`TestRunMapIntegrationTest`)
    - 빈 맵 GET
    - PUT 뒤 GET
    - 같은 버전 두 번 → 두 번째 409
    - 다른 프로젝트 멤버 404
    - 상한 초과 400
    - 런 삭제 → 맵 삭제
    - 저장 시 `TEST_RUNS` 이벤트
  - home `node:test`: 위 순수 함수 다섯
  - 실제 스택에서 run 54 · run 15 로 화면 확인(다크 · 라이트)
  - 스텝 추가 → 편집 화면에서 보임, 되돌리기 → 원래대로

- [ ] **Step 3: Rollout / Rollback**
  - 마이그레이션은 새 테이블만. 되돌리면 테이블만 남고 아무도 읽지 않는다.
  - home 은 같은 경로의 페이지를 바꾼다. 되돌리면 옛 맵으로 돌아간다.

## Validation

- **Commands to run:**
  - orche `./mvnw -q test -Dtest='TestRunMapIntegrationTest'`, 관련 `TestRun*` 테스트
  - home `npm test`, `npx tsc -b`, `npx eslint src/testRuns`, `npx vite build`
- **Expected output:** 모두 통과. 화면 캡처를 PR 에 넣는다.

## Risks & Rollback

- **Risks:**
  - 맵에서 스텝을 고치는 동안 편집 화면에서도 같은 TS 를 고치면, 나중 PUT 이 먼저 것을 덮는다(TS 저장 경로에 버전이 없다). 이번엔 TS 저장 전에 최신 TS 를 다시 읽어 그 위에 고친다. 그래도 같은 순간 겹치면 덮인다. 이 한계는 PR 에 적는다.
  - TS 97개 · 스텝 499개를 DOM 으로 그린다. 시안(같은 규모)은 버벅이지 않았다. 느리면 화면 밖 프레임을 접힌 모양으로 그린다.
  - 되돌리기가 TS 저장까지 되돌리므로, 되돌리기도 PUT 을 한 번 더 한다.
- **Rollback steps:** 두 PR 을 각각 revert. 테이블은 남아도 해가 없다.

## Open Questions

- 없음. 시안에서 사용자가 확정했다("바로 이겁니다", 2026-10-23).
