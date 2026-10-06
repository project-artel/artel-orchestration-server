# 2026-10-06 — macro 저장 schema 와 macro 를 적고 읽는 frame 둘

- Date: 2026-10-06
- Jira: ARTEL-919, ARTEL-921
- Status: Reviewed

## Goal

`artel-orchestration-server` 가 QA agent 가 등록한 macro 를 저장하고 다시 돌려준다.

1. **ARTEL-919** — `content_map` 에 매다는 `macro` 표와, macro 와 `screen` 을 잇는 `screen_macro`
   관계 표를 migration 하나로 만든다.
2. **ARTEL-921** — macro 정의를 적는 frame 하나와 읽는 frame 하나, 그 응답 frame 둘,
   `QaAgentInboundRouter` 라우팅.

## Non-goals

- `ContentMapMode.FROZEN` 이 macro 쓰기를 막는 것 — `ARTEL-922`. **게이트를 부를 자리만 비워 둔다.**
- `run_macro` 의 batch 실행과 `require` 판정 — `artel-agent-server` 의 `macro-runner`.
- macro 에러 코드 집계 — `ARTEL-924`.
- scene context 응답에 macro 를 싣는 것 — `ARTEL-934`.
- 등록된 macro 목록 화면 — `ARTEL-941`.
- 관계를 빼는 길. v1 에 없다. 더하기만 한다.
- macro 가 macro 를 부르는 것. v1 에 없다.
- `docs/` 에 새 문서를 두는 것. `ARTEL-921` 의 Validation Notes 가 요구하는 것은 **PR 본문의 표**다.
  계약은 `MacroWriteFrames.kt` 의 KDoc 이 진다.

## Context / Constraints

### migration 번호는 V98 이다

이슈 본문은 `V93` 이라 적었지만 `develop` 에 `V93` · `V94` · `V95` 가 이미 있다. 그 다음인
`V96` 과 `V97` 도 **다른 branch 가 이미 집었다**:

- `V96__ask_what_to_call_a_new_screen.sql` — ARTEL-910, PR 280 (draft 아님, `develop` 를 향함)
- `V97__create_local_credential_and_platform_setting.sql` — PR 285 (draft)

working tree 만 보면 둘 다 안 보인다. `check-flyway-migrations.sh` 가 remote branch 를 함께 보고
잡아 준 것이고, 그래서 이 작업의 번호는 **V98** 이다. 앞의 둘 중 하나가 먼저 merge 되면 그쪽이
이기므로 번호를 비켜 주는 쪽이 맞다.

### 표를 둘로 나누고 statement 를 행으로 나누지 않는다

`if` 가 statement 를 중첩시켜서 평평한 표로는 몸통이 어디서 끝나는지 담지 못한다. JSON 이 질의
대상이 아니라서 tree 를 SQL 에 모델링할 이유도 없다. 텍스트가 원본이고 JSON 은 파생이다.

### 등록 갱신은 제자리에서 일어나야 한다

지우고 새로 넣으면 `ON DELETE CASCADE` 로 `screen_macro` 행이 같이 사라진다. 그것은 agent 가
공들여 단 것이다. 갱신은 SQL 한 문장이다:

```sql
INSERT INTO macro (content_map_id, name, source, definition_json, parameter_names)
VALUES (...)
ON CONFLICT (content_map_id, name) DO UPDATE SET
    source = EXCLUDED.source,
    definition_json = EXCLUDED.definition_json,
    parameter_names = EXCLUDED.parameter_names,
    updated_at = CURRENT_TIMESTAMP
RETURNING id
```

조회 후 분기가 아니다. **한 문장이라 행의 `id` 가 바뀌지 않고, 경합도 없다** — 그래서
`AgentCapabilityWriteService` 가 쓰는 "트랜잭션 밖에서 다시 조회해 복구" 를 베끼지 않는다.

### 멱등은 DB 유니크 index 가 진다

