# 2026-08-18 — content_map 스키마를 만든다

- Date: 2026-08-18
- Jira: ARTEL-440
- Status: Draft

## Goal

게임에서 뽑아낸 근거(`artel-affordances.json`)를 담을 **씬 명세(content_map)** 의 테이블과 뷰를 만든다.
이 스키마가 TC 생성기의 **유일한 입력**이 되고, QA 런이 그것을 검증해 화면 명세까지 채우는 그릇이 된다.

이번 범위는 **스키마와 매핑까지**다. 적재도 조회 API도 이 이슈가 아니다 — 그것들이 딛고 설 바닥을 만든다.

설계 문서: [content_map — 씬·화면·기능 스키마 설계](https://app.notion.com/p/content_map-3c00bce5474c81639b3ff4e28eae8a59)
에픽: ARTEL-444

## Non-goals

- **적재 로직**(ARTEL-442). 조인·필터·status 판정은 전부 그쪽이다.
- **조회 API**(ARTEL-446), **의사 C# 렌더**(ARTEL-443).
- **판독 수용**(ARTEL-449). `qa_run_reading_index`도 `qa_run_target`도 이번에 만들지 않는다. pulse
  중계(ARTEL-414)가 선행이고, 둘 다 판독을 받는 쪽이 자기 마이그레이션으로 가져간다.
  `qa_run_target`은 처음에 그릇으로 넣었다가 뺐다 — 사유는 아래 "조준 해석표를 왜 뺐나".
  이번에 만드는 런타임 쪽 테이블은 **적재 대상이 아니라 그릇**이다(`screen`,
  `capability_observation`) — 지금 만들어야 후속 이슈가 마이그레이션 없이 코드만 붙인다.
- **기능 간 조인**(`capability_link`, `condition_atom`). 일부러 뺐다. 사유와 재도입 조건은 설계 문서.
- **씬 오브젝트 전량(raw list)**. 기능에 딸리지 않은 오브젝트는 담지 않는다.
- **원본 문서 보관**(`content_map_document`)과 **렌더 캐시**(`content_map_render`). 각각 ARTEL-441/448이
  자기 마이그레이션으로 가져간다 — 이번에 미리 만들면 쓰는 코드 없이 테이블만 뜬다.

## Context / Constraints

### 기준과 선행 사실

기준은 `origin/develop` = `9e5328a`. 착수 전 확인한 것 넷:

1. **마이그레이션 번호는 V39다.** `V36`은 develop에 이미 있고(`align_test_case_with_spec_schema`),
   `V38`(`create_sdk_performance`)까지 찼다. 설계 문서와 Jira 본문이 `V36`이라고 적은 것은 그 시점의
   착각이며, `./scripts/check-flyway-migrations.sh`가 지금은 충돌 없음을 확인해 준다.
2. **이 프로젝트는 R2DBC다.** JPA가 아니다. 엔티티는 `@Table`/`@Id`/`@Column`, JSONB는
   `io.r2dbc.postgresql.codec.Json`, 리포지토리는 `CoroutineCrudRepository`. 연관관계 매핑이 없으므로
   FK는 DB에만 존재하고 엔티티는 `Long` id를 든다.
3. **`qa_run`과 `game_build`가 이미 있다**(V30, V4). 이번 FK가 참조할 대상이 전부 존재한다.
4. **`knowledge`는 건드리지 않는다.** content_map은 knowledge를 대체하지 않는다 — 가변성 모델이 반대다
   (한쪽은 agent가 고치고 지우는 창고, 한쪽은 스캔이 덮어쓰는 관측 기록). `knowledge.source`에 `SPEC`을
   더하는 것은 후속 논의.

### 설계의 축

**문서가 하는 약속은 `evidence_promises`로 받는다.** 근거 문서의 `capabilities` 필드
(`["build-info-v1", "selector-v1", "visual-roles-v1", "persistent-objects-v1"]`)를 담는 자리다.
`schema`가 세대라면 이쪽은 개별 약속이고, 더하기만 한다.

원문 이름을 그대로 쓰지 않는 이유는 **`capability` 테이블과 충돌**하기 때문이다 — 한쪽은 게임의 기능이고
한쪽은 문서의 계약인데 한 글자도 다르지 않다. `evidence_digest`와 짝이 맞는 이름으로 바꾼다.

필드 존재로 계약을 추론하면 안 되기에 이 목록이 따로 있다. 근거 문서 주석이 그 사례를 적어 둔다 —
`build`가 `label`의 뜻이 좁아지기 한 커밋 전에 들어와서, 필드만 보고 판단하면 그 쌍을 틀리게 읽는다.
적재기가 쓸 자리도 여기다: `selector-v1`이 없는 문서에서 `control_selector`를 채우면 안 되고,
`visual-roles-v1`이 없으면 `control_label`을 컨트롤 이름으로 믿으면 안 된다.

**capture를 `content_map` 키에 넣는다.** editor는 저장된 값이고 player는 플레이가 지나간 뒤의 값이라
같은 필드가 다른 뜻이다 — 적의 `label`이 authored `20`인가 남은 체력 `20`인가가 갈린다.

**축이 둘이다.**

| 축 | 값 | 뜻 |
|---|---|---|
| `origin` | evidence / observed / inferred / human | 어디서 왔나 |
| `verification` | unverified / confirmed / contradicted | 실행으로 확인됐나 |

하나로 뭉치면 "IL 분석기가 확신함"과 "돌려봐서 됨"을 구분하지 못한다. QA agent가 플레이하며 배운 기능이
evidence 출신과 같은 통에 들어가는 순간, TC가 근거 없는 것을 근거 있는 것처럼 취급한다.

`capability_evidence.analysis_confidence`(verified/derived/partial)는 **IL 분석기의 자기 확신도**지 실행
확인이 아니다. 이름이 겹쳐 혼동되던 자리라 서브테이블로 내려둔다.

**명세 세 칸의 어휘를 `given`/`when`/`then`으로 통일한다.** agent-server의 TC 스키마가 이미 쓰는 말이라
끝까지 한 단어로 이어진다.

### evidence 출신 컬럼을 왜 서브테이블로 떼나

`entry_id`·`record_kind`·`trigger_kind`·`condition_tree`는 IL 근거를 전제한다. 이것들을 `capability`에
두면 QA가 관측으로 배운 기능이 `NOT NULL`에 막혀 더미값을 넣게 되고, **그 순간 두 종류가 구분
불가능해진다.** `capability_inference`도 같은 이유로 뗀다.

### 액션 프로토콜 어휘를 담지 않는 이유

`button_click`/`enter_text` 같은 메서드명은 SDK의 것이고 배포마다 바뀐다. 판독의 `offers`가 그 오브젝트가
**지금** 무엇에 응답하는지 실어 주므로, 어떤 메서드로 보낼지는 agent가 런타임에 정하는 편이 정확하다.
스키마에는 프로토콜이 바뀌어도 그대로인 **의도**(`interaction`: click/type/press/axis/none)만 담고,
성공한 조작은 `hint_*`에 캐시하되 **권위는 주지 않는다.**

### selector는 조준 키가 아니다

`control_selector`는 실행 간 유지되는 안정 식별자다. 그런데 현재 액션 프로토콜은 `int` instance id를 받고
(`ActionExecutor`의 `button_click params [targetId]`), 그 숫자는 프로세스를 넘지 못한다. 실행 시 해석은
`qa_run_target`이 맡는다 — content_map이 아니라 **런 단위** 표인 이유가 이것이다. 그 표는 이번 범위가
아니다(ARTEL-449).

### 조준 해석표를 왜 뺐나

`qa_run_target`을 그릇으로 미리 만들었다가 제거했다. 셋 다 아직 안 정해졌다.

1. **`scene_name`이 PK 구성원인데 지속 오브젝트에는 씬이 없다.** 근거 문서의 `gaps`가
   `dont-destroy-on-load-not-walked`를 적고 `persistentObjects`가 빈 배열이다. 활성 씬으로 넣을지
   센티널을 쓸지 정하지 않은 채 PK를 굳히면 후속이 PK 마이그레이션을 쓴다.
2. **selector의 안정성 등급이 미정이다.** selector는 형제 인덱스가 붙은 위치 경로라 한 판독 안에서는
   유일하지만, 형제가 생기거나 사라지면 인덱스가 밀린다. 안정 키(selector-v2)를 SDK가 내보내게 되면
   해석표의 키가 통째로 바뀐다.
3. **쓰는 코드가 여기 없다.** 판독이 도착하지 않으므로 머지 후 0행이고, 스펙 원본은 ARTEL-449 본문에
   SQL 그대로 있다. 그쪽에서 만드는 편이 위 둘을 정하고 만드는 것이다.

`capability.control_selector`는 남는다 — 명세가 드는 값이고, 해석표 없이도 사람과 TC 생성기가 읽는다.

## Approach (Checklist)

- [ ] **Step 0: Recon**
  - [x] `check-flyway-migrations.sh`로 번호 확인 → V39
  - [x] R2DBC 엔티티/리포지토리/테스트 관행 확인(`TestCaseEntity`, `TestCaseRepository`, `TestScenarioRepositoryTest`)
  - [ ] `qa_run`/`game_build` 컬럼명 재확인(FK 대상)

- [ ] **Step 1: 마이그레이션** — `src/main/resources/db/migration/V39__create_content_map.sql`
  - 11 테이블: `content_map` · `scene` · `screen` · `capability` · `capability_evidence` ·
    `capability_inference` · `capability_effect` · `capability_observation` · `screen_capability` ·
    `screen_transition` · `scene_edge`
  - 2 뷰: `v_content_map_capability`(TC 입력 창구) · `v_spec_gap`(명세의 어느 칸을 못 채웠나)
  - `IF NOT EXISTS`, `TIMESTAMP WITH TIME ZONE`, CHECK 제약으로 열거값 고정
  - 주석은 **왜**를 적는다 — capture를 키에 넣는 이유, 축이 둘인 이유, 조인을 뺀 이유

- [ ] **Step 2: 엔티티와 리포지토리** — `kr/artel/orchestration/contentmap/`
  - `entity/`: 12개 엔티티 + 열거형(`CaptureKind`, `CapabilityOrigin`, `VerificationState`,
    `Interaction`, `SpecStatus`, `EffectCategory`, `EffectOrigin`, `RecordKind`, `TriggerKind`,
    `AnalysisConfidence`, `EdgeSource`, `TransitionKind`)
  - `repository/`: `CoroutineCrudRepository` + 최소 조회 메서드. **이번엔 CRUD와 키 조회까지**,
    복잡한 질의는 쓰는 이슈가 자기 것을 붙인다
  - `condition_tree`·`gaps`·`discriminator`·`observed_effects`는 `Json`

- [ ] **Step 3: 테스트**
  - 리포지토리 왕복 테스트 — 저장/조회, Auditing 채움, JSONB 왕복
  - **불변식 테스트**: `origin='evidence'`인데 `capability_evidence`가 없으면 읽는 쪽이 알아채는지
  - **CHECK 제약 테스트**: `interaction='press'`인데 `input_key`가 없으면 거절되는지
  - 뷰 조회 테스트: `not-a-step`과 `merged_into`가 걸러지는지, `v_spec_gap`이 사유를 분류하는지

- [ ] **Step 4: 검증**
  - `./scripts/check-flyway-migrations.sh`
  - `./scripts/verify-flyway-upgrade.sh`
  - `./mvnw clean test`

## Validation

- **Commands to run:**
  - `./scripts/check-flyway-migrations.sh` — 다른 브랜치와 번호 충돌 확인
  - `./scripts/verify-flyway-upgrade.sh` — develop 마이그레이션 위에 얹어 `validate`
  - `./mvnw clean test` — 매핑과 SQL 정합
- **Expected output:** 셋 다 exit 0. 신규 리포지토리 테스트 통과.

## Risks & Rollback

- **Risks:**
  - **번호 충돌.** 다른 브랜치가 V39를 먼저 가져가면 머지 후 서버가 뜨지 않는다. 머지 직전 재확인.
  - **테이블 12개가 한 번에 들어온다.** 쓰는 코드는 후속 이슈들이 붙이므로, 머지 직후에는 빈 테이블만
    존재한다. 이것은 의도다 — 후속이 마이그레이션 없이 코드만 붙일 수 있게.
  - **뷰가 굳는다.** `v_spec_gap`의 분류 규칙은 적재(ARTEL-442)가 `gaps`에 무엇을 넣느냐에 달려 있다.
    적재를 짜면서 규칙이 흔들릴 수 있고, 그때는 뷰를 고치는 후속 마이그레이션이 필요하다.
  - **`screen`/`capability_observation`은 이번에 쓰이지 않는다.** 판독 수용(ARTEL-449)
    전까지 0행이다.
- **Rollback steps:** `git revert`. 테이블이 비어 있고 다른 스키마를 건드리지 않으므로 되돌리기가 안전하다
  (기존 테이블 ALTER 없음).

## Open Questions

- `v_spec_gap`의 `indistinguishable`(세 칸이 같은 기능이 둘 이상)은 행 단위가 아니라 집합 판정이라 뷰 한
  줄로 나오지 않는다. 이번엔 빼고 별도 집계 쿼리로 남길지, 뷰를 윈도우 함수로 확장할지.
- `capability.merged_into` 자기참조 FK를 이번에 넣을지. 쓰는 코드(관측이 evidence로 확인되는 경로)는
  ARTEL-451이고, 컬럼만 미리 두는 것과 같은 판단이라 넣는 쪽으로 잡았다.
