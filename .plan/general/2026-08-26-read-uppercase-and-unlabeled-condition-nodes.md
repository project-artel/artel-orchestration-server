# 2026-08-26 — 조건의 대문자와 이름표 없는 노드를 읽는다

- Date: 2026-08-26
- Jira: ARTEL-495
- Status: Draft

## Goal

`EvidenceParser.toCondition()` 이 실측 문서의 조건을 조용히 삼키는 두 경로를 막는다.

1. `kind` 를 대소문자 무시로 읽는다 — `"EVERY"` 가 `every` 와 같은 것이 된다.
2. `kind` 가 없는 노드를 모양으로 읽는다 — 지금은 전부 `Always`("항상 참")로 떨어져
   진짜 조건이 통째로 사라진다.
3. 조건이 객체가 아닐 때(문자열·숫자·배열)도 `Always` 로 떨어지는 셋째 경로를 함께
   막는다. 실측 0건이지만 2번과 **같은 코드 줄**이 삼키는 것이라 따로 고칠 수 없다 —
   `!isObject` 를 모양 추론 앞에 세우지 않으면 "필드가 하나도 없음"이 문자열 조건을
   먼저 `Always` 로 만든다.

"조건이 없다"와 "우리가 못 읽었다"가 같은 모양이 되지 않게 하는 것이 요점이다.

## Non-goals

- **쓰는 쪽을 고치는 것.** 대문자 `EVERY` 를 실제로 만드는 곳은 SDK 가 아니라
  `ContentMapIngestService.conditionJsonOf()` 다(아래 `### 뿌리` 참고). 이슈 제약이
  "이 이슈는 읽는 쪽만 고친다"라고 못박았으므로 이 PR 은 쓰는 쪽을 건드리지 않는다.
  후속 이슈로 올린다.
- 조건을 사람 문장으로 만드는 것.
- `conditionJson` 의 동작을 바꾸는 것. 원본을 그대로 담는 것은 우리 모델이 못 담은 키가
  조용히 사라지지 않게 하려는 결정이다.

## Context / Constraints

### 실측 (2026-08-26, 로컬 스택 `capability_evidence` 465행)

`condition_tree` 를 전부 펴서 노드 종류를 센 결과:

```
test 865 · every 269 · always 141 · either 62 · gesture 19 · unknown 8
EVERY 12 · (kind 없음) 28
```

`kind` 없는 28개의 키 조합은 두 가지뿐이다:

```
20 × (context, left, offset, operator, right, subjectLost)   → test 모양
 8 × (input, offset)                                         → gesture 모양
```

**`parts` 를 들고 `kind` 가 없는 노드는 465행 어디에도 없다.** 이것이 아래 설계 판단의
근거다.

영향받는 행은 465행 중 **8행**이고 전부 `Map.MapMove` 다. 트리 8개가 대문자 `EVERY`
12개와 이름표 없는 노드 28개를 모두 들고 있다.

### 저장소 픽스처에는 이 모양이 없다

- `src/test/resources/contentmap/wv-editor-play-schema7.json` (schema 7)
- `src/test/resources/contentmap/wv-editor-latest.json` (schema 6 골든)

둘 다 조건 노드 분포가 **완전히 같고**(`test 855 · every 276 · always 151 · either 68 ·
gesture 25 · unknown 6`, 총 1381개), 대문자 `kind` 도 이름표 없는 노드도 **0건**이다.

따라서 **두 픽스처의 전후 수는 바뀌지 않는다.** 그 불변이 이 변경의 안전 증거이고,
버그가 고쳐졌다는 증거는 DB 에서 그대로 꺼낸 트리 8개가 든다.

### 뿌리 — 그 대문자는 SDK 가 아니라 우리가 썼다