앱 레벨 `if` 로 막지 않는다. 같은 이름이 동시에 둘 오면 조회와 INSERT 사이로 빠져나간다.
`screen_macro` 도 같다 — `INSERT ... ON CONFLICT DO NOTHING` 이고 `if (없으면) insert` 가 아니다.

### frame 은 `docs/capability-write-frames.md` 의 모양을 그대로 쓴다

- 같은 envelope: `messageId`(UUID) · `type` · `qaTryId` · `correlationId` · `timestamp` · `payload`
- 거절은 **새 frame type 이 아니라** 기존 `ERROR` frame 에 `correlationId` 를 실어 답한다
- router guard 둘이 앞에 서서, 비 UUID `messageId` 와 모르는 / 끝난 `qaTryId` 를 답 없이 떨어뜨린다
- 모든 id 는 요청과 응답 양쪽에서 **JSON 문자열**이다
- `CapabilityWriteFrames` 처럼 frame type 문자열 상수를 담는 **object** 와 payload data class

### 거절은 값으로 돌아온다. DB 오류가 agent 에게 가지 않는다

`routeCapabilityWrite` 가 예외를 삼켜 `ERROR` 로 바꾸는 이유는 예외가 receive 체인 밖으로 나가면
WebSocket 이 닫혀 런 전체가 실패하기 때문이다. 그러므로 **DB 제약으로 떨어질 입력은 서비스가 먼저
Kotlin 에서 걸러 사유를 문장으로 돌려준다.** 제약 위반 메시지는 제약 이름만 실려 agent 가 무엇을
고쳐야 하는지 읽을 수 없다. DB 제약은 backstop 이다 — 이 서비스가 유일한 writer 가 아닐 때를 위한.

`AgentCapabilityWriteService` 가 `ck_capability_press_needs_key` 를 Kotlin 에서 한 번 더 보는 것이
같은 판단이고, 그 주석이 이유를 적어 두었다.

### 열려 있던 것을 이 PR 이 정한다

- **JSON tree 의 모양** — `ARTEL-918`(할 일)이 확정한다. 이 PR 의 CHECK 는 `require` node 의
  `kind` 와 `remedy` 두 key 에만 기댄다. `statements` · `body` · `orelse` 같은 나머지 key 이름에는
  기대지 않는다.
- **`screen` 을 지목하는 식별자의 모양** — `ARTEL-919` · `ARTEL-925` 가 "함께 정한다" 로 미뤄 둔
  것이다. 이 PR 은 **새 모양을 만들지 않고** `CAPABILITY_VERDICT.screen_id` 가 이미 쓰는 규약을
  그대로 쓴다: `screen.id` 를 JSON 문자열로 싣는다.

## 정해 둔 모양

### frame type 넷

```
AGENT_TO_ORCHE  MACRO_REGISTER      등록된 macro 정의를 적는다
AGENT_TO_ORCHE  MACRO_READ          저장된 정의를 이름으로 읽는다
ORCHE_TO_AGENT  MACRO_WRITE_RESULT  MACRO_REGISTER 의 답
ORCHE_TO_AGENT  MACRO_READ_RESULT   MACRO_READ 의 답
ORCHE_TO_AGENT  ERROR               둘 다에 대한 거절. correlationId 가 요청을 문다
```

`MacroWriteFrames.INBOUND = setOf(MACRO_REGISTER, MACRO_READ)`.

응답 type 을 둘로 가른다. 쓰기 응답은 id 를 싣고 읽기 응답은 source 와 JSON tree 를 싣는다 — 모양이
다르다. `KNOWLEDGE_WRITE_RESULT` 와 `KNOWLEDGE_SEARCH_RESULT` 가 갈린 것과 같은 판단이고,
`CAPABILITY_WRITE_RESULT` 가 둘을 겸한 것은 그 둘이 **쓰기 한 가족**이었기 때문이다. 쓰기 쪽을
하나로 두어 다음 macro 쓰기 type 이 계약을 물려받는다.

