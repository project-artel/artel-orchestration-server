# 2026-09-02 — knowledge 항목 단건 조회 API 를 만든다

- Date: 2026-09-02
- Jira: ARTEL-753
- Status: Reviewed (fast/medium/heavy plan-review passed 2026-09-02)

## Goal

`GET /api/projects/{projectId}/knowledge/{knowledgeId}` 를 추가해, 그래프 화면에서 노드 하나를
고른 사용자가 `description`(본문)까지 읽을 수 있게 한다. 그래프 목록 응답(`KnowledgeGraphViewResponse`)
은 그대로 두고, 본문은 이 단건 조회에서만 나간다.

## Non-goals

- 수정(PATCH/PUT)과 삭제 — 이 API 는 읽기 전용이다.
- 그래프 목록 응답의 모양 변경.
- 스코프 조회(실험 arm) — 운영 스코프(`scope_id IS NULL`)만 읽는다.

## Context / Constraints

- **1차 plan review 에서 나온 결정들(fast/medium 모두 반영):**
  - `findVisibleById(id, projectId, scopeId)` 에 넘길 `scopeId` 는 `KnowledgeScope.PRODUCTION.id`
    (== `null`) 다. `KnowledgeScopeSql.VISIBLE` 은 `(scope_id IS NULL OR scope_id = :scopeId)` 라
    `:scopeId` 가 null 이면 `scope_id = NULL` 은 SQL 에서 항상 unknown(false) 이 되어 baseline 행만
    남는다 — `KnowledgeGraphViewService.graph()` 가 `findVisible` 에 이미 같은 값을 넘기는 것과
    같은 관례다.
  - `updatedAt` 은 새 컬럼이 아니다. `KnowledgeEntity.updatedAt: Instant?`
    (`@LastModifiedDate`) 이 이미 있고 R2DBC auditing 이 자동으로 채운다 — 마이그레이션이 필요 없다.
  - `PART_OF_RELATION` 상수는 `KnowledgeRelation.kt:119` 에 `"PART_OF"` 로 이미 정의돼 있고
    `KnowledgeDocumentNodeSql.IS_DOCUMENT_NODE` 가 이미 그 값을 문자열 템플릿으로 참조한다 — 새로
    만들 것이 없다.
  - **컨트롤러는 새 클래스 `KnowledgeDetailController` 로 확정한다.** Spring 이 클래스 레벨
    `@RequestMapping` 과 메서드 레벨 매핑을 이어 붙이므로, `KnowledgeGraphViewController` 의
    베이스 경로(`/api/projects/{projectId}/knowledge-graph`)를 바꾸지 않고는 `/knowledge/{knowledgeId}`
    를 그 클래스에 얹을 수 없다("또는 기존 컨트롤러" 가지를 접는다).
  - **서비스는 기존 `KnowledgeGraphViewService` 에 `detail(...)` 메서드로 추가한다.** 새 서비스
    클래스를 만들면 `ProjectAccessService`/`KnowledgeRepository` 생성자 배선만 그대로 복제하는
    분리라 이득이 없다("또는 별도 서비스" 가지를 접는다).
  - 단건 조회는 `findVisibleById` 로 행을 한 번 읽고 `isDocumentNode(id)` 로 한 번 더 묻는
    **의도적인 두 질의**다. 목록 조회처럼 N+1 이 되는 구조가 아니라 요청마다 정확히 두 번이라,
    하나로 합치는 최적화가 필요할 만큼의 비용이 아니다 — 나중에 "질의를 하나로 합치자"는 시도도
    "왜 두 번이냐"는 지적도 둘 다 여기서 미리 접는다.
  - 앵커(anchors)는 단건 응답에 넣지 않는다 — 그래프 노드가 이미 낸 값이고, 이 서비스는
    `anchorRepository` 를 detail 경로에서 아예 부르지 않는다.
  - 실험 스코프 항목의 404 테스트: `givenKnowledge(..., scopeId = EXPERIMENT_SCOPE_ID)` 로 만든
    행을 그 id 로 조회하면, `findVisibleById` 에 넘기는 `scopeId` 가 언제나 `null`(운영)이므로
    `(scope_id IS NULL OR scope_id = NULL)` 이 그 행에서 거짓이 되어 조회되지 않는다 —
    `KnowledgeGraphViewIntegrationTest` 의 "실험 스코프의 지식과 간선은 응답에 없다" 와 같은 판단.

