# 2026-08-27 — 지식 그래프 조회에 앵커를 싣는다

- Date: 2026-08-27
- Jira: ARTEL-605
- Status: Draft

## Goal

ARTEL-591 이 만든 앵커를 **사람이 볼 수 있게** 한다. 591 은 앵커를 저장하고 Agent 의
검색 히트(`KnowledgeSearchHit.anchors`)에 실었지만, 브라우저는 그 경로를 쓰지 않는다.
지식 콘솔은 그래프 조회(`/api/projects/{projectId}/knowledge-graph`)를 읽고, 그 노드는
`id`/`tag`/`source`/`summary`/`version`/`createdByQaTryId`/`createdAt` 뿐이다. 그래서
앵커가 DB 에 있어도 화면에는 없는 것과 같고, ARTEL-593(FE) 이 그릴 것이 없다.

- 그래프 노드마다 `anchors` 를 싣는다. **없으면 빈 배열**이고 그 필드는 언제나 있다.
- 노드 묶음의 앵커를 **한 번에** 데려온다. 노드당 한 질의는 응답 하나를 N 질의로 만든다.
- 스코프에 가려진 지식의 앵커는 새지 않는다.

## Non-goals

- 앵커 수정·삭제 API (ARTEL-591 과 같은 non-goal).
- 앵커로 그래프를 좁히는 필터. 검색에는 `scene_name` 필터가 있지만(591) 그래프는 창고
  전체의 생김새를 보는 화면이라 아직 좁힐 이유가 없다.
- Agent 경로(`KnowledgeSearchHit`, `QaAgentInboundRouter`)와 `artel-home` 쪽 변경.

## Context / Constraints

- **브라우저 DTO 라 camelCase 다.** ARTEL-591 의 Agent 용 DTO(`KnowledgeAnchorView`)는
  같은 사실을 snake_case 로 말한다. 그 비대칭은 의도이며 어느 쪽도 "고치지" 않는다 —
  WS 프레임의 payload 관례와 `/api` 응답의 관례가 원래 다르다.
- **`screenId` 는 문자열이거나 null 이다.** 이 레포의 다른 id 와 같이 문자열로 낸다
  (FE 64비트 정밀도 손실 방지). null 은 정상이다 — 화면은 pulse 관측으로 판정되는
  것이라(V40) 판정이 안 되는 순간이 정상적으로 있다.
- **앵커가 없는 것이 기본값이다.** 앵커 없는 지식은 게임 전체의 사실이고, 창고 대부분이
  그렇다. 그래서 빈 배열은 "못 읽었다"가 아니라 "게임 전체의 사실"이라는 뜻이다.
- **N+1 을 만들지 않는다.** 이 조회는 노드를 최대 500개까지 낸다. 노드마다 앵커를 부르면
  질의가 노드 수만큼 늘고, 그 비용은 앵커가 하나도 없는 프로젝트에서도 그대로 난다.
  `KnowledgeAnchorRepository.findVisibleFor` 가 이미 묶음 조회라 그대로 쓴다.
- **스코프 술어는 리포지토리가 진다.** 그래프는 운영 스코프만 담으므로 노드는 이미
  걸러진 뒤지만, 앵커 조회가 자기 힘으로 `KnowledgeScopeSql.VISIBLE` 을 지나야 한다 —
  `KnowledgeScope` 주석이 말하는 "읽기 경로를 하나라도 빠뜨리면 격리가 뚫린다"가 여기에도
  그대로 적용된다. 591 이 검색에서 지킨 선과 같다.
- **순수 추가다.** 기존 노드 필드는 그대로고, `anchors` 를 모르는 클라이언트는 지금과
  똑같이 동작한다.

## Approach (Checklist)

- [ ] **Step 0: Recon** — `KnowledgeGraphViewService`/`KnowledgeGraphViewDtos`,
      `KnowledgeAnchorRepository.findVisibleFor`, `KnowledgeScopeSql.VISIBLE`,
      591 이 검색에서 앵커를 붙인 방식 확인
- [ ] **Step 1: Implementation**
  - `knowledge/dto/KnowledgeGraphViewDtos.kt` — `KnowledgeGraphNodeAnchor` 추가,
    `KnowledgeGraphNode.anchors`
  - `knowledge/service/KnowledgeGraphViewService.kt` — 노드가 정해진 뒤 앵커를 한 번에
    데려와 메모리에서 묶고, `toNode` 에 넘긴다
- [ ] **Step 2: Tests** (`KnowledgeGraphViewIntegrationTest`)
  - 앵커 하나 / 여럿 / 없음(빈 배열) / 씬만 아는 앵커(`screenId` null)
  - 가려진 항목(삭제·실험 스코프)의 앵커가 새지 않는다
  - **질의 수 고정** — 리포지토리를 세는 델리게이트로 감싸 노드 여러 개짜리 그래프에서
    `findVisibleFor` 호출이 정확히 1회임을 못박는다
  - 앵커가 하나도 없는 그래프는 앵커 질의를 아예 하지 않는다(빈 컬렉션은 `IN ()` 로
    SQL 이 깨진다 — 591 리포지토리 주석)
- [ ] **Step 3: Rollout / Rollback** — 순수 추가. 마이그레이션 없음. `git revert` 하나면
      끝이고, 되돌려도 FE 는 없는 필드를 못 읽을 뿐 깨지지 않는다.

## Validation

- **Commands to run:**
  - `./mvnw test -Dtest='Knowledge*'` — 실질 게이트
  - `./mvnw test` — 전체(선재 실패 2건 확인용)
- **Expected output:** `Knowledge*` 전부 통과. 전체 suite 에서
  `OpenApiDocumentationIntegrationTest` 와 `TestScenarioReconcileIntegrationTest` 는
  clean `origin/develop` 에서도 실패하는 선재 실패라 지식 경로와 무관하다.

## Risks & Rollback

- **Risks:**
  - 노드 상한이 500 이라 앵커 행이 노드보다 많을 수 있다(한 지식이 여러 화면에 걸린다).
    응답 크기가 앵커 수만큼 는다 — 앵커는 씬 이름과 id 한 쌍뿐이라 본문보다 훨씬 작고,
    노드가 본문을 싣지 않는 기존 판단이 이 여유를 이미 벌어 두었다.
  - 591 이 남긴 구멍(스코프 런의 그림자가 baseline 앵커를 물려받지 않는다)은 그대로다.
    그래프 조회는 운영 스코프만 담아 그림자를 애초에 보지 않으므로 여기서는 발화하지
    않는다.
- **Rollback steps:** `git revert`. 스키마 변경이 없어 DB 는 건드릴 것이 없다.

## Open Questions

- 앵커로 그래프를 좁히는 축이 필요해지면 검색의 `scene_name` 필터와 같은 모양으로
  붙인다. 이번 범위 밖이라 열어 둔다.
