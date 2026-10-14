# 케이스 라이브러리 버전 관리 — 케이스 정체 · 버전 표 · 사용자 폴더

Jira: ARTEL-1018(1단계), ARTEL-1019 = 1021 orche + 1022 home(2단계), ARTEL-1020 = 1023 orche + 1024 home(3단계).
결정(사용자, 2026-10-13): **버전 표 방식**. 한 행에 버전별 JSONB 를 두는 안은 버리고, 정체 표와 버전 표로 나눈다.

## Context

#293 이후 같은 케이스가 게임 버전(build)마다 별개의 `test_case` 행이다. 버전을 넘어서는 `metadata.case_key` 로만 이어진다. 그래서 다음 일이 생긴다.

- 시나리오 · 폴더 · QA 판정이 버전마다 다른 `case_id` 를 가리킨다.
- 가져오기에서 `case_id` 를 바꿔 끼워야 하고, 옛 시나리오를 새 버전에서 돌리면 판정이 앞 버전 행을 덮어쓴다.
- 버전 비교는 TC 카드를 하나씩 열어야 보인다.

목표는 케이스 라이브러리를 "버전별 TC 관리 + 회귀 짚기 + 사용자 폴더" 통합 시스템으로 만드는 것이다.

## 데이터 모델 (1단계, ARTEL-1018)

```
test_case          케이스 정체. 프로젝트 안에서 case_key 당 한 행(사람이 쓴 케이스는 키 없이 한 행).
                   내용 칸(scene · step · …)과 verification_status 는 "그 케이스가 있는 가장 최근 버전" 의 값을 비춘다.
                   → 이 표를 읽는 기존 쿼리(약 96곳)는 그대로 최신 내용을 읽는다.
test_case_version  (test_case_id, game_build_id) 유일. 그 버전의 내용 스냅샷 + verification_status + 판정 시각.
                   행이 없으면 "그 버전에 없는 케이스" 다.
```

- 버전 표 칸: `content_map_id`, `scene`, `step`, `precondition`, `condition`, `expected_value`, `expected_items`, `status`, `capability_key`, `metadata`, `verification_status`(CHECK 5값), `verified_at`, `created_at`, `updated_at`.
- 인덱스: `(game_build_id, verification_status)`.
- FK: `test_case_id` → `test_case` ON DELETE CASCADE, `game_build_id` → `game_build` ON DELETE CASCADE.
- **비춤 규칙**(`syncIdentity`): 버전 행이 바뀌면 그 케이스의 가장 최근 버전(build `created_at`, id)을 `test_case` 에 비춘다.
  - 내용은 지도 출신(`metadata.origin = content-map`)일 때만 비춘다. 사람이 쓴 케이스는 사람이 고친 내용이 원본이다.
  - 상태와 `last_verified_build_id` 는 늘 비춘다.

### 마이그레이션 V106 (데이터)

1. 표를 만든다.
2. 지도 출신 행 중 `content_map` 을 찾을 수 있는 것만 대상이다. `(project_id, case_key)` 로 묶는다.
3. 묶음마다 **남길 행**을 정한다. 가장 최근 build 의 행이고, 같으면 id 가 큰 쪽이다.
4. 묶음의 모든 행을 버전 행으로 옮긴다(`test_case_id` = 남길 행, build = 그 행의 content map 의 build).
5. 참조를 옮긴다.
   - `test_scenario.steps[].case_id` 를 지울 행에서 남길 행으로 바꾼다.
   - 남길 행에 `folder_id` 가 없으면 묶음에서 가져온다.
   - `test_case_embedding` 은 CASCADE 로 지워진다(남길 행의 것이 남는다).
6. 지울 행을 지운다.
   - `qa_log` · `qa_try_score` 안의 옛 `case_id` 는 이력이라 그대로 둔다.
   - `content_map` 을 찾을 수 없는 지도 출신 행은 그대로 둔다(버전 없음).

### 코드 (1단계)

- **`MapTestCaseWriter.rewrite`**(build B 의 content map)
  - 정체는 프로젝트 전체에서 `case_key` 로 찾는다. 없으면 정체를 새로 만든다. 그다음 B 버전 행을 upsert 한다.
  - 새 버전 행은 직전 버전 행의 상태가 VERIFIED · FAILED · REGRESSION 이면 `REGRESSION`, 아니면 `DRAFT` 다.
  - 이번 생성에 없는 B 버전 행은 지운다. 그 결과 버전이 하나도 안 남은 정체는, 시나리오가 인용하면 `BROKEN`, 아니면 삭제한다.
  - `Result` 에 `regression` 을 더한다. 앞선 `markRegressionFromPreviousBuild` 쿼리는 지운다.
  - 문장 맞춤 fallback(키 없는 옛 행)은 그대로 둔다.
