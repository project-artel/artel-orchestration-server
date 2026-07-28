# 2026-07-28 — 관찰값 키를 부모 블록 경로까지 포함

- Date: 2026-07-28
- Jira: ARTEL-172
- Status: Complete

## Goal

`GameStateTransformer`가 만드는 관찰값 키를 블록 이름 하나에서 조상 블록 이름을
`.`으로 이은 경로로 바꾼다. `nameTMP.content`가
`Canvas.StatusPanel.nameTMP.content`가 된다.

## Non-goals

- `interactables`의 `name` 필드. 그쪽은 `id`로 지목하므로 경로가 필요 없다.
- Agent 씬 렌더러의 출력 길이 조정. 키가 길어지는 것은 사실이나, 실제 씬을 보고
  판단할 일이다.
- 이름에 `.`이 들어간 블록의 모호성 해소. Unity에서 흔하지 않고, 해결하려면
  구분자를 바꾸거나 이스케이프해야 해서 이 변경의 이득보다 비싸다.

## Context / Constraints

- 키가 블록 이름 하나뿐이라 서로 다른 부모 아래 같은 이름이 있으면 `Map`에서
  나중에 순회된 쪽이 앞을 덮어쓴다. 몇 개가 사라졌는지도, 남은 것이 어느 쪽인지도
  읽는 쪽에서 알 수 없다.
- 씬 루트 이름은 이미 `AgentGameState.scene`으로 나간다. 경로에 넣으면 모든 키에
  같은 접두사가 한 번 더 붙을 뿐이라 뺀다.
- 그 결과 씬 직속 블록의 키는 종전과 같다. 평평한 씬만 다루던 기존 테스트는
  그대로 통과해야 한다.
- ARTEL-170과 같은 함수를 건드리고 170이 아직 머지 전이라 그 위에 브랜치를 쌓는다.

## Approach (Checklist)

- [x] **Step 0: Recon** `traverse` 재귀 구조와 관찰값 키를 만드는 두 지점 확인
- [x] **Step 1: Implementation** `traverse`에 `path` 추가, `childPath`로 자식 경로
      조립, 키 두 곳을 `$path` 기준으로 변경. 진입점은 씬 루트에 빈 경로를 준다.
- [x] **Step 2: Tests** 중첩 경로가 이어지는지, 다른 부모 아래 같은 이름 두 블록이
      서로 덮어쓰지 않는지. 후자가 이 변경의 이유다.
- [x] **Step 3: 깨진 계약 정리** `ArtelWebSocketIntegrationTest`의 픽스처는 `Canvas`
      아래 중첩이라 단언 3개가 실제로 바뀐다. 키를 새 계약에 맞춘다.

## Validation

- **Commands to run:** `./mvnw -o test -Dtest=GameStateTransformerTest`;
  `./mvnw -o test -Dtest=ArtelWebSocketIntegrationTest`
- **Expected output:** 전부 통과, 평평한 씬을 쓰는 기존 단언은 수정 없이 통과
- **Result:** `GameStateTransformerTest` 15/15 (기존 13 + 신규 2),
  `ArtelWebSocketIntegrationTest` 6/6. 평평한 씬 단언은 손대지 않았고 통과했다.

## Risks & Rollback

- **Risks:** Agent가 관찰값 키를 문자열로 다루므로, 프롬프트나 시나리오가 특정
  키 이름을 하드코딩하고 있었다면 어긋난다. 레포 안에서는 그런 곳을 찾지 못했다.
  키가 길어져 씬 렌더링 한 줄이 길어지는 것은 감수한다.
- **Rollback steps:** 단일 커밋 revert.

## Open Questions

- 없음.
