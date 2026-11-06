# 2026-08-05 — QA 이슈 조회·해결 API와 해결 상태

- Date: 2026-08-05
- Jira: ARTEL-245 (Epic ARTEL-12 [Backend] Orchestration 서버 개발)
- Branch: `feat/qa-이슈-조회-해결-api와-해결-상태를-추가한다-ARTEL-245`
- Status: Draft

## Goal

Agent가 남긴 `issue` 행을 **읽을 수 있게** 하고, 사람이 **처리했다고 표시**할 수 있게 한다.

세 갈래 계획의 가운데 조각이다. 프레임을 싣는 쪽은 artel-agent-server
`.plan/general/2026-08-05-report-issue-tool.md`(ARTEL-246), 화면은 artel-home
`.plan/general/2026-08-05-qa-issue-console.md`(ARTEL-247). **이 계획이 계약의 원본**이며,
home은 여기서 정한 응답 모양에 맞춘다.

## Non-goals

- 이슈 본문 편집·코멘트·담당자 지정. 지금 이슈는 Agent가 관측한 증거이고, 사람이 더하는 것은
  "처리했다"는 사실 하나뿐이다.
- 외부 트래커(Jira 등) 연동.
- 중복 이슈 자동 병합·유사도 판정.
- 이슈 통계 집계 API. 수치는 ARTEL-243 계열이 맡는다.

## Context / Constraints

기준 `origin/develop`. 마이그레이션 최신은 **V25**(`add_qa_try_run_config`)이므로 신규는 **V26**.

현재 상태:

| 위치 | 지금 |
|---|---|
| `V12__create_issue.sql` | `severity` / `title` / `detail`(JSONB) / `reported_at`. 해결 상태 컬럼 **없음** |
| `IssueEntity` | 위 컬럼 그대로 |
| `IssueRepository` | `findByQaTryIdAndMessageId` 하나(멱등 흡수용) |
| `IssueService` | `recordAgentIssue` 저장 하나. 주석이 "저장만 노출한다"고 명시 |
| 컨트롤러 | **없음** |

`IssueService`의 클래스 주석은 "읽기는 향후 Report 작성 플로우가 이 서비스를 직접 호출하는
형태로 붙는다(그때 인가는 Report 소비자가 책임진다)"고 적어두었다. 이 계획은 그 예고를
HTTP 경로로 실현하되, **인가를 서비스 안으로 가져온다** — 소비자가 프로젝트 멤버십을 다시
구현하는 구조는 유출 지점을 늘린다.

제약:

- 인바운드 저장 경로(`QaAgentInboundRouter.routeIssue` → `IssueService.recordAgentIssue`)의
  동작은 바꾸지 않는다. 이 계획은 읽기·전이만 더한다.
- 오류는 `common/error`의 타입 예외로 던진다(`.agents/docs/error-handling.md`).
- id는 문자열(decimal)로 직렬화한다(`QaTryResponse` 관례).
- 접근 판정은 `QaTryRepository.findAccessibleById`와 **같은 조인 경로**를 쓴다:
  `qa_try → test_scenario → project_member`. 이슈에만 다른 규칙을 두지 않는다.

## 계약

### 상태 모델

`status`는 `OPEN` / `RESOLVED` 둘뿐이다. 세 번째 상태(WONTFIX 등)를 미리 두지 않는다 —
지금 화면이 묻는 질문은 "남았나 처리됐나" 하나다.

- `OPEN → RESOLVED`: `resolved_at`(서버 clock), `resolved_by`(app_user.id)를 채운다.
- `RESOLVED → OPEN`: 둘 다 NULL로 되돌린다. 되돌린 이력은 남기지 않는다(감사 로그는 non-goal).
- 같은 상태로의 재요청은 **멱등 성공**(204)이다. 토글 UI에서 더블클릭이 409로 보이는 것은
  사용자에게 알릴 가치가 없는 사건이다. `qa_try` 취소가 409를 쓰는 것과 갈리는데, 그쪽은
  "이미 끝난 실행을 다시 끝낼 수 없다"는 되돌릴 수 없는 전이라 다르다.

### 엔드포인트

| 메서드 | 경로 | 답 |
|---|---|---|
| GET | `/api/projects/{projectId}/issues?status=&severity=&beforeId=&size=` | `IssuePageResponse` |
| GET | `/api/qa-tries/{qaTryId}/issues?beforeId=&size=` | `IssuePageResponse` |
| POST | `/api/issues/{issueId}/resolve` | 204 |
| POST | `/api/issues/{issueId}/reopen` | 204 |

