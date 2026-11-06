# 2026-08-05 — QA 런 실행 설정을 qa_try에 기록한다

- Date: 2026-08-05
- GitHub Issue: None
- Jira: ARTEL-239 (Epic ARTEL-12 [Backend] Orchestration 서버 개발) — ARTEL-238에 blocked
- Branch: `feat/qa-런-실행-설정을-qa_try에-기록-ARTEL-239`
- Status: Done

## Goal

QA 런을 **모델 / 에이전트 구조 / 프롬프트 버전** 축으로 집계·비교할 수 있게, 실행 설정을
요청 시 Agent에 전달하고 Agent가 돌려준 **해석된 설정**을 `qa_try`에 저장한다.

짝이 되는 Agent 쪽 계획: artel-agent-server `.plan/general/2026-08-05-qa-run-config-and-arch-versioning.md`
(계약 정의는 그쪽이 원본).

## Non-goals

- 결과 metric 집계·리포트 화면. ARTEL-233(LLM 사용량 기록 테이블)이 수치를 대고, 이 계획은
  그 수치를 묶을 축을 댄다. 조인·집계 쿼리는 후속.
- 실험 프리셋 관리(설정 조합을 이름으로 저장/재사용). 지금은 요청마다 명시.
- `test_run` 계층과의 연결. V17 주석대로 `qa_try`는 그 계층과 무관하게 유지되고, 실행 기록은
  여전히 `qa_try`다.

## Context / Constraints

기준 `origin/develop` = `3098a32`. `V24`는 ARTEL-233(`create_llm_usage`)이 가져갔으므로 신규는 **V25**.

현재 상태. `develop`은 이미 `model`과 `reasoning`을 끝까지 흘리고 있다 — 이 계획의 최초 초안은
오래된 브랜치(`fix/...ARTEL-134`)를 읽고 쓴 것이라 그 부분이 틀렸다. 남은 구멍은 세 축과 회수뿐이다.

| 위치 | 지금 |
|---|---|
| `CreateQaTryRequest` | `model` / `reasoning` 있음. `language` / `promptVersion` / `arch` 없음 |
| `QaAgentSessionContext` | `model` / `reasoning` 있음. 나머지 세 축 없음 |
| `QaSessionOpenRequest` | `model` / `reasoning` 전송. `@JsonInclude(NON_NULL)`이라 미지정은 아예 안 나감 |
| `QaSessionOpenResponse` | `session_id`만 읽는다. **Agent가 확정한 설정을 받을 자리가 없다** |
| `QaAgentSession` | `sessionId`만 |
| `QaTryEntity` | 설정 컬럼 없음 |
| `QaTryResponse` | 설정 노출 없음 |

`artel.agent.model` 프로퍼티는 `develop`에 존재하지 않는다(모델은 요청에서 온다). 초안이 지적한
"앱 전역 단일 모델"과 "기본 슬러그가 카탈로그에 없다"는 둘 다 stale 브랜치를 본 오독이다.

레포 규칙: 오류는 `common/error`의 타입 예외로 던진다(`.agents/docs/error-handling.md`).
신규 `ResponseStatusException` 금지 — `QaTryService`는 이미 `NotFoundException` /
`ConflictException` / `UpstreamUnavailableException`을 쓴다.

## Approach (Checklist)

- [x] **Step 0: Recon** — 완료. 대상: `db/migration/V24__*.sql`, `qa/entity/QaEntities.kt`,
      `qa/dto/QaDtos.kt`, `qa/repository/QaRepositories.kt`, `qa/service/QaAgentPort.kt`,
      `qa/service/WebSocketQaAgentAdapter.kt`, `qa/service/QaTryService.kt`,
      `qa/controller/QaTryController.kt`.

- [x] **Step 1a: `V25__add_qa_try_run_config.sql`**

  ```sql
  ALTER TABLE qa_try
      ADD COLUMN model             VARCHAR(100),
      ADD COLUMN reasoning_effort  VARCHAR(20),
      ADD COLUMN prompt_version    VARCHAR(20),
      ADD COLUMN agent_arch        VARCHAR(50),
      ADD COLUMN agent_fingerprint VARCHAR(20),
      ADD COLUMN run_config        JSONB NOT NULL DEFAULT '{}'::jsonb;

  CREATE INDEX IF NOT EXISTS idx_qa_try_config
      ON qa_try (model, prompt_version, agent_arch, reasoning_effort);
  ```

  **비교 축 4개만 컬럼으로 승격**하고 나머지 해석값 전부는 `run_config` JSONB에 스냅샷으로 둔다.
  집계 쿼리는 승격 컬럼만 쓰므로 인덱스가 산다. 축을 늘리고 싶으면 JSONB에서 꺼내 승격한다.
  전부 nullable — 기존 행과 Agent가 `run_config`를 안 주는 구버전 응답을 수용해야 한다.

  `reasoning_effort`가 NULL인 두 경우("미지정" vs "모델 미지원")는 `run_config.reasoning_supported`가
  구분한다. 컬럼은 GROUP BY용, JSONB가 진실.