### `MACRO_REGISTER` payload

| 필드 | 규칙 |
|---|---|
| `name` | 필수. 진입점 `def` 의 이름. 200자 이하 |
| `source` | 필수. 원본 텍스트. 20,000자 이하 |
| `definition` | 필수. JSON tree. 직렬화 200,000자 이하 |
| `parameters` | 선택. 진입점 `def` 줄의 parameter 이름, **순서 있게**. 없으면 `[]` |
| `screens` | 선택. `screen.id` 를 JSON 문자열로 담은 배열. 기존 관계에 **더한다** |

### `MACRO_WRITE_RESULT` payload

| 필드 | 뜻 |
|---|---|
| `type` | `MACRO_REGISTER` — 무엇의 답인지 |
| `macro_id` | 문자열. 등록 갱신이어도 **같은 값**이다 |
| `name` | 등록된 이름 |
| `created` | 행을 새로 넣었나. 갱신이면 `false` |
| `screen_ids` | 이 macro 에 달린 `screen` 전부, 문자열 배열. 더한 뒤의 상태 |

### `MACRO_READ` payload / `MACRO_READ_RESULT` payload

요청은 `{"name": "..."}` 하나. 응답은 `macro_id` · `name` · `source` · `definition` ·
`parameters` · `screen_ids`.

### `MacroWrite` 결과 타입

`CapabilityWrite` 와 같은 모양이다. `Rejected(reason)` 와, 쓰기용 `Registered` · 읽기용 `Loaded`.

### 거절 문장

전부 요청 타입을 앞머리에 단다 — `AgentCapabilityWriteService.message()` 와 같다.

```
MACRO_REGISTER payload.name is required
MACRO_REGISTER payload.name is longer than 200 characters
MACRO_REGISTER payload.source is required
MACRO_REGISTER payload.definition is required
MACRO_REGISTER payload.definition must be a JSON object
MACRO_REGISTER payload.parameters must be a list of names: <value>
MACRO_REGISTER payload.screens must be numeric screen ids: <value>
MACRO_REGISTER references screens outside this build's content map: <ids>
MACRO_REGISTER require statements must each carry a remedy — a failed require
  that names no remedy leaves the agent nothing to do next
MACRO_READ payload.name is required
MACRO_READ references an unknown macro: <name>
<TYPE> failed: <message>
```

`references an unknown macro` 는 **이 build 의 `content_map` 안에서** 못 찾았다는 뜻이다. 이름의
유일 범위가 `content_map_id` 안이므로 다른 build 의 같은 이름은 찾지 않는다 — 존재하지 않는
`scene` 을 묻는 지금의 거부 문장과 같은 모양이다.

### router 안에서의 자리

`QaAgentInboundRouter.handle` 의 capability 분기(`:197`) **바로 뒤**에 자기 분기를 둔다. 표시용
`message` 가 없는 payload 라 아래 non-blank 가드보다 앞서야 하고, 그것이 capability · knowledge ·
screen selector 분기가 모두 그 위에 있는 이유다.

**`allowContentMapWrite` 를 부르지 않는다.** 그 자리에 `ARTEL-922` 가 들어올 것을 적어 둔다.

## Approach (Checklist)

- [x] **Step 0: Recon** — `docs/capability-write-frames.md`, `CapabilityWriteFrames.kt`,
      `AgentCapabilityWriteService.kt`, `QaAgentInboundRouter.kt`(`:111` `SUPPORTED_TYPES`,
      `:155` guard 둘, `:197` capability 분기, `:1033` `answerWithError`), `V40` `screen_capability`,
      `V63` content_map

