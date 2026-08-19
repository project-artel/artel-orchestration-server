# 2026-08-18 — 근거 문서를 의사 C# 으로 렌더한다

- Date: 2026-08-18
- Jira: ARTEL-443
- Status: Implemented

## Goal

`wv2cs.py`(432줄, `.parallel-inputs/wv2cs.py`)를 Kotlin으로 이식한다. evidence JSON
(`artel-affordances.json` 스키마, 실측 `wv-editor-latest.json`)을 입력으로 받아 타입별
의사 C# 소스 + scene graph 를 문자열로 뽑는 순수 렌더러. DB/content_map 접근 없음
(ARTEL-458 이 그 반쪽).

## Non-goals

- content_map 조회, capability/verification/selector/edge 주석 (ARTEL-458)
- 씬 단위 자르기·캐시 (ARTEL-448)
- `wv2cs.py` 자체 유지보수

## Context / Constraints

- 참고 입력: `.parallel-inputs/wv-editor-latest.json` (1.4MB, schema 6, WordVenture 실측).
  `python3 .parallel-inputs/wv2cs.py <input> /tmp/wv-reference` 로 만든 37개 파일이 비교 대상.
  타입 21 + unplaced 14 + `_SceneGraph.cs` + `_Notes.cs` = 37.
- 골든 스냅샷은 **파이썬 출력과 바이트 동일할 필요 없다** — 이식이지 재현이 아니다. 네 가지
  버그는 반드시 고친다. 스냅샷은 Kotlin 렌더러 자신의 출력을 리소스로 고정해 회귀를 잡는 용도.
- evidence record는 dict 기반(파이썬)이라 필드 유무가 들쭉날쭉하다. 엄격한 POJO 트리 대신
  Jackson `JsonNode` 로 원본을 들고 다니며 필요한 키만 읽는다 — 파이썬 원본과 구조적으로
  대응되어 포팅 리스크가 낮고, 스키마가 흔들릴 때(evidence는 파생물, 언제든 재생성) DTO
  갱신 부담이 없다.