- `KnowledgeGraphNode`(`KnowledgeGraphViewDtos.kt:41`)의 KDoc 이 이미 이 API 의 존재를 전제한다:
  "본문이 필요한 순간은 사용자가 노드 하나를 고른 뒤이고 그때는 단건 조회가 있다." 그 API 가 없어서
  이번에 만든다.
- `KnowledgeRepository.findVisibleById(id, projectId, scopeId)` 가 이미 필요한 조건을 전부 건다:
  `project_id` 일치, `deleted_at IS NULL`, `KnowledgeScopeSql.VISIBLE`(스코프). 접근 불가 프로젝트는
  `ProjectAccessService.requireMember` 가 `NotFoundException` 으로 던진다. 셋을 합치면 이슈가 요구한
  세 가지 404 조건(접근 불가 프로젝트/다른 프로젝트 항목/소프트삭제 항목)을 코드 추가 없이 만족한다.
- **응답에 무엇을 더 실을지.** `KnowledgeGraphNode` 가 이미 `id`/`tag`/`source`/`summary`/`version`/
  `createdByQaTryId`/`createdAt`/`anchors` 를 낸다 — 브라우저는 노드를 고르기 전에 그래프 목록 호출로
  이 값을 이미 쥐고 있다. 겹치지 않게 더할 값:
  - `description` — 이슈가 요구한 필수 값. 그래프가 뺀 이유(용량)가 이 조회에는 적용되지 않는다(한
    항목만 읽으므로).
  - `updatedAt` — 그래프 노드는 `createdAt` 만 낸다. "이 항목이 마지막으로 고쳐진 시각"은 노드가
    주지 않는 새 정보이고, `version` 만으로는 "언제" 를 알 수 없다.
  - `isDocumentNode` — 아래 문서 node 판단 참조.
- **문서 node(ARTEL-748) 취급.** `KnowledgeService.createDocumentNode` 의 KDoc 은 이미 이 이슈를
  지목한다: "이 node 를 단건으로 읽는 사람(항목 단건 조회 API, ARTEL-753)이 게임 지식으로 착각하지
  않는다." `description` 컬럼에 고정 문장(`DOCUMENT_NODE_DESCRIPTION`)이 들어 있어 사람이 읽으면
  구분되지만, 그 문장은 한국어 자유 텍스트라 프런트엔드가 파싱해 분기할 근거로 삼기엔 약하다(문장이
  바뀌면 조용히 깨진다). 그래서 **응답에 `isDocumentNode: Boolean` 을 명시적으로 추가한다** —
  판정은 `KnowledgeDocumentNodeSql.IS_DOCUMENT_NODE` 술어(살아있는 `PART_OF` edge 의 도착점) 하나만
  쓴다. 그 외에는 문서 node 를 다른 knowledge 행과 다르게 취급하지 않는다: 같은 조회 경로, 같은 404
  규칙, `summary`/`description` 필드 이름도 같다 — 문서 node 도 결국 `knowledge` 테이블의 한 행이고,
  화면이 다르게 그리고 싶다면 이 플래그 하나로 충분하다.
- 브랜치는 ARTEL-748 위에 쌓인 스택이라 base 는 `develop` 이 아니라
  `feat/기획서-node-와-part-of-edge-를-knowledge-적재-때-만든다-ARTEL-748`.
- id 는 문자열로 주고받는다(64비트 정밀도 손실 회피, 레포 관례).

