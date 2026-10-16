# 2026-10-15 — QA 실행 화면을 워크벤치 + 실행 설정 패널로 다시 짓는다

- Date: 2026-10-15
- Jira: ARTEL-1082 (스토리) · ARTEL-1083 (orchestration) · ARTEL-1084 (home)
- Status: Draft

## Goal

`/projects/{id}/qa` 의 QA 실행 영역을 확정 시안(Design 캔버스 "QA 실행 화면 리디자인 시안", 워크벤치 + 인스펙터안)대로 다시 짓는다.
사이드바 · 탑바는 그대로 둔다. 시안 뒤 사용자 조정(2026-10-15):

| 조정 | 반영 |
|---|---|
| "런 바꾸기" 버튼 대신 런 카드 자체를 누르면 바뀐다 | 카드 = 버튼, 오른쪽에 펼침 화살표 |
| 지난 실행 이후 · 최근 실행 | 접기, 기본 펼침 |
| 이슈는 제목만 나열 | 이슈 칸과 "그 뒤로 바뀐 것" 칸 높이를 맞추고 칸마다 `EdgeScrollbar` |
| 실패한 시나리오만 다시 실행 | 미구현 — 비활성으로 보이고 이유를 툴팁(`InfoHint`) |
| 갈 곳이 있는 항목 | 링크(이슈 → 이슈 화면, 시나리오 → 스튜디오, 실행 → QA 런 화면, 빌드 → 콘솔 관리) |
| 디자인 | 서비스 토큰 · 기존 컴포넌트 · 아이콘 관례를 따른다. 제공사는 실제 로고 |

## Non-goals

- 실패한 시나리오만 다시 실행(서버가 런의 일부 시나리오만 돌리는 기능) — 별도 이슈.
- 시나리오 스텝 단위 변경 내역 · 런에서 뺀 시나리오 — 기록이 남지 않는다.
- 이슈 사진 · 영상 시점 — 이슈에 없다.

## Context / Constraints

- QA 실행(`qa_run`) **목록 조회가 없다**(`QaRunController` 는 생성 · 단건 · 변경 · 취소뿐). 홈은 시나리오 단위 `qa_try` 목록만 쓴다.
- `qa_run`: `test_run_id` · `game_instance_id` · `game_build_id` · `key_source` · 상태(COMPLETED/CANCELLED/FAILED/…) · 시각. 모델 · 추론은 `qa_try.model` · `reasoning_effort` 에 있다. 케이스 수는 `qa_try.cases_passed/total`.
- 이슈: `issue.detail` = title · severity · actual · expected · step · reproduction, `status`(OPEN/…) · `resolved_at` · `resolved_by`. 이미지 없음.
- 변경 근거 시각: `game_build.created_at/updated_at`, `test_scenario.updated_at`, `test_run_scenario.created_at`, `test_case_version.created_at`, `issue.resolved_at`.
- 조회 패턴: 여러 표를 접는 읽기는 `DatabaseClient` 리포지토리(`QaTryDetailRepository` 와 같은 규약), 접근 판정은 서비스에서 `ProjectAccessService` 로 먼저.
- 홈: `QaSection` = `QaTryPanel` + `GameInstancePanel` + `GameBuildPanel`. 런 트리는 `runLibrary` · `testRunFolders`(버전 → 폴더 → 런). 서비스 스크롤바 `EdgeScrollbar`, 접기 `CollapseIcon`, 설명 `InfoHint`, 모델 선택 `QaModelPicker`.

## Approach (Checklist)