이슈 본문은 대문자 `EVERY` 를 SDK 가 낸 것으로 적었다. **아니다.** 그 8행은
`src/test/resources/contentmap/wv-editor-play-schema7.json` — 저장소에 이미 있는 그
문서 — 를 적재한 결과이고, 대문자를 쓴 것은 우리 코드다:

`ContentMapIngestService.conditionJsonOf()` 는 입력을 가르는 `either` 를 쪼갠 갈래에만
원문 대신 타입 트리를 직렬화해 싣는다(`objectMapper.writeValueAsString(candidate.condition)`).
Jackson 기본 직렬화가 이렇게 쓴다:

- `GroupKind` 에 `@JsonValue` 가 없어 enum 이 **이름 그대로** `"EVERY"` 로 나간다.
- `ConditionNode.Test` · `Gesture` 에는 `kind` 필드가 없어 **이름표 없이** 나간다.

재현은 한 번에 확인된다. 그 픽스처를 파싱해 `ConditionBranches.splitOnInput` 을 돌리고
쪼개진 갈래만 Jackson 규칙대로 직렬화하면 **DB 의 8행과 정확히 같은 집합**이 나온다
(값·개수 모두 일치). 갈래가 8개인 것도 우연이 아니다 — `ConditionBranches` KDoc 이
"갈래마다 다른 gesture 를 든 `either` 는 실측 4건뿐이고 전부 `Map.MapMove::CharacterMove`"
라고 적어 둔 바로 그 4건이 쪼개진 결과다.

키 조합이 뒷받침한다. 이름표 없는 test 20개는 **전부** `subjectLost` 키를 들고 있다
(값은 null). SDK 가 낸 test 855개 중 `subjectLost` 를 든 것은 47개뿐이다 — 데이터
클래스 필드를 빠짐없이 쓰는 우리 직렬화의 지문이다.

이 사실이 계획에 미치는 것:

- **AC #5("재적재하면 이미 앉은 행의 조건이 되살아난다")는 이 변경만으로는 일어나지
  않는다.** `EvidenceParser` 의 프로덕션 호출자는 `ContentMapIngestService` 하나뿐이고,
  그것이 읽는 것은 S3 의 SDK 문서다. `capability_evidence.condition_tree` 는 다시
  파싱되지 않는다. 재적재는 소문자·이름표 있는 원문을 다시 읽으므로 그 8행의
  `CapabilityKey` 도 바뀌지 않는다. PR 본문에 그대로 적는다.
- 대신 이 변경이 실제로 닫는 것은 **왕복**이다. 우리가 쓴 `condition_tree` 를 우리
  파서가 읽을 수 있게 된다. 지금은 우리가 쓴 것을 우리가 못 읽고, 못 읽은 것이
  `Always` 로 둔갑한다.
- `parts` 를 들고 이름표가 없는 노드가 0건인 것은 **표본이 적어서가 아니라 구조적으로
  불가능**해서다. SDK 는 그룹에 소문자 `kind` 를 쓰고, 우리 직렬화는 대문자 `kind` 를
  쓴다. 두 생산자 어느 쪽도 이름표 없는 그룹을 만들지 않는다.

### 관대함의 한계선

- 모르는 것을 `Always` 로 만들지 않는다. 그게 지금 버그다.
- 판단이 안 서면 `Unknown` 이다. `Unknown` 은 `CapabilityKey.canonical` 에서 `always` 와
  다른 키를 갖고, `ConditionBranches.conjunctiveTests()` 는 그것을 근거로 쓰지 않는다 —
  즉 "못 읽었다"가 값으로 남는다.
- 정말 비어 있는 노드(필드가 아무것도 없음)와 `kind:"always"` 만 `Always` 다.

## Approach (Checklist)

- [ ] **Step 0: Recon** — `evidence/EvidenceParser.kt`, `evidence/EvidenceModel.kt`,
      소비자 `ingest/CapabilityKey.kt` · `join/ConditionBranches.kt` 를 읽고, DB 실측으로
      노드 모양을 확정한다. (완료 — 위 실측)