## Approach (Checklist)
- [x] **Step 0: Recon** — `KnowledgeGraphViewDtos.kt`, `KnowledgeGraphViewService.kt`,
  `KnowledgeGraphViewController.kt`, `KnowledgeRepository.kt`, `KnowledgeEdgeEntity.kt`
  (`KnowledgeDocumentNodeSql`), `ProjectAccessService.kt`, `KnowledgeService.kt` (문서 node 생성부),
  기존 `KnowledgeGraphViewIntegrationTest.kt` 를 읽고 패턴 확인.
- [ ] **Step 1: Implementation**
  - `KnowledgeRepository` 에 `isDocumentNode(id: Long): Boolean` 추가 —
    `KnowledgeDocumentNodeSql.IS_DOCUMENT_NODE` 를 그대로 재사용한 `SELECT EXISTS(...)`.
  - 새 DTO 파일 `knowledge/dto/KnowledgeDetailDtos.kt` — `KnowledgeDetailResponse(id, summary,
    description, updatedAt, isDocumentNode)`.
  - 새 서비스 메서드 `KnowledgeGraphViewService.detail(projectId, userId, knowledgeId): KnowledgeDetailResponse`.
    `accessService.requireMember` 로 프로젝트 접근을 확인(비참여자는 404 — 그래프 목록의 "빈
    그래프" 판단과 다르다: 단건 조회는 있는 척할 목록이 없으므로 404 가 맞다), 이어서
    `findVisibleById(knowledgeId, projectId, KnowledgeScope.PRODUCTION.id)` 로 항목을 읽고 없으면
    404.
  - 새 컨트롤러 `KnowledgeDetailController` —
    `@GetMapping("/api/projects/{projectId}/knowledge/{knowledgeId}")`. 기존
    `KnowledgeGraphViewController` 와 경로 루트가 다르므로(`/knowledge` vs `/knowledge-graph`) 새
    컨트롤러 클래스로 분리한다.
- [ ] **Step 2: Tests**
  - 새 파일 `KnowledgeDetailIntegrationTest.kt`(`KnowledgeGraphViewIntegrationTest.kt` 와 같은
    스타일 — `@SpringBootTest(webEnvironment = NONE)`, 서비스 직접 호출, FK 순서 수동 정리): 정상
    조회(`id`/`summary`/`description`/`updatedAt`/`isDocumentNode` 확인), 비참여자 404, 다른
    프로젝트 항목 404, 소프트삭제 항목 404, 실험 스코프 항목 404(운영 스코프만 읽으므로), 문서
    node 는 `isDocumentNode=true`.
- [ ] **Step 3: Rollout / Rollback**
  - 순수 추가(GET, 읽기 전용) — 마이그레이션 없음, 기존 응답 변경 없음. 문제가 생기면 컨트롤러/
    서비스/DTO 커밋을 되돌리면 된다.
  - `OpenApiSnapshotTest` 를 실행해 `docs/api/openapi.json` 재생성.

## Validation
- **Commands to run:**
  - `./mvnw test -Dtest='KnowledgeGraphViewIntegrationTest,KnowledgeDetailIntegrationTest,OpenApiDocumentationIntegrationTest,OpenApiSnapshotTest'`
- **Expected output:** 새/기존 테스트 전부 통과. 전체 스위트는 이 변경과 무관하게 이미 깨져 있으므로
  실행하지 않는다.

## Risks & Rollback
- **Risks:** `isDocumentNode` 판정이 `PART_OF` edge 존재 여부에 의존하므로, edge 조회가 느리면
  단건 조회에 조인 하나가 추가된다 — 그래프 목록과 달리 단건이라 비용은 무시할 수준.
- **Rollback steps:** 컨트롤러/서비스/DTO/리포지토리 메서드를 되돌리는 revert 한 번으로 충분(다른
  경로가 이 코드를 참조하지 않는다).

## Open Questions
- 없음 — 이슈와 KDoc 이 결정에 필요한 근거를 이미 남겨 두었다.
