# 2026-07-26 — 비활성 UI 조작 후보 제외

- Date: 2026-07-26
- Jira: ARTEL-134
- Status: Implemented

## Goal

SDK가 씬 스냅샷에 싣기 시작한 `interactable` 값을 읽어, 비활성 `button`/`editText`를 에이전트에게 주는 `interactables`에서 제외한다.

## Non-goals

- 액션 요청 시점의 재검증. 비활성 대상 실행 차단은 SDK가 담당한다(ARTEL-133).
- 커스텀 컴포넌트 액션의 게이팅.
- 비활성 UI를 `observables`나 액션 기록에서 빼는 것. 관측은 그대로 남아야 한다.
- `interactables` 응답 스키마에 상태 필드를 새로 추가하는 것. 후보에서 빼는 것으로 충분하다.

## Context / Constraints

- `GameStateTransformer.traverse`는 타입이 `button`이거나 `editText`이면 무조건 `Interactable`을 만든다.
- SDK 배포와 서버 배포 순서가 보장되지 않는다. `interactable` 필드가 없는 구버전 페이로드는 기존과 동일하게 후보에 포함되어야 한다.
- `jackson-module-kotlin`이 pom에 있으므로 Kotlin 기본값이 역직렬화에 적용된다. 필드 부재 시 `true`가 들어온다.
- `Interactable` 응답 스키마의 기존 필드는 바꾸지 않는다. 에이전트 프롬프트가 그 형태에 묶여 있다.

## Approach (Checklist)

- [x] **Step 0: Recon** — `sdk/dto/GameStateDto.kt`의 `SdkComponent`, `sdk/service/GameStateTransformer.kt`의 `traverse` 확인 완료.
- [x] **Step 1: Implementation**
  - `SdkComponent`에 `val interactable: Boolean = true` 추가. 기본값이 하위 호환을 담당한다는 주석을 남긴다.
  - `traverse`의 `"button"`/`"editText"` 분기에서 `interactable`이 `false`면 `interactables`에 넣지 않는다. 액션 기록 수집은 건드리지 않는다.
  - 라벨 중복 배제 조건을 `hasButton`에서 `hasInteractableButton`(= 상호작용 가능한 `button`이 같은 블록에 있는가)으로 좁힌다. 지금 규칙은 "블록에 버튼이 있으면 그 블록의 `text` content는 버튼 라벨이니 관찰값에서 뺀다"인데, 버튼이 후보에서 빠지면 그 라벨은 `interactables`에도 `observables`에도 남지 않아 에이전트 시야에서 완전히 사라진다. 잠긴 버튼이야말로 "무엇이 잠겼는지"를 알아야 하는 대상이므로, 후보로 나가지 않는 버튼의 라벨은 관찰값으로 남긴다.
- [x] **Step 2: Tests**
  - `GameStateTransformerTest`: 비활성 버튼이 후보에서 빠지고 활성 버튼은 남는지, 비활성 `editText`가 빠지는지.
  - 비활성 버튼의 라벨 `text`가 `observables`에 남는지. 활성 버튼의 라벨은 종전대로 `observables`에서 빠지는지.
  - 비활성 버튼이 실행한 액션은 `recentActions`에 그대로 남는지.
  - `interactable` 키가 없는 JSON을 읽었을 때 후보에 포함되는지(하위 호환). DTO를 직접 만드는 테스트로는 이 경로가 검증되지 않는다. Kotlin 기본값이 적용되려면 `jacksonObjectMapper()`를 써야 한다. 맨 `ObjectMapper()`로는 런타임(Spring이 주입하는 매퍼)과 다른 것을 검증하게 된다.
- [x] **Step 3: Rollout / Rollback** — 플래그 없음. 마이그레이션 없음. 서버를 먼저 배포해도 구버전 SDK 동작은 그대로다.

## Validation

- **Commands to run:** `./mvnw test -Dtest=GameStateTransformerTest`
- **Expected output:** 신규 테스트 통과, 기존 변환 테스트 회귀 없음.
- **실행 결과:** `GameStateTransformerTest` 8건 전부 통과. 전체 `./mvnw test`는 123건 중 52건 오류이나 모두 통합 테스트의 `DELETE FROM project` 정리 단계에서 나는 `DataIntegrityViolation`이고, 변경 전 `origin/develop`에서 `ProjectDocumentIntegrationTest` 12/12, `ArtelWebSocketIntegrationTest` 6건 중 1건 오류로 동일하게 재현된다. 이 변경과 무관한 기존 실패다.

## Risks & Rollback

- **Risks:**
  - 게임이 버튼을 일시적으로 잠그는 연출을 쓰면 그 프레임의 스냅샷에서 후보가 사라진다. 다음 스냅샷에서 복구되므로 수용한다.
  - 씬 전체가 잠긴 상태의 스냅샷에서는 `interactables`가 비어 에이전트가 할 일이 없다고 판단할 수 있다. 관찰값은 그대로 남으므로 상태 자체는 보인다.
- **Rollback steps:** `git revert`. SDK 쪽 변경과 독립적으로 되돌릴 수 있다.

## Open Questions

- 없음.