- [ ] **Step 1: Implementation** — `contentmap/evidence/EvidenceParser.kt` 한 파일.
  `toCondition()` 의 판정 순서를 위에서 아래로 못 박는다:

  1. `isMissingNode || isNull` → `Always`. 지금과 같다. `condition` 필드가 아예 없는
     레코드는 실측 0건이고, 이 이슈가 요구한 변경이 아니다.
  2. `!isObject` (문자열·숫자·배열) → `Unknown(reason="condition-not-an-object")`.
     **모양 추론보다 먼저다** — 객체가 아니면 `path("parts")` 도 `hasNonNull("left")` 도
     전부 조용히 false 라, 뒤에 두면 그대로 `Always` 로 떨어진다. 지금 코드가 정확히
     그렇게 한다.
  3. `kind` 를 읽어 **여기 한 곳에서** 정규화한다: `path("kind").asTextOrNull()` 직후에
     `trim()` → `lowercase()` → 빈 문자열이면 null. 이 정규화를 거친 값만 아래 `when` 과
     `GroupKind.from` 으로 들어가므로 `GroupKind.from` 은 손대지 않는다(이미 소문자만
     받는다). 정책이 두 곳으로 갈라지지 않게 하려는 것이다.
  4. 정규화한 `kind` 로 갈린다: `"always"` → `Always`, `"test"` → `Test`,
     `"gesture"` → `Gesture`, `GroupKind.from` 이 아는 것 → `Group`, 그 밖의 **아는
     이름이 아닌 이름표** → 지금처럼 `Unknown(reason = reason ?: kind)`. 이름표가 있는데
     우리가 모르는 것은 모양으로 다시 추측하지 않는다 — 문서가 자기가 무엇인지 말했고
     우리가 그 말을 모르는 것이다. 이때 `reason` 자리에 넣는 `kind` 는 **정규화 전 원문**
     이다. 그 값이 `CapabilityKey.canonical` 을 타고 키에 들어가므로, 문서가 쓴 글자
     그대로여야 되짚을 때 찾을 수 있다.
  5. `kind` 가 null 이면 모양으로 읽는다(`inferFromShape`):
     1. 필드가 하나도 없음(`size() == 0`) → `Always`
     2. `parts` 가 배열(빈 배열 포함) → **`Unknown(reason="group-kind-missing")`**.
        **이것은 AC #2 의 "`parts` 가 있으면 그룹"을 그대로 따르지 않는 것이다.** 그룹인
        것은 알지만 `every` 인지 `either` 인지는 모양이 말해 주지 않는다. 연결사를 찍으면
        "둘 중 하나"가 "둘 다"로 뒤집힐 수 있고, 그것은 이슈 제약이 경고한 "반대 방향
        사고"이자 `ConditionNode` KDoc 의 "평탄화 금지"가 막으려는 사고와 같다. 게다가
        이 모양은 **구조적으로 생산자가 없다** — SDK 는 소문자 `kind`, 우리 직렬화는
        대문자 `kind` 를 그룹에 반드시 쓴다. 되돌리는 것도 한 줄이다. PR 본문에 이
        이탈을 따로 적는다.
     3. `reason` 또는 `unread` 키를 들고 있음 → `Unknown(reason, unread)` — 값을 그대로
        옮긴다. 우리 직렬화가 `ConditionNode.Unknown` 을 정확히 이 모양으로 쓰므로, 여기
        규칙이 없으면 문서가 적어 둔 사유가 `condition-kind-missing` 으로 덮인다.
     4. `left`·`operator`·`right` 가 **모두** `hasNonNull` → `Test`
     5. `input` 이 `hasNonNull` → `Gesture`
     6. 그 외 → `Unknown(reason="condition-kind-missing")`
  - `Test` · `Gesture` 노드를 만드는 자리를 private 함수 하나씩으로 뽑아, 이름표로 온
    길과 모양으로 추론한 길이 **같은 코드**를 부르게 한다. 필드를 읽는 방식이 두 길에서
    갈리면(한쪽은 `context` 를 채우고 한쪽은 null) 이 이슈가 다시 열린다.
  - 새로 생기는 `Unknown.reason` 어휘 셋(`group-kind-missing` ·
    `condition-kind-missing` · `condition-not-an-object`)은 `EvidenceParser` 동반 객체에
    상수로 두고 **테스트가 그 상수를 참조**한다. 리터럴로 두 곳에 적으면 오타가 나도
    컴파일은 통과하고 테스트만 조용히 어긋난다. 저장소에 같은 전례가 있다 —
    `GroupKind.wire`, `SpecGapReason.wire`.