- 소유권 경계: `contentmap/render/**` 만 생성·수정. `contentmap/{entity,repository,dto}` 는
  건드리지 않는다(ARTEL-440, PR #135, develop에 없음 — 있을 필요도 없음).

## 실측으로 검증한 네 가지 수정

1. **증분**: `effect.detail` 이 `+`/`-` 로 시작하면 `target += N;` / `target -= N;` (부호 뒤
   숫자를 그대로 피연산자로 쓴다). 예: `BattleWaveController.wave` detail `"+1"` →
   `BattleWaveController.wave += 1;`. 검증: `ExpressionWriterTest` 에 이 정확한 입출력 쌍을
   assert 하는 케이스, 그리고 골든 스냅샷에 이 라인이 실제로 나타나는 것 둘 다 포함한다
   (스냅샷만으로는 회귀를 사람이 못 알아챈다 — 유닛 테스트가 계약, 스냅샷은 회귀 그물).
2. **UnityEvent 과다 부착**: triggerKind `unity-event` 레코드는 타입 기준 112건(unplaced
   포함 172건, 정확한 실측치 — 이슈 본문의 "106건"은 근사치라 정정) 중 실제 배선은
   `objects[].components[].calls[]` 7건 (`Canvas/MapSceneButton→InitPlayerData`,
   `Canvas/continue→LoadStoryScene`, `Canvas/ExitButton→QuitGame`,
   `Canvas/Button (Legacy)→BackButton.BackToMain`,
   `DebugCanvas/TurnEndButton→TurnBattleSystem.TurnEndButton`,
   `CombineSystem/CombineButton→CombineButton.OnButtonClick`,
   `CombineSystem/CombineZone/Button→CombineZone.OnButtonClick`).
   **주의(2차 검토, 실측 확인): 7건 중 6건만 실제로 주석이 붙는다.**
   `Combat.UI.CombineZone::OnButtonClick` 은 `types`/`unplaced` 어디에도 재구성된
   레코드가 없다(evidence 자체가 이 메서드 본문을 못 봄 — gap). `WiringIndex` 는
   여전히 7개 배선을 다 인덱싱하지만, `TypeRenderer` 는 자기가 렌더 중인 메서드에
   대해서만 인덱스를 조회하므로 이 메서드는 애초에 렌더되지 않고, 결과적으로
   `[UnityEvent(wired: ...)]` 는 6곳에만 나타난다. **이건 매칭 규칙이 깨진 게
   아니다** — 구현하면서 "7건이어야 하는데 6건만 나온다"고 매칭을 느슨하게 풀지
   말 것. `WiringIndexTest` 는 인덱스 자체가 7 엔트리인 것을, 골든/`TypeRenderer`
   관련 테스트는 실제 주석이 6개 메서드에 붙는 것을 각각 확인한다(대상이 다르다).
   **매칭 규칙(명시)**: `objects[]`(+ `persistentObjects[]`) 의 각
   `components[].calls[]` 항목이 `(targetType, method)` 를 나른다. 이 메서드가 속한
   레코드의 owner 타입 풀네임과 `targetType` 을 **문자열 그대로 완전 일치**로,
   메서드 이름과 `method` 를 **문자열 그대로 완전 일치**로 비교한다(파라미터 개수·타입은
   보지 않는다 — 원본 `calls[].method` 자체가 이름만 담고 오버로드 구분 정보가 없다;
   실측에도 오버로드 충돌 없음). 제네릭이나 별칭 정규화는 하지 않는다 — 실측 7건이 전부
   풀네임 그대로 일치하고, 이 스코프는 evidence 자체 재현이지 새 추론을 얹는 자리가
   아니다. 일치하면 `[UnityEvent(wired: "<object path1>", "<object path2>")]`
   (경로가 여럿이면 콤마로 나열), 없으면 `[InspectorCallable]`.
3. **subjectLost 조건**: `condExpr` 는 `CondResult(code: String?, comments: List<String>)`
   를 반환한다. atom(`kind == "test"` 등) 에 `subjectLost` 키가 있으면 그 atom은
   `code = null` 이고 `comments = ["unresolved condition (subject lost): $left $operator $right"]`.
   `every`/`either` 는 자식들의 `CondResult` 를 모아 **code 가 null 이 아닌 것만** 남겨
   `&&`/`||` 로 합치고(전부 null 이면 자기도 code=null), **모든 자식의 comments 를 그대로
   이어붙인다**(순서 보존, 자식이 몇 겹이든 leaf 주석까지 다 위로 전파). 최상위
   `CondResult.code` 가 null 이면 `if` 문 자체를 만들지 않고 `comments` 를 본문 위에
   한 줄씩 주석으로 남긴다(무조건 실행문 취급). code 가 있으면 평소대로 `if (code) { }`
   를 내고, 그 옆에 `comments` 를 `// ` 로 이어붙인 보조 주석 줄을 추가한다(있을 때만).

   **`variantKey` 와의 상호작용(2차 검토, 실측으로 확인한 버그 시나리오):**
   `Combat.Enemies.Player::TakeHit(int)` 는 레코드가 여러 개인데, 그중 하나(콜패스
   `SwordEnemy.Attack -> TakeHit`)는 조건이 `subjectLost`(`distanceToPlayer <
   Enemy.attackRange`, 실제로 subject를 못 정한 원자)이고 몸통은
   `Player.Hp = (Player.Hp - damage);` 하나뿐이다. 같은 메서드의 다른 레코드(콜패스
   `Player.TakeHit` 직접 진입, 조건 `always`)는 조건 없이 **똑같은** 몸통을 낸다.
   `variantKey` 는 여전히 `(code, bodyLines)` 만으로 잡는다(comments 는 키에 넣지
   않는다) — 이 두 레코드는 fix 3 적용 후 `code=null` 로 같아지고 몸통도 같아서
   원래도 합쳐져야 하는 진짜 동일 변형이기 때문이다(콜패스만 다르지 실제 낼 코드는
   하나). 대신 **렌더 시점에 그 variant 버킷에 모인 모든 레코드를 순회하며 각
   레코드의 `CondResult.comments` 를 합집합(순서 보존, 중복 제거)해서** 그 변형의
   주석으로 낸다 — `variants.setdefault(key, mutableListOf()).add(record)` 로 이미
   버킷에 원본 레코드가 다 모여 있으므로, 렌더링 단계에서 그 리스트를 다시 훑어
   comments 만 뽑으면 된다(별도 자료구조 불필요). 이렇게 안 하면 comments 가
   버킷의 "대표 레코드" 하나에서만 나와 나머지 레코드가 기여한 주석이 조용히
   사라진다 — 이게 정확히 2차 검토가 실측으로 잡아낸 문제. `MethodRendererTest` 에
   이 TakeHit 모양(같은 몸통, 한쪽만 subjectLost)을 최소 케이스로 추가해 comments 가
   살아남는 것을 assert.
   Python 원본의 "빈 variant 걸러내기"(`nonempty = ... if k[0] or k[1]`)도 그대로
   가져오되, comments 만 있고 code/body 가 비어 있어도 **주석이 있으면 비어있지
   않은 것으로 친다** — 이 필터에서 comments 를 안 보면 subjectLost 뿐인 변형이
   조용히 지워진다(fast/heavy 검토가 짚은 지점을 합쳐 반영).
4. **지역변수**: effect kind 가 write류(`write`/`ui-value`/`transform`)이고 target에 `.`
   이 없으면(필드는 항상 `Type.member` 형태로 온다 — 원본 evidence 추출기의 컨벤션,
   실측에서 100% 성립) 지역변수/파라미터로 보고 `local` 접두를 붙여
   `local waveEnd = 1;` 로 낸다. (실측: `i`, `waveEnd` 만 해당.)

`splitGenericArgs`(구 `split_args`)는 파이썬 원본과 동일하게 `<`/`[` 에서 depth+1,
`>`/`]` 에서 depth-1 을 세며 depth==0 인 쉼표에서만 분리한다 — `Dictionary<string,
List<int>>` 같은 중첩 제네릭도 원본처럼 깨지지 않는다. 새 알고리즘이 아니라 그대로 이식.

## WaitForSeconds 확인 결과

실측 문서 전체에 `WaitForSeconds` 문자열이 없다(코루틴 대기는 `handedOverTo` 로만 잡히고
전부 `UnityEngine.WaitUntil::.ctor` 다). 이식할 게 없다 — PR에 gap 으로 명시.

## Approach (Checklist)

- [x] **Step 0: Recon** — 완료. 스키마 필드 확인(`condition.subjectLost`, effect `target`
  dot 유무, `objects[].components[].calls`) 끝냄.
- [x] **Step 1: 모듈 구현** (`src/main/kotlin/kr/artel/orchestration/contentmap/render/`) —
  파이썬 섹션 주석을 그대로 파일로 옮기지 않는다. 실제 소비자 경계로 8개로 묶는다
  (1차 검토에서 `TypeNames`·`StateMachineFolder` 를 단일 소비자뿐인 독립 파일로 지적받아
  흡수함):
  - `EvidenceDocument.kt` — **JSON I/O 전담, 서명·타입 파싱 로직은 두지 않는다.**
    Jackson `ObjectMapper.readTree`, top-level 접근자(`types`, `unplaced`, `objects`,
    `persistentObjects`, `scenes`, `capabilities`, `gaps`, `build`) + 다른 모든 파일이
    쓰는 `JsonNode` 확장 함수(`textOrNull`, `arrayOrEmpty` 등)를 여기 한 곳에만 둔다 —
    나머지 8개 파일은 원시 `.get("x").asText()` 를 직접 흩뿌리지 않고 이 확장 함수를
    통해서만 읽는다.
  - `SignatureParser.kt` — `ParsedSignature(returnType, declaringType, name, params, raw)`,
    `parseSignature`, `isGeneratedSignature`, `declShort`, `splitGenericArgs`,
    **+ `shortType`/PRIMS 맵도 여기로 흡수**(타입 텍스트를 다루는 단일 소비자 성격이라
    별도 `TypeNames.kt` 를 두지 않는다 — 2차 검토 반영).
  - `ExpressionWriter.kt` — `callExpr`, `effectStmt`(fix 1, 4), `inputExpr`,
    `condExpr`(fix 3, `CondResult` 반환 — 위 절 참조), `handleStmt`, `handlerName`.
    파이썬 원본에서도 150줄 안팎의 독자 섹션이라 분리 근거가 있다.
  - `WiringIndex.kt` — `objects[]+persistentObjects[]` 의 `components[].calls[]` 를
    `(ownerFullType, methodName) -> List<objectPath>` 로 미리 인덱싱(fix 2, 매칭 규칙은
    위 절 참조). `TypeRenderer` 와 `SceneGraphRenderer` 둘 다 같은 배선 사실을 다른
    모양으로 써야 해서 계산을 한 번만 한다 — 실제 두 소비자가 있어 분리 유지(1·2차
    검토 모두 이견 없음).
  - `MethodRenderer.kt` — `buildBody`, `variantKey`(레코드의 렌더된 `(condition 문자열,
    본문 라인 리스트)` 쌍 그 자체를 키로 써서 `LinkedHashMap` 에 넣는 것 — 파이썬
    `variant_key`/`OrderedDict` 와 동일하게 "같게 렌더되는 레코드는 하나로 합친다"는
    뜻이지 해시가 아니다), `firstOffset`, `renderMethod`,
    **+ `logicalSig`(옛 `StateMachineFolder`) 도 여기로 흡수** — `<M>d__N::MoveNext` 를
    `M` 으로 접는 로직의 유일한 소비자가 `renderMethod` 라 독립 파일 근거가 없다(2차
    검토 반영).
  - `TypeRenderer.kt` — `renderType(typeName, records, extraHeader, wiringIndex)`.
  - `SceneGraphRenderer.kt` — `renderSceneGraph(objects, scenes, wiringIndex)`.
  - `EvidenceRenderer.kt` — 오케스트레이션: `EvidenceDocument` → `Map<String, String>`
    (파일명 → 내용, `_unplaced/<Type>.cs` 프리픽스 포함), 파이썬 `main()` 의 렌더 부분과
    대응. 컨트롤러/서비스 계층은 이 스코프 밖(엔드포인트 없음, 순수 함수).
- [x] **Step 2: 테스트** — 모듈 수를 줄인 만큼 테스트 파일도 1:1로 따라 줄인다.
  - 단위: `SignatureParserTest`(구 서명 파싱 + `shortType` 케이스 포함),
    `ExpressionWriterTest`(네 가지 수정 각각 최소 1케이스 + 정상 케이스 — 수정 1·4는
    입출력 문자열 assert, 수정 2는 `WiringIndexTest` 쪽에서, 수정 3은 `CondResult` 의
    `code`/`comments` 를 both 단독-subjectLost 케이스와 `every` 내 한 갈래만
    subjectLost 인 케이스로 각각 assert), `WiringIndexTest`(7건 실배선 + 미배선
    unity-event 최소 1건), `MethodRendererTest`(`logicalSig` 상태 머신 접기 케이스 포함).
  - 골든 스냅샷: `EvidenceRendererGoldenTest` — `wv-editor-latest.json` 을
    `src/test/resources/contentmap/wv-editor-latest.json` 에 고정 리소스로 두고 렌더,
    각 출력 파일을 `src/test/resources/contentmap/golden/*.cs` 와 바이트 동일 비교.
    같은 입력 두 번 렌더해 바이트 동일(결정론) 검증하는 케이스 별도 추가. 타입 21 +
    unplaced 14 + `_SceneGraph.cs` + `_Notes.cs` = 37개 파일 수 자체도 assert.
- [x] **Step 3: Rollout** — 신규 코드, 호출부 없음(다음 이슈가 컨트롤러에 연결). 롤백은
  디렉터리 삭제로 충분.

## Validation

- **Commands**: `./mvnw -q test -Dtest=EvidenceRendererGoldenTest` 먼저, 이어서
  `./mvnw clean test` (회귀 없는지).
- **Expected**: 골든 스냅샷 바이트 일치, 전체 스위트 그린.
- **실행 결과**: `EvidenceRendererGoldenTest`(39) + `ExpressionWriterTest`(13) +
  `MethodRendererTest`(5) + `SignatureParserTest`(7) + `WiringIndexTest`(4) = 68개
  전부 통과. `./mvnw clean test` 전체 스위트도 exit 0(기존 테스트 회귀 없음).
  실측 데이터로 직접 확인: `Canvas/continue` → `TitleSceneManager.LoadStoryScene`
  배선이 `[UnityEvent(wired: "Canvas/continue")]` 로 복원됨(이슈 본문이 지목한
  사라지는 버튼 사례), `BattleWaveController.wave += 1;` / `MapMove.StagePosition
  += 1;`(fix 1), `[InspectorCallable]` vs `[UnityEvent(wired: ...)]` 6곳(fix 2,
  `CombineZone::OnButtonClick` 은 레코드 자체가 없어 제외), `local waveEnd = 1;`(fix
  4), `WaveEndSensor` 조건 두 곳이 `// unresolved condition (subject lost): ...`
  주석으로 강등(fix 3)까지 골든에 고정.

## Risks & Rollback

- **Risks**: JsonNode 기반이라 필드 누락 시 조용히 빈 값으로 빠질 수 있음 —
  누락은 파이썬처럼 `?`/`/* ? */` 로 명시해 "지어내지 않는다" 원칙 지킴.
  UnityEvent 와이어링 매칭이 타입 풀네임 불일치(예: 별칭)로 놓칠 가능성 — 실측에서는
  전부 풀네임 일치라 스코프 내에서는 안전, 못 찾으면 `[InspectorCallable]`로 안전한 쪽으로 떨어짐.
- **Rollback**: `git revert`, 또는 `contentmap/render` 디렉터리 삭제(다른 코드가 참조하지 않음).

## Rejected feedback (1차 검토)

- **Fast reviewer, must-fix 1**: "타입 매칭에서 제네릭 소거·별칭 정규화 규칙을 정의하라" —
  거부는 아니고 축소 수용. 실측 7건이 전부 완전한 풀네임 문자열 일치이고, evidence
  추출기가 원래 제네릭 소거된 형태로 `targetType`/`method` 를 낸다(스키마 자체가 이미
  단순 문자열). 이 스코프에서 없는 사례를 위한 정규화 규칙을 새로 설계하는 것은
  "근거에 없는 것을 코드로 지어내지 않는다"는 이슈의 제약과 어긋난다. 완전 일치만
  하고 실패하면 안전한 기본값(`[InspectorCallable]`)으로 떨어지도록만 명시했다.
- **Medium reviewer, should-fix 3(제안 그대로)**: "`EvidenceDocument.kt` 에 `SignatureParser`
  와 `TypeNames` 를 합쳐라" — 파일 수를 줄이자는 방향은 수용했지만 합치는 대상은
  바꿨다. JSON 트리 접근(I/O 경계)과 C# 시그니처 문자열 파싱(순수 텍스트 변환)은 서로
  다른 책임이라 한 파일에 두면 "이 파일이 뭘 하는 파일인지" 되묻게 된다. 대신
  `TypeNames` 를 `SignatureParser` 로 흡수했다(같은 성격 — 둘 다 타입/시그니처 텍스트
  변환, `TypeNames`의 유일한 소비자가 사실상 `SignatureParser`가 이미 만들어내는
  타입 문자열이기도 함).

## Pair review (구현 후, PR 전)

`pair-review-critic` 역할로 실제 파일을 읽고 실측 데이터로 검증받았다. `VERDICT: NONPASS`,
must-fix 성격 6건 + minor 3건. 전부 반영, 재검토는 생략(작은 수정들이라 반복 불필요).

- **fix 1 이 실제로 틀린 렌더를 냈다(가장 중요, 실측으로 확인됨)**: `-` 로 시작하는
  `detail` 을 전부 증분으로 읽으면 `_unplaced/Cards.Util.cs` 의 `Vector3.z` write
  (`detail: "-10"`, `Vector3 MousePos { get; }` 안에서 z를 고정 깊이로 대입하는 코드)가
  `Vector3.z -= 10;` 으로 지어내진다 — 원본은 대입이지 증분이 아니다. `+` 는 안전하다
  (양수 리터럴 대입은 `detail` 에 `+` 부호가 안 붙는다는 게 evidence 추출기 관례라
  `+` 가 있다는 것 자체가 증분 관용구라는 뜻). `-` 는 증분(`-= N`)과 음수 리터럴
  대입(`= -N`) 이 문자열로 구분이 안 되는 진짜 모호함이라, "근거에 없는 것을 지어내지
  않는다"는 이슈 제약을 따라 `+` 만 `+=` 로 바꾸고 `-` 는 원래대로 대입으로 낸다.
  `MapMove.position` 의 `-1` 케이스도 같은 규칙을 적용받아 다시 `= -1` 로 바뀌었다 —
  참일 수도 있지만 근거만으로는 확신할 수 없으므로 지어내지 않는 쪽을 택했다.
  (`ExpressionWriter.kt`, `ExpressionWriterTest.kt`, golden 갱신.)
- **`condExpr` 가 메서드 하나 렌더링에서 레코드당 최대 3번(변형 키, 빈 변형 필터,
  주석 합치기) 다시 불렸다** — "code 와 comments 는 한 판단에서 나온 한 쌍"이라는 fix 3
  의 불변식이 세 곳에 흩어져 있으면, 그중 한 곳만 고쳤을 때 comments 소실 버그가
  재현될 수 있다는 지적. `PreparedRecord(record, condition, bodyLines)` 로 레코드당
  딱 한 번만 계산해 세 지점 모두에서 재사용하도록 `MethodRenderer.kt` 를 정리했다.
- **`EvidenceDocument` 의 "raw 접근은 여기로만" 이라는 문서 주장을 `ExpressionWriter`
  가 스스로 어겼다**(별도 `ObjectMapper` 생성 + `condition.fields()` 직접 순회, gesture
  렌더링 때문에 불가피). `ObjectMapper` 를 `evidenceObjectMapper` 하나로 합쳐 재사용하고,
  KDoc 을 "정해진 키는 여기 확장 함수로, gesture 처럼 임의 키를 통째로 훑어야 하는 드문
  예외는 이 매퍼를 재사용" 으로 정확하게 고쳤다.
- **gesture 조건의 JSON 스니펫이 파이썬 `json.dumps` 기본 간격(`": "`, `", "`)과 다르게
  붙어 나왔다**(Jackson `writeValueAsString` 은 구분자에 공백을 안 넣는다). 네 가지 수정
  대상은 아니지만 사람이 읽는 게 이 렌더러의 존재 이유라 고쳐 넣었다 —
  `gestureDetail` 이 `"key": value` 형태로 직접 조립하도록 바꿨다.
- **fix 4 의 "target에 점이 없으면 지역변수" 규칙에 대한 주석이 과신하고 있었다** —
  "필드는 항상 `Type.member`" 라고 단정했지만, 로컬 struct의 멤버 write(`Vector3.z`)도
  선언 타입 이름으로 적혀 필드처럼 보인다(evidence가 리시버로 로컬 변수 이름을 안
  싣는다). 오늘 데이터에는 그런 로컬 struct 케이스가 없어 동작은 안 바뀌지만, 주석을
  "관례이지 보장이 아니다 — 이 한계는 고칠 수 없다"로 정정했다.
- **파이썬의 falsy(빈 문자열) 처리 일부가 null 체크로만 옮겨져 있었다**(`animation`
  detail 이 빈 문자열이면 `target.;` 처럼 깨진 문장이 나올 수 있는 자리, `audio`/`saved`
  값 자리, `handleStmt` 의 channel). 오늘 데이터에는 빈 문자열 사례가 없어 골든은 안
  바뀌지만, 원본이 명시적으로 다루던 경계라 `isNullOrEmpty()` 로 맞춰 두고 테스트
  2건을 추가했다.
- **minor, 반영**: 골든 스냅샷 테스트가 파일 37개마다 1.4MB 문서를 통째로 다시
  렌더링하고 있었다(파라미터화 테스트 37회 = 렌더 37회) — `PER_CLASS` + `@BeforeAll`
  로 한 번만 렌더하도록 정리. `String.format` 이 기본 로케일을 타 결정론이 흔들릴
  잠재 위험이 있어 `Locale.ROOT` 명시. `WiringIndex` 의 `persistentObjects` 경로가
  테스트로 안 덮여 있어 합성 문서 테스트 1건 추가.
- **반영 안 함(근거 있는 거부)**: "`EvidenceDocument.schema` 가 스키마 버전을 파싱만
  하고 검증은 안 한다 — 스키마 7이 와도 조용히 이상한 출력을 낸다" 는 지적은 맞지만,
  이 저장소에는 스키마 6 문서 하나뿐이고(비교 대상도 그것뿐) AC 에 버전 검증 요구가
  없다. 검증할 대상(다른 스키마 문서)이 아직 없는 상태에서 방어 코드를 추가하는 것은
  이전 medium plan-review 가 이미 경계한 과잉설계다 — evidence는 "언제든 재생성"되는
  파생물이라는 전제와도 맞다. 다른 스키마가 실제로 등장하는 시점에 그 이슈에서 다룬다.

## Open Questions

- (없음 — 스코프가 순수 렌더러로 좁혀져 있어 확정적)