- [ ] **Step 0: Recon** — 위 Context 확인 완료. 홈 워크스페이스 데이터(`useWorkspace`: runs · instances · builds · models · tries · openIssues).
- [ ] **Step 1a: orchestration (ARTEL-1083)** — 조회만, 스키마 변경 없음
  - `GET /api/projects/{projectId}/qa-runs?limit=` → `QaRunSummary[]` 최신순: id · testRunId · testRunName · gameInstanceId · gameInstanceName · gameBuildVersion · model · reasoningEffort · keySource · status · startedAt · completedAt · casesPassed · casesTotal · issueCount.
  - `GET /api/projects/{projectId}/test-runs/{runId}/since-last-qa` → `{ lastQaRun: QaRunSummary?, issues: [id · title · severity · status · qaTryId · scenarioId · scenarioTitle · step · reportedAt], changes: [kind · at · subjectId · subjectName · detail] }`.
    - changes kind: `ISSUE_RESOLVED`, `BUILD_REPORTED`, `SCENARIO_UPDATED`, `SCENARIO_ADDED`, `CASE_VERSION_ADDED`(건수로 묶음). 마지막 실행 시작 이후 것만, 최신순.
    - 마지막 실행이 없으면 `lastQaRun=null`, 빈 목록.
  - 새 `QaRunReadRepository`(DatabaseClient) + `QaLaunchReadService`(접근 판정) + 컨트롤러 둘. 비참여자 404.
  - 테스트: 리포지토리 통합 테스트(테스트 DB) — 최신순 · 집계 · 변경 종류별 · 접근 404.
- [ ] **Step 1b: home (ARTEL-1084)**
  - API: `qaLaunchApi.ts` (위 두 조회 파서 + 테스트).
  - 화면 `QaSection` → 새 `QaLaunch` 레이아웃(워크벤치 + 오른쪽 실행 설정 패널, 전폭).
    - `RunCard`(카드 = 버튼 + 화살표) + `RunTreePicker`(runLibrary/testRunFolders 재사용: 버전 → 폴더 트리 + 그 노드의 런 목록 + 검색).
    - `QuickStart` 칩: qa-runs 에서 (런 · 게임 · 모델 · 추론 · 키) 조합 최근 3개.
    - `CommandPalette` ⌘K: 런 · 최근 조합 · 동작(새 런, 설치 안내).
    - `SinceLastRun`(접기): 이슈 제목 목록 | 그 뒤로 바뀐 것 — 같은 높이, 칸마다 `EdgeScrollbar`. 실패만 다시 실행(비활성 + `InfoHint`).
    - `RecentRuns`(접기): 표(①~⑥, TR 이름) + 통과율 막대(같은 번호, hover 연동).
    - `LaunchPanel`: 한 문장 요약 · 게임 콘솔(선택 + 새로고침 · 설치 안내 · 관리) · 끊김 안내 · `QaModelPicker`(+ 제공사 로고) · 추론 · 실행 전 점검 · 하단 고정 실행(⌘↵, 지난 실행 기준 예상 시간). 실행/충돌/이어받기는 `QaTryPanel` 의 로직을 그대로 옮긴다.
    - `ConsoleDrawer`: 기존 `GameInstancePanel` · `GameBuildPanel` 을 그대로 담는다(기능 손실 0).
  - 제공사 로고: `qaModelIcons.tsx` 관례(출처 · 라이선스 주석, `currentColor` 가능한 SVG)로 OpenAI · Claude · Gemini(Simple Icons, CC0). xAI 는 로고가 없어 글자 마크.
  - i18n ko/en, `DESIGN.md` 토큰, 다크/라이트.
- [ ] **Step 2: Tests** — orche 통합 테스트; home `npm test` · typecheck · build · lint(신규 0); 로컬 E2E(다른 세션과 겹치지 않게 별도 포트).
- [ ] **Step 3: Rollout / Rollback** — orche 먼저 머지. 홈은 새 API 가 404(옛 서버)여도 깨지지 않게 해당 칸을 숨긴다.

## Validation

- **Commands to run:** orche `./mvnw -q test -Dtest='*QaLaunch*,*QaRunRead*'`; home `npm test`, `npm run typecheck`, `npm run build`, `npx eslint src/qa src/projects`.
- **Expected output:** 전부 통과, lint 는 develop 대비 신규 0.
- 화면: 다크/라이트에서 런 카드 → 트리 피커, 빠른 시작, ⌘K, 접기, 이슈 · 변경 칸 스크롤, 실행 → QA 런 화면 이동.

## Risks & Rollback

- **Risks:** 변경 목록이 "기록 시각" 기반이라 시나리오의 무엇이 바뀌었는지는 못 보인다(문구로 한정). qa_run 이전의 단독 qa_try 는 목록에 안 나온다.
- **Rollback steps:** 각 PR `git revert`. API 는 조회 전용이라 데이터 영향 없음.

## Open Questions

- 빠른 시작 조합의 "같은 조합" 판정 키 — (런, 게임, 모델, 키 출처)로 두고 추론은 마지막 값을 채운다.