- [ ] **Step 2: Tests** —
  `src/test/kotlin/kr/artel/orchestration/contentmap/evidence/ConditionKindTest.kt`
  - 픽스처 `src/test/resources/contentmap/condition-kind-observed.json`:
    로컬 스택 `capability_evidence.condition_tree` 에서 그대로 꺼낸 조건 트리 8개를
    **최소 schema 7 문서**로 감싼다. 최소란 파서가 요구하는 것만이라는 뜻이다 — 문서는
    `schema`, `capture`, `types` 세 키만 갖고, 레코드는 `owner` · `entryId` ·
    `methodId` · `recordKind` · `triggerKind` · `condition` 만 든다. 값은 전부 DB 에서 온
    관측값이고, 지어낸 `entry` · `source` · `callPath` 는 넣지 않는다.
    **테스트 KDoc 이 출처를 정확히 적는다**: 이 트리들은 SDK 가 낸 것이 아니라
    `conditionJsonOf` 가 쓴 것이고, 문서 껍데기는 그것을 파서에 먹이려고 손으로 지은
    것이며, `jsonb` 가 키 순서를 다시 적었다. 껍데기가 "SDK 가 이런 문서를 낸다"는 뜻이
    되지 않게 하는 것이 이 KDoc 의 목적이다.
  - 노드 종류별 개수는 `ConditionNode.flatten()` 으로 **트리 전체를 펴서** 세고,
    **하드코딩으로 고정**한다. KDoc 에 그 수가 왜 그 수인지 적는다.
    - 실측 8트리: 전 `Unknown 8` (그 외 전부 0) → 후 `Group 12 · Test 20 · Gesture 8`,
      `Unknown 0`.
    - 두 대형 픽스처: **각각 개별적으로** 전후 동일(`Always 151 · Test 855 · Group 344 ·
      Gesture 25 · Unknown 6`, 합 1381). 두 문서의 분포가 우연히 같다는 것도 그대로
      단언한다. 바뀌지 않는 것이 단언이다.
    - 하드코딩한 수는 픽스처가 다시 구워지면 손으로 맞춰야 한다. KDoc 에 다시 세는
      방법(같은 테스트의 `countByKind` 헬퍼)을 적고, 수와 별개로 **"`Unknown` 은 늘지
      않는다"** 는 약한 불변식을 함께 단언한다 — 다음 사람이 수를 갱신하면서 이 변경이
      막은 퇴행을 같이 지우지 못하게 한다.
  - 경계 단위 테스트: `{}` → `Always`; `{"parts":[…]}` 이름표 없음 → `Unknown`;
    `{"parts":[]}` 이름표 없음 → `Unknown`; 이름표 없는 test/gesture 모양 → 각각
    `Test`/`Gesture`; `left` 만 있고 `operator`·`right` 가 없는 반쪽 test → `Unknown`;
    섞인 표기 `"EVERY"`·`"Every"`·`"TEST"`·`"Gesture"`·`"ALWAYS"` 가 전부 소문자와 같은
    것이 된다; 조건이 문자열/배열 → `Unknown`.
  - `conditionJson` 이 원본 그대로임을 함께 고정한다(이 이슈가 건드리지 않았다는 증거).