- [x] **Step 1: ARTEL-919 — migration V98** (작성 완료, probe DB 에서 12 경로 확인)
  - `V98__store_registered_macros_on_the_content_map.sql`
    - `macro` — `id` · `content_map_id`(FK CASCADE) · `name` · `source` · `definition_json` ·
      `parameter_names` · `created_at` · `updated_at`
    - `uk_macro_name (content_map_id, name)` — 유일 범위이자 갱신의 충돌 대상. 선두 컬럼이
      `content_map_id` 라 build 단위 목록 조회도 이 index 가 받는다(별도 index 없음)
    - `ck_macro_require_carries_remedy` — `jsonb_path_exists` 가 `$.**` 로 tree 전체를 훑어
      `remedy` 가 없거나 비거나 문자열이 아닌 `require` 를 거절
    - `ck_macro_parameter_names_array`
    - `screen_macro` — PK `(screen_id, macro_id)`, 양쪽 FK `ON DELETE CASCADE`
    - `idx_screen_macro_macro` — macro 쪽 조회. `screen` 쪽은 PK 선두 컬럼이 받는다
  - `contentmap/entity/MacroEntity.kt` · `ScreenMacroEntity.kt`
  - `contentmap/repository/MacroRepositories.kt`
    - `MacroRepository.upsertByName(...)` — 위 SQL. `RETURNING id`
    - `MacroRepository.findByContentMapIdAndName(contentMapId, name)`
    - `ScreenMacroRepository.link(screenId, macroId)` — `ON CONFLICT DO NOTHING`
    - `ScreenMacroRepository.findScreenIdsByMacroId(macroId)`
  - `ScreenRepository.findIdsInContentMap(contentMapId, screenIds)` — `screens` 검증용
  - **`findByContentMapId` 는 두지 않는다.** 이 PR 에 호출부가 없다(`ARTEL-934` · `941` 이 non-goal)
  - commit 하나

- [ ] **Step 2: ARTEL-921 — frame 둘과 라우팅**
  - `contentmap/macro/MacroWriteFrames.kt` — 위 "정해 둔 모양" 그대로. 상한 상수 셋:
    `MAX_NAME_LENGTH = 200` · `MAX_SOURCE_LENGTH = 20_000` · `MAX_DEFINITION_LENGTH = 200_000`
  - `contentmap/macro/MacroDefinitionService.kt` — `register()` · `read()`
    - content_map 해석만 베낀다: `qaTry` → `gameInstances.findById` → `lastGameBuildId` →
      `contentMaps.findByGameBuildId`. **`scene` 해석과 `qaRunId` 요구는 베끼지 않는다** — macro 는
      `content_map` 에 매달리고 scene 경계를 넘는다
    - **`remedy` 없는 `require` 를 Kotlin 에서 먼저 거른다.** DB CHECK 는 backstop 이다
    - 쓰기 하나가 한 트랜잭션이다. macro 행 upsert 와 `screen_macro` 더하기가 같은 트랜잭션
  - `QaAgentInboundRouter` — `SUPPORTED_TYPES` 에 `MacroWriteFrames.INBOUND` 를 더하고
    `routeMacro()` 분기를 capability 분기 뒤에 둔다
  - commit 하나

- [ ] **Step 3: Tests** — `MacroDefinitionWriteTest.kt`.
      `AgentCapabilityWriteTest` 의 `RecordingAgentPort` · `newWorld` · `deliver` 모양을 따른다
  - 정상 쓰기 · 정상 읽기 · 거부 — `ARTEL-921` 이 요구하는 세 경로
  - 재등록이 제자리에서 일어나 `macro_id` 가 그대로이고 `screen_macro` 행이 남고 `updated_at` 이 오른다
  - `screens` 를 주면 기존 관계에 더해지고 기존 것이 안 지워진다
  - `remedy` 없는 `require` 를 서비스가 사유와 함께 거절한다(ERROR frame, DB 오류가 아니다)
  - **repository 를 직접 찔러** `remedy` 없는 tree 가 DB CHECK 에 막히고 멀쩡한 tree 는 들어가는지
    본다. 서비스를 지나지 않는 경로다 — 이것이 jsonpath 와 `"^\\s*$"` 이스케이프가 Flyway 와
    R2DBC 를 지나서도 작동함을 증명하는 유일한 테스트다
  - 모르는 이름을 읽으면 `references an unknown macro: <name>` 으로 거절된다
  - 비 UUID `messageId` 와 끝난 `qaTryId` 는 답 없이 떨어진다
  - 응답의 id 가 JSON 문자열이다(한 군데에서 확인)

