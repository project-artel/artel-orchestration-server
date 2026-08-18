# 2026-08-18 — 성능 지표군 확장 저장·조회

- Date: 2026-08-18
- Jira: ARTEL-435 (엄브렐러 ARTEL-434)
- Status: In progress

## Goal

지표군이 계속 늘어난다는 전제를 저장·조회에 반영한다.

1. `PERFORMANCE` 프레임의 **모르는 지표군까지** 저장한다.
2. 런 상세·빌드 추세 응답에 `groups` 봉투를 싣는다.
3. 가용성 3상태(`MEASURED` / `UNSUPPORTED` / `NOT_REPORTED`)를 계산해 내려준다.
4. **새 지표군 추가에 마이그레이션이 필요하지 않게** 한다.
5. 원본 표본 보존 정책을 넣는다.

## Non-goals

- SDK 구현 (ARTEL-350/351/352). 새 군은 당분간 전부 `NOT_REPORTED`로 내려간다
- 화면 (ARTEL-436)
- 성능 회귀 자동 판정
- 기존 컬럼·API·테스트 재작성

## Context

`V38__create_sdk_performance.sql`(ARTEL-378, PR #121, develop 머지됨)이 이미 있다.
`sdk_performance_sample` / `_run_summary` / `_run_series` / `_run_budget` 네 테이블,
조회는 사전 집계만 읽는다.

문제는 **지표당 컬럼 하나**라는 점이다. 새 군 3개(필드 25개 남짓)를 같은 방식으로 넣으면
폭 넓은 `ALTER TABLE`이 필요하고, 앞으로 군마다 마이그레이션이 하나씩 붙는다.

계약은 Notion 두 문서의 2026-08-18 확장분이 정한다.

- 성능 지표 런 상세 조회 `3bb0bce5-474c-8136-bbee-fe0c019fdba0`
- 성능 지표 빌드 추세 조회 `3bb0bce5-474c-8145-bf4c-f43404d0d2ba`

## 스키마 선택과 근거

세 안을 놓고 골랐다.

| 안 | 군 추가 비용 | 조회 비용 | 판정 |
|---|---|---|---|
| A. 지표마다 컬럼 (현행 연장) | 마이그레이션 1건 + 집계 SQL 수정 | 가장 싸다 | **탈락.** 군이 늘어난다는 전제와 정면 충돌 |
| B. 군 단위 `jsonb` 한 컬럼 | 0 | 집계할 때마다 jsonb 파싱 | 원본 저장에는 맞고 집계에는 안 맞다 |
| C. 좁은 `(run_id, group, leaf, ...)` 행 | 0 | 인덱스로 잡힌다. 행 수는 군×필드 수만큼 | **집계에 채택** |

**결정: 원본은 B, 사전 집계는 C, 기존 뜨거운 값은 컬럼 그대로.**

- 원본 — `sdk_performance_sample_group(sample_id, group_name, payload jsonb)`.
  받은 것을 그대로 보존한다. 파싱해 이해한 것만 남기면 나중에 규칙이 바뀌었을 때 복원할 수 없다.
- 사전 집계 — `sdk_performance_run_group_metric(qa_run_id, group_name, leaf_path, ...)`과
  시계열용 `sdk_performance_run_series_group(qa_run_id, bucket_at, group_name, leaf_path, ...)`.
  숫자 잎마다 `sample_count / sum / max / min`을 증분 갱신한다.
- 조회는 여전히 사전 집계만 읽는다. `sdk_performance_sample`과 `_sample_group`은 어느 조회
  경로에서도 보지 않는다.

`knowledge.description: string` → `content: jsonb` 논의(2026-08-04 스프린트 회의록)와 같은
저울이지만 결론이 다르다. 저기는 읽을 때 통째로 꺼내 쓰는 문서라 jsonb 하나로 충분했고,
여기는 **읽을 때 집계·정렬·범위 질의를 해야** 해서 집계면은 좁은 행이 맞다. 원본면만 jsonb다.

### 롤업 규칙 — 군마다 코드가 필요한가

숫자 잎마다 `Mean`(sum/count)과 `Max`를 낸다. 이것이 기본값이고 **모르는 군에도 적용된다** —
그래서 계약에 없는 임의 군을 보내도 저장되고 응답에 나온다.

델타 카운터(`collections.gen0` 같은 것)는 평균이 무의미하고 합이 맞다. 어떤 잎이 카운터인지는
`GroupRollupSpec`이라는 **코드 안의 선언 표**에 둔다. 마이그레이션이 아니다. 표에 없는 잎은
기본값(Mean+Max)으로 떨어진다.

→ 군 추가 비용: 마이그레이션 0건. 계약이 이름 붙인 필드를 정확히 내려면 선언 표에 몇 줄.
   아무것도 안 해도 값은 흐른다.

## 가용성 3상태

`null`(값 하나 없음)과 `0`(재봤더니 0) 위에 세 번째가 필요하다 — *이 SDK가 이 군을 아예 모름*.

근거는 `DEVICE_CONTEXT`의 신규 `collectedGroups`(SDK가 수집을 **시도하는** 군 이름 배열)다.

- `collectedGroups`에 있고 값이 온 적 있다 → `MEASURED`
- `collectedGroups`에 있고 값이 한 번도 안 왔다 → `UNSUPPORTED`
- `collectedGroups`에 없다 → `NOT_REPORTED`
- `collectedGroups` 자체가 없는 연결(구버전 SDK) → 모든 군이 `NOT_REPORTED`

서버에 SDK 버전 표를 두지 않으려는 것이 이유다. 버전 표는 SDK가 릴리스될 때마다 서버를
고쳐야 하고, 고치는 것을 잊으면 조용히 틀린 답을 낸다.

## 보존 정책

ARTEL-378이 Non-goals로 미뤄둔 항목(`원본 롤업·삭제 정책 (보존 정책이 정해지면 별도)`)을
이번에 정한다. 뒤집는 것이 아니라 이어받는 것이다.

- 원본(`sdk_performance_sample`, `_sample_group`) — **유한 보존**
- 요약·시계열·budget 도수 — **영구 보존**

**조회 API는 요약·시계열만 읽으므로 보존 정책이 응답에 영향을 주지 않는다.** 오래된 런의
상세 화면도 그대로 뜬다. 사라지는 것은 표본 단위 드릴다운뿐이고, 그런 경로는 현재 없다.

근거: 초당 1건/인스턴스(`ArtelManager.PerformanceReportIntervalSeconds = 1f`), 새 군으로
행 폭이 약 3배. 10분 런이 600행.

이번 변경은 **보존 기간을 설정으로 넣고 삭제 잡을 붙이는 데까지**만 간다. 파티셔닝은 하지
않는다 — 아직 그 비용을 낼 근거가 없다.

## 단계

1. `V39__add_performance_metric_groups.sql`
   - `sdk_performance_sample_group`, `sdk_performance_run_group_metric`,
     `sdk_performance_run_series_group`
   - `sdk_device_context.collected_groups TEXT[]`
   - 전부 `IF NOT EXISTS`. 기존 테이블은 건드리지 않는다 (뒤로 호환)
2. 수신 — `SdkPerformanceMessage`가 미지 군을 잡도록 `@JsonAnySetter`. `SdkDeviceInfo`에
   `collectedGroups`
3. 저장·집계 — 원본 군 payload 저장, 잎 단위 증분 upsert
4. 조회 — `groups` 봉투 조립, `availability` 판정, `sampleRatio`
5. `GroupRollupSpec` — 계약이 이름 붙인 필드의 롤업 종류 선언
6. 보존 — 설정값 + 삭제 잡
7. 테스트

## Risks

- **증분 upsert가 표본당 쿼리 수를 늘린다.** 지금 표본당 4개(원본·요약·시계열·budget)인데
  군 잎 수만큼 더 붙는다. 잎을 한 문장의 다중 VALUES로 묶어 군당 1쿼리로 잡는다.
  ARTEL-378 댓글이 "초당 1건 × 동시 세션" 부하는 측정하지 않았다고 남겼고, 이번에도 측정하지
  않는다. **미검증으로 기록한다.**
- 모르는 군을 그대로 저장하므로 SDK 버그가 쓰레기 군을 보내면 그대로 쌓인다. 군 이름 길이와
  군 수에 상한을 둔다.

## Validation

- `./scripts/check-flyway-migrations.sh`, `./scripts/verify-flyway-upgrade.sh`
- `./mvnw test` 전체
- 새 경계: 군 전체 없는 런 / 일부 군만 있는 런 / `collectedGroups` 없는 구버전 런 /
  `collectedGroups`에 있으나 값이 안 온 군 / 계약에 없는 임의 군