- `size`는 1..100, 기본 50. `QaTryController.logs`와 같은 가드를 쓴다.
- `beforeId` 커서 + `id DESC`. `QaLogPageResponse`와 같은 모양(`items`/`nextBeforeId`/`hasMore`).
- `status`/`severity`는 선택. 값이 열거에 없으면 400.
- 프로젝트 목록은 프로젝트 경로 아래, 런 목록은 `qa-tries` 아래에 둔다. 전이는 이슈 자신이
  자원이므로 `/api/issues/{id}`를 쓴다 — 이슈는 프로젝트에도 런에도 속하지만 그 둘 중
  하나를 URL에 박으면 화면마다 다른 경로로 같은 전이를 부르게 된다.

### 응답

```
IssueResponse {
  id: String, qaTryId: String,
  severity: String, title: String, detail: JsonNode,
  status: String, reportedAt: Instant, createdAt: Instant,
  resolvedAt: Instant?, resolvedBy: String?
}
IssuePageResponse { items: [IssueResponse], nextBeforeId: String?, hasMore: Boolean }
```

`projectId`는 싣지 않는다. 두 목록 모두 호출자가 이미 프로젝트를 알고 있는 경로다.

## Approach (Checklist)

- [ ] **Step 0: Recon** — `V12__create_issue.sql`, `IssueEntity/Repository/Service`,
      `QaTryRepository.findAccessibleById`, `QaTryController`(가드·파싱 관례),
      `KnowledgeVectorSearchRepository`(DatabaseClient 동적 절 패턴) 확인. *(완료)*

- [ ] **Step 1: 마이그레이션** — `V26__add_issue_resolution.sql`
      - `status VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RESOLVED'))`
      - `resolved_at TIMESTAMPTZ`, `resolved_by BIGINT REFERENCES app_user(id) ON DELETE SET NULL`
      - 기존 행은 DEFAULT로 전부 `OPEN`이 된다(하위 호환).
      - `resolved_by`는 `ON DELETE SET NULL` — 처리자가 탈퇴해도 "처리됐다"는 사실은 남아야 한다.
      - 부분 인덱스 `CREATE INDEX idx_issue_open ON issue (qa_try_id, id DESC) WHERE status = 'OPEN'`:
        화면의 기본 필터가 미해결이고, 해결된 행은 시간이 지날수록 다수가 된다.

- [ ] **Step 2: 엔티티·리포지토리**
      - `IssueEntity`에 `status` / `resolvedAt` / `resolvedBy` 추가. `status` 기본값은
        `IssueStatus.OPEN.name` — 인바운드 저장 경로가 값을 넘기지 않아도 그대로 동작해야 한다.
      - `IssueStatus` enum 신설(`IssueSeverity`와 같은 자리, `NAMES` 동반).
      - 조회는 **`IssueRepository` 한 곳**에 `@Query`로 둔다. 선택 필터는 `QaLogRepository.findPage`가
        이미 쓰는 `(:beforeId IS NULL OR id < :beforeId)` 관용구를 그대로 넓힌 것이고, 커서 유무로
        메서드를 쪼개지 않는다. (DatabaseClient 분리는 하지 않는다 —
        `KnowledgeVectorSearchRepository`가 그렇게 하는 이유는 `vector` 타입에 R2DBC 코덱이 없어서지
        선택 필터 때문이 아니다.)

        ```sql
        -- 프로젝트 단위. 멤버십은 여기서 조인하지 않는다 — 서비스가 ProjectAccessService로
        -- 먼저 판정하고 비참여자에게 404를 준다(아래 Step 3). 조인으로 걸면 비참여자가
        -- 200 + 빈 목록을 받아 "이슈가 없는 프로젝트"와 구분되지 않는다.
        SELECT i.* FROM issue i
          JOIN qa_try qt ON qt.id = i.qa_try_id
          JOIN test_scenario ts ON ts.id = qt.test_scenario_id
         WHERE ts.project_id = :projectId
           AND (:status IS NULL OR i.status = :status)
           AND (:severity IS NULL OR i.severity = :severity)
           AND (:beforeId IS NULL OR i.id < :beforeId)
         ORDER BY i.id DESC LIMIT :limit
        ```

        런 단위 목록은 `qa_try_id`로 좁힌 같은 모양이며, 접근 판정은 서비스가
        `QaTryRepository.findAccessibleById`로 먼저 한 뒤 부른다(이슈 쿼리가 조인을 두 번 하지 않게).
      - `@Modifying` 전이 두 개. `WHERE id = :id AND status = :expectedStatus`로 조건부 UPDATE하고
        영향 행 수를 돌려준다(`QaTryRepository.transition` 패턴). 0행은 "이미 그 상태"이므로 멱등
        성공으로 읽는다 — 존재/권한은 그 전에 이미 판정된다.