- [ ] **Step 4: Rollout / Rollback** — feature flag 없음. 새 표 둘과 새 frame type 둘뿐이라 기존
      경로가 바뀌지 않는다. rollback 은 `git revert` + `DROP TABLE screen_macro, macro`

## Validation

- **Commands to run:**
  - `./scripts/check-flyway-migrations.sh` — 번호 충돌
  - `./scripts/verify-flyway-upgrade.sh` — `develop` 위에 V98 을 얹고 `validate`
  - `./mvnw test -Dtest=MacroDefinitionWriteTest`
  - `./mvnw test -Dtest=AgentCapabilityWriteTest` — router 를 건드렸으므로 회귀 확인
- **Expected output:** 앞 둘 exit 0, 테스트 둘 통과
- **주의:** 전체 suite 는 이 branch 이전부터 100개쯤 깨져 있다. Kotlin daemon 이 못 떠서 in-process
  로 돌려야 한다. 내가 깬 것과 원래 깨져 있던 것을 가려서 보고한다

## Risks & Rollback

- **Risks:**
  - `ContentMapMode.FROZEN` 이 macro 쓰기를 아직 막지 않는다. `ARTEL-922` 가 붙기 전까지
    `frozen` arm 이 macro 를 쓸 수 있고, 그것이 같은 build 를 쓰는 다른 arm 의 출발점을 옮긴다.
    **의도된 미완성이고 `ARTEL-921` 이 그렇게 지시한다.** PR 본문에 적는다
  - **CHECK 가 fail-open 한다.** `ARTEL-918` 이 node 의 `kind` 를 다른 key 로 정하거나 `remedy` 를
    다른 이름으로 바꾸면 `NOT jsonb_path_exists(...)` 가 어떤 tree 에도 참이 되어 CHECK 가 아무것도
    막지 않는다. 그것은 조용한 실패다. 막는 것 둘: (1) Step 3 의 repository 직통 테스트가
    음성·양성 대조를 함께 둔다 — key 가 달라지면 양성 쪽이 아니라 **음성 쪽이 깨져** 눈에 띈다,
    (2) `ARTEL-918` 에 이 CHECK 를 다시 보라는 코멘트를 남긴다. 계획 파일의 주석으로는 안 버틴다
  - `screen` 식별자를 `screen.id` 문자열로 정한 것은 `ARTEL-925` 와 합의한 값이 아니라 이 PR 의
    결정이다. 기존 frame 의 규약을 그대로 쓴 것이라 새 모양은 아니다. PR 본문에 적는다
- **Rollback steps:** `git revert` 뒤 `DROP TABLE screen_macro, macro;`

## Rejected feedback

- **`parameter_names` 칸을 지워라** (medium #4) — 거절. `ARTEL-919` 의 인수 조건이 명시적으로
  요구한다: "`def` 줄에서 읽은 parameter 이름을 순서 있게 저장한다. `run_macro` 가 실행 시점에
  인자를 위치로 대응시킬 수 있어야 한다." `definition_json` 에서 파생되는 것은 맞지만, 이슈가
  칸으로 요구한 것을 계획이 지울 수는 없다. 대신 주석이 자기 모순이던 것은 고쳤다 — 일부러 둔
  사본이고 원본은 tree 쪽이라고 적는다.

## Open Questions

없다. 1차 검토에서 열려 있던 frame type 값 · payload 모양 · router 안의 자리 · 응답 type 을 둘로
가르는 판단은 모두 위 "정해 둔 모양" 에서 확정했다.