- [x] **Step 1b: 엔티티/리포지토리** — `QaTryEntity`에 위 컬럼 추가(`Json` 타입은 `qa_log.payload`
      선례를 따른다). 리포지토리에 설정 반영용 업데이트 경로 추가.

- [x] **Step 1c: DTO** — `CreateQaTryRequest`에 `language` / `promptVersion` / `arch` 추가
      (전부 optional). `model` / `reasoning`은 이미 있으므로 건드리지 않는다.
      `QaTryResponse`에 `model` / `promptVersion` / `agentArch` / `agentFingerprint` / `runConfig` 노출.

- [x] **Step 1d: 포트 확장**
  - `QaAgentSessionContext`에 `language` / `promptVersion` / `arch` 추가.
  - `QaAgentSession(sessionId, runConfig: JsonNode?)` — Agent가 돌려준 해석값을 버리지 않는다.
    이게 이 계획의 핵심이다. 저장하는 건 요청값이 아니라 **실제 쓰인 값**이어야 한다.

- [x] **Step 1e: `WebSocketQaAgentAdapter`**
  - `QaSessionOpenRequest`에 `language` / `prompt_version` / `arch` 추가
    (`@JsonProperty` snake_case, 기존 `qa_try_id` 관례 그대로). `@JsonInclude(NON_NULL)`이
    이미 붙어 있어 미지정 축은 나가지 않고, Agent 기본값이 그대로 쓰인다.
  - `QaSessionOpenResponse`에 `@JsonProperty("run_config") val runConfig: JsonNode?` 추가.

- [x] **Step 1f: `QaTryService`**
  - `QaAgentSessionContext` 생성 시 요청 설정을 실어 보낸다 (`QaTryService.kt:115`).
  - `attachAndMarkRunning`에서 `agentSessionId`와 함께 `runConfig`를 반영한다 —
    **한 번의 쓰기로.** 별도 UPDATE를 붙이면 세션은 붙었는데 설정은 비어 있는 창이 생긴다.
  - `run_config`가 null인 응답(구버전 Agent)은 컬럼을 비운 채 진행한다. 런을 죽이지 않는다.

- [x] **Step 2: Tests**
  - 마이그레이션 적용 후 기존 행이 살아 있고 새 컬럼이 NULL / `{}`일 것.
  - `QaTryService` 테스트: 요청 설정이 `QaAgentSessionContext`로 전달될 것,
    Agent 응답의 `run_config`가 `qa_try`에 반영될 것, `run_config` 누락 시에도 RUNNING 도달할 것.
  - 어댑터 직렬화 테스트: snake_case 필드명이 Agent 계약과 일치할 것.
  - `insomnia-sync` 스킬로 `orchestration-server.yaml` 갱신.

- [x] **Step 3: Rollout**
  - 배포 순서: **Agent 먼저.** Orchestration이 `arch`를 보내는데 Agent가 모르면
    (pydantic `extra` 기본 ignore라) 조용히 무시돼 잘못된 기록이 남는다.
    Agent를 먼저 올리면 Orchestration 구버전이 아무것도 안 보내도 현재 동작이다.
  - 롤백: 컬럼은 전부 nullable이라 코드만 revert 해도 스키마가 무해하게 남는다.
    마이그레이션 되돌림 불필요.

## Validation

- **Commands to run:** `./mvnw -o test` (Testcontainers로 pgvector/redis를 띄운다),
  로컬 기동 후 `POST /qa-tries`에 설정 넣고 호출 → `qa_try` 행의 승격 컬럼 + `run_config` 확인.
- **Expected output:** 설정 없이 만든 런도 정상 RUNNING 도달(컬럼 NULL). 설정 넣은 런은
  Agent가 해석한 값이 컬럼과 JSONB에 모두 기록.

## Risks & Rollback

- **Risks:**
  - `QaAgentPort` 시그니처 변경이 구현체·테스트 더블을 건드린다.
  - Agent 응답 계약에 의존한다. Agent 쪽 `run_config` 키 이름이 바뀌면 조용히 NULL이 쌓인다 —
    어댑터 테스트에 키 이름을 고정한다.
- **Rollback steps:** `git revert`. V25는 nullable-only ALTER라 남겨도 무해.

## Open Questions

- 실행 설정을 `CreateQaTryRequest`로 열면 임의 사용자가 모델을 고를 수 있게 된다. 실험 목적상
  맞지만, 권한 제한(운영자만)이 필요한지 확인 필요.
- `run_config` 스냅샷을 `qa_try`에 인라인할지 별도 테이블로 뺄지. 지금은 인라인 — 런당 1행,
  수 KB 미만. 프리셋 재사용이 생기면 그때 정규화.