- **`TestCaseVerdictService` / `recordVerdict`**: `qa_run` 의 build 버전 행에 판정을 적는다(없으면 정체 내용으로 스냅샷을 만든다). 그다음 `syncIdentity` 한다. build 가 없으면 정체만 고친다.
- **가져오기**: `case_id` 를 바꿔 끼울 필요가 없어진다. `unmatchedCaseIds` 는 "대상 build 에 버전 행이 없는 지도 출신 케이스" 다. capability id 바꿔 끼우기는 남긴다.
- **`verdict-history`**: 버전 표에서 읽는다.
- **응답 모양**은 바꾸지 않는다.

## 2단계 (ARTEL-1019)

- **orche(1021)**
  - `GET /api/projects/{projectId}/test-case-versions`: 버전 목록(최신 순)과 버전별 수 · 상태별 수.
  - `GET /api/projects/{projectId}/test-cases?gameBuildId=&compareTo=`
    - 그 버전의 행으로 내용과 상태를 덮어쓴 목록을 준다.
    - `compareTo` 가 있으면 비교 버전의 상태와 존재 여부(`BOTH` · `ONLY_THIS` · `ONLY_OTHER`)를 함께 싣는다.
    - `gameBuildId` 가 없으면 지금처럼 정체 목록이다(호환).
- **home(1022)**
  - 왼쪽 위 "전체" → **"버전"** 트리(최신 맨 위, 현재 버전 칩). 버전을 고르면 그 버전 표를 보인다.
  - 개수는 고른 버전 기준이고, 기본은 최신 버전이다. 라이브러리 머리에 현재 버전을 표시한다.
  - **비교 모드**: 이전 버전을 고르면 두 상태를 한 줄에 보인다. "회귀만"(이전 통과 → 지금 실패 · 재검증) 필터를 둔다.
  - 한쪽에만 있는 케이스는 빗금 줄로 보인다. 구버전에 없으면 "구버전에는 없는 케이스", 신버전에 없으면 "해당 버전에서 삭제된 기능" 이다.
  - 폴더 창은 접기/펴기를 할 수 있고, 높이는 화면에 고정한다.

## 3단계 (ARTEL-1020)

- **orche(1023)**
  - `test_case_folder_item(folder_id, test_case_id, position, hidden_game_build_ids BIGINT[])`.
  - `test_case.folder_id` 소속을 옮기고, content map 폴더는 지운다(버전 트리가 대신한다).
  - API: 항목 추가 · 제거 · 이동(폴더 간), 버전별 숨김 토글, 폴더 복제(하위 폴더 + 항목), 폴더 항목을 버전별로 푼 목록.
- **home(1024)**
  - 폴더 트리 디자인은 기존 라이브러리 폴더 트리(`tcl-folder`)로 통일한다.
  - 우클릭 메뉴(새 폴더 · 이름 변경 · 이동 · 삭제 · 복제)와 드래그 앤 드롭을 둔다.
  - Undo/Redo 는 시나리오 편집기의 `useStepEditor` 방식(되돌릴 동작 스택)을 따른다.
  - 버전별 숨김과 빗금 줄을 보인다.

## 범위 밖

- 사용자 정의 TC 를 기준으로 새 버전을 동기화하는 것(추가 고도화)

## 검증

- **단계마다 통합 테스트(Testcontainers):**
  - 마이그레이션은 시드한 build 별 중복 행 → 정체 하나 + 버전 행, 시나리오 참조 이동을 확인한다.
  - 생성기는 버전 행 upsert, REGRESSION, 사라진 버전, BROKEN 을 확인한다.
  - 판정은 버전 행에 적히는지, 비춤이 맞는지 확인한다.
- **로컬 복제 DB(`artel_ts_reuse`):** 마이그레이션 전후 행 수와 참조 무결성을 확인한다. 시나리오가 가리키는 `case_id` 가 모두 존재해야 한다.
- **home:** `npm test` 와 실행 중인 스택 캡처(PR 규칙).
