# 2026-07-28 — P3: FE Canvas 시나리오 도면 + TestCase 사이드바 DnD

- Date: 2026-07-28
- Jira: None
- Status: Draft
- Repo/Branch: **artel-home**(FE), (신규) `feat/canvas-testcase-composition-*`
- 개요: `2026-07-28-testcase-scenario-run-redesign-overview.md`

## Goal
구조 변경(케이스 조합형 시나리오)에 맞춰 FE를 수정하되 **기존 UX를 훼손하지 않는다**:
1. **Canvas**: 하나의 TestScenario를 **한 도면**에서 확인, **드래그앤드롭 편집**(기존 기능 유지).
2. **좌측 사이드바**: **미리 만들어진 TestCase 목록**을 확인, **드래그앤드롭으로 시나리오에 배치**.
3. **LLM 챗봇 시나리오 제작**을 **기본 제공 방법**으로 유지.

## Non-goals
- Run 실행/모니터 UI(별도, 아마 P4 이후). 
- 리셋 최적화 시각화(P2 후속).
- 백엔드 스키마/API 정의(P1) — 여기선 소비만.

## Context / Constraints
- 현재 artel-home: `ScenarioCanvas.tsx`(노드=ScenarioStep, DnD 재배치), `useScenarioSession.ts`(초안/Undo 스택 20/Ctrl+Z), i18n(EN/KO), 챗봇 세션(`/sessions` SSE/WS).
- 기존 **DnD 재배치 · Undo/Redo · i18n** 절대 회귀 금지.
- 노드 단위가 `ScenarioStep` → **`TestCase`(조합 항목)** 로 바뀜.

## Approach (Checklist)
- [ ] **Step 0: Recon** — `ScenarioCanvas.tsx`, `useScenarioSession.ts`, i18n 메시지, 시나리오 조회/수정 API 클라이언트.
- [ ] **Step 1: Canvas 노드 모델 전환** — 노드=TestCase(조합 position 기반). 기존 순서 DnD·Undo·엣지 연결 유지. 참조/스냅샷 모드에 따른 노드 표기(참조면 "공유 케이스" 뱃지).
- [ ] **Step 2: 좌측 TestCase 라이브러리 사이드바** — 프로젝트의 재사용 케이스 목록(검색/필터: tag·feature·검증상태 DRAFT/VERIFIED). 카드에서 **드래그 → 캔버스 드롭 = 시나리오에 케이스 추가**.
- [ ] **Step 3: 챗봇 통합 유지** — 챗봇이 기본 작성 경로. 챗봇 산출(케이스 조합)을 캔버스에 반영, 수동 DnD와 공존.
- [ ] **Step 4: API 연동** — 케이스 라이브러리 조회, 시나리오 조합 편집(추가/삭제/재배치) 엔드포인트 소비(P1 백엔드).

## Validation
- **수동 체크:** (a) 기존 시나리오 DnD 재배치·Undo/Redo·Ctrl+Z 정상, (b) 사이드바에서 케이스 드래그→드롭 추가, (c) 챗봇으로 시나리오 생성이 기본 경로로 동작, (d) EN/KO i18n 정상.
- **자동:** 기존 캔버스/세션 테스트 회귀 없음 + 신규 사이드바 DnD 테스트.

## Risks & Rollback
- **Risks:** 캔버스 리팩터가 기존 DnD/Undo를 깨뜨릴 위험(회귀 테스트 필수). 참조 모드일 때 "한 케이스 수정이 여러 시나리오에 영향" → 사용자 혼란(경고 UI 필요).
- **Rollback:** 기능 플래그로 신규 사이드바/조합 UI 토글, 실패 시 기존 step 캔버스로 복귀.

## Open Questions
- 케이스 라이브러리 필터 축(tag·feature·검증상태 중 무엇을 1차로).
- **스냅샷 vs 참조**의 UX: 참조면 공유 케이스 편집 경고/버전 표기 필요.
- Run(복수 시나리오) 조립 UI를 P3에 포함할지, 뒤로 미룰지.
- 백엔드 조합 API 형태(P1 확정 의존).