- [ ] **Step 3: Rollout / Rollback** — 마이그레이션도 플래그도 없다. **이미 앉은 행은
      이 변경만으로 되살아나지 않는다**(`### 뿌리` 참고) — 재적재는 SDK 원문을 다시 읽고,
      `condition_tree` 는 다시 파싱되지 않는다. 되돌리려면 `git revert` 한 번.

## Validation

- **Commands to run:**
  - 기준선(변경 전): `./mvnw -Dtest='kr.artel.orchestration.contentmap.**' test`
  - 변경 후 같은 명령
- **Expected output:** 기준선 241건 통과. 변경 후에는 새 테스트만큼 늘어난 수가 통과하고
  기존 골든은 하나도 흔들리지 않는다(픽스처에 이 모양이 없으므로).

## Risks & Rollback

- **Risks:**
  - 추론이 너무 관대하면 반대 방향으로 같은 사고가 난다. 그래서 그룹 연결사는 찍지 않고
    `Unknown` 으로 남긴다. 대신 이름표 없는 그룹의 자식(진짜 조건)은 이 선택으로
    버려진다 — 두 생산자 어느 쪽도 그 모양을 만들지 않으므로 지금 잃는 것은 없고,
    생기기 시작하면 `unknown` 수가 튀어 눈에 보인다.
  - **적재 결과는 바뀌지 않는다.** `EvidenceParser` 가 프로덕션에서 읽는 것은 SDK 문서
    하나뿐이고 그 문서에는 이 변경이 건드릴 모양이 없다. `CapabilityKey` 도,
    `capability_evidence` 행도 그대로다. 이 PR 은 그러므로 **지금 무엇을 고치는 것이
    아니라 앞으로 삼키지 않게 하는 것**이고, PR 본문에 그렇게 적는다.
  - 진짜 위험은 그대로 남는다: 쓰는 쪽(`conditionJsonOf`)이 우리 파서가 못 읽는 모양을
    계속 쓴다. 이 PR 이후로는 적어도 **읽을 수 있는** 모양이 되지만, 대문자와 소문자가
    같은 컬럼에 섞이는 것은 후속 이슈가 필요하다.
- **Rollback steps:** `git revert`. 마이그레이션이 없어 되돌림이 데이터에 남기는 것이
  없다.

## Rejected feedback

- **픽스처 파일 이름에 `synthetic`/`handbuilt` 표식을 넣어라.** 넣지 않는다. 손으로 지은
  것은 문서 껍데기 세 키뿐이고, 조건 트리 8개는 시스템이 실제로 쓴 값 그대로다. 이름에
  `synthetic` 을 달면 다음 사람이 트리까지 지어낸 것으로 읽어, 이 픽스처가 드는 유일한
  증거의 무게가 사라진다. 이름은 `condition-kind-observed.json` 으로 두고, "무엇을
  관측했나"(SDK 문서가 아니라 `capability_evidence.condition_tree` 8행)를 테스트 KDoc 이
  분명히 적어 오인을 막는다.

## Open Questions

- 이슈 본문의 어휘 수치(`test 1556 · every 405 · … · unknown 16 · EVERY 16`)와 지금
  측정한 수치(`test 865 · every 269 · … · unknown 8 · EVERY 12`)가 다르다. 같은 465행을
  다르게 센 것으로 보인다(중복 포함 여부). 방향과 결론은 같아 계획은 지금 측정치를 쓴다.
- **쓰는 쪽 후속 이슈를 누가 언제 여는가.** `GroupKind` 에 `@JsonValue`, `ConditionNode`
  에 `kind` 판별자를 붙이면 우리가 쓴 것을 우리가 그대로 읽는다. 이 PR 의 관대한 읽기는
  그때도 남을 값이 있지만(옛 행이 이미 그 모양으로 앉아 있다), 근본은 저쪽이다.