- [ ] **Step 3: 서비스** — `IssueService`에 읽기·전이 추가
      - `listByProject(projectId, userId, filters, cursor, size)`,
        `listByQaTry(qaTryId, userId, cursor, size)`,
        `resolve(issueId, userId)` / `reopen(issueId, userId)`.
      - 접근 불가·부재는 모두 `NotFoundException`. 존재 여부를 권한 없는 호출자에게 알리지 않는다
        (`QaTryService`의 `findAccessibleById → NotFound` 관례와 동일).
      - 접근 판정 자리가 셋 다 다르다:
        - 프로젝트 목록 — `ProjectAccessService.isMember(projectId, userId)`가 false면 404.
          `ProjectTestScenarioController`가 프로젝트 스코프 목록에서 쓰는 그 관례다.
          (`QaTryController.list`는 반대로 조인으로 걸러 빈 목록을 주지만, 그쪽 선례는 따르지
          않는다 — 화면이 "권한 없음"과 "이슈 없음"을 구분해야 한다.)
        - 런 목록 — `QaTryRepository.findAccessibleById`가 null이면 404.
        - 전이 두 개 — 이슈 → `qa_try_id`로 같은 접근 판정을 한 뒤 UPDATE.
      - 클래스 주석의 "저장만 노출한다" 문단을 새 책임에 맞게 고친다.

- [ ] **Step 4: DTO·컨트롤러**
      - `IssueDtos.kt`: `IssueResponse`, `IssuePageResponse`.
      - `IssueController`(`/api/issues`, 전이)와 `ProjectIssueController`
        (`/api/projects/{projectId}/issues`) 둘. 이 갈래는 `TestScenarioController` /
        `ProjectTestScenarioController` 선례와 같다.
      - 런 단위 목록(`GET /api/qa-tries/{id}/issues`)은 **`QaTryController`에 얹는다**.
        같은 자리의 `logs`가 이미 다른 도메인(`QaLogService`)에 위임하는 형태이고, 컨트롤러를
        하나 더 만들 근거가 되지 못한다.
      - `parseId` / `requireUser` 가드는 `QaTryController`와 동일한 형태로 맞춘다.

- [ ] **Step 5: 테스트** — `IssueIntegrationTest`(기존 파일 확장)
      - 런 단위 목록이 최신순으로 오고 커서가 이어진다
      - 프로젝트 단위 목록이 여러 런의 이슈를 모으고, `status`/`severity` 필터가 걸린다
      - `resolve`가 `resolvedAt`/`resolvedBy`를 채우고, 재요청이 204로 멱등하다
      - `reopen`이 둘을 NULL로 되돌린다
      - 다른 프로젝트 사용자의 조회·전이가 404다
      - 잘못된 `status`/`severity`/`size`가 400이다

- [ ] **Step 6: Rollout** — 마이그레이션은 컬럼 추가뿐이라 무중단. 롤백은 `V26` 되돌리기
      (컬럼 DROP)이며, 그 사이 기록된 해결 표시는 사라진다. 배포 순서는
      **Orchestration → Agent → home**: 프레임은 이미 받아지고 있고, 화면은 이 API가 있어야 뜬다.

- [ ] **Step 7: Insomnia** — `insomnia-sync` 스킬로 컬렉션 갱신(신규 4개 엔드포인트).

## Validation

- **Commands to run:** `./gradlew test --tests '*IssueIntegrationTest*'`, 이후 `./gradlew test`
- **Expected output:** 전부 통과. Testcontainers(Postgres/Redis)가 뜨는 환경이어야 한다.

## Risks & Rollback

- **Risks**
  - 프로젝트 단위 목록이 `issue → qa_try → test_scenario → project_member` 4중 조인이다.
    현재 데이터량에서는 문제가 없지만, 이슈가 수십만 행이 되면 `project_id`를 `issue`에
    비정규화하는 선택지가 남는다. 지금 하지 않는다(추측성 최적화).
  - `resolved_by`가 `app_user`를 참조하므로 그 테이블 이름·PK가 바뀌면 함께 깨진다.
    V2에서 만든 이름을 그대로 쓴다.
- **Rollback steps:** `git revert` + `V26` 역마이그레이션. 화면(ARTEL-247)이 먼저 배포되면
  404를 받으므로 배포 순서를 지킨다.

## Rejected feedback

- **인덱스를 `(qa_try_id, severity, id DESC)`로 넓히자**(fast). 넓히지 않는다. 화면의 기본이자
  거의 유일한 상시 필터는 해결 여부이고, severity는 사용자가 가끔 좁히는 축이다. 아직 오지 않은
  질의 형태에 맞춰 인덱스를 미리 넓히는 것은 추측성 최적화다.
- **`resolved_by`의 `app_user.id` 타입 확인**(medium). 확인했다 — `V2`의 `BIGSERIAL PRIMARY KEY`,
  즉 BIGINT다. 계획대로 간다.

## Open Questions

- 없음. 다중 severity 필터(체크박스 여러 개)는 화면이 요구하면 그때 배열 파라미터로 넓힌다 —
  지금 단일 선택으로 충분하다.
