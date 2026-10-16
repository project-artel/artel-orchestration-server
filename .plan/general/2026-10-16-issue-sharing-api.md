# 2026-10-16 — QA 이슈 기반 팀 공유 게시판 API

- Date: 2026-10-16
- Jira: https://artel-asm.atlassian.net/browse/ARTEL-1114
- Status: Implementation complete; local validation recorded
- Branch: `feat/issue-sharing-api-ARTEL-1114`
- Base: `origin/develop`의 `cc5f0e21`

## Goal

프로젝트 구성원이 QA 보고서를 연결한 글과 댓글, 멘션, 구독, 반응 및 결정 댓글을 저장하고 공유한다. 논의 종료와 QA 결함 해결 상태를 독립적으로 유지한다.

## Approach (Checklist)

- [x] 기존 issue/member/access API와 승인 목업 조사 및 계약 검토.
- [x] `issue/discussion`의 controller/service/repository/DTO 및 `V117__create_issue_discussions.sql` 구현.
- [x] 원본 보고서 단건 API에서 기존 `IssueService.page` 응답 조립 재사용.
- [x] PostgreSQL HTTP 통합 테스트, migration 검증, 실제 브라우저 연결.
- [x] pair review 및 계획 최종 반영.

## API Contract

기본 경로는 `/api/projects/{projectId}/issue-discussions`다. 전체 schema는 `docs/api/openapi.json`에 기록했다.

| Method / 경로 | 동작 |
|---|---|
| GET / | state, mine, subscribed, issueId, query, cursor, size로 목록 조회 |
| POST / | 제목·본문·목적·이슈·멘션과 clientRequestId로 생성 |
| GET /issue-options | 큰 보고서 본문 없이 이슈 검색·페이지 조회 |
| GET /{discussionId} | 본문·연결 이슈·결정 댓글·권한 조회 |
| PATCH /{discussionId} | version 조건으로 제목·본문·목적·링크·멘션 편집 |
| PUT /{discussionId}/state | OPEN/CLOSED 설정 |
| PUT /{discussionId}/pin | OWNER가 고정 여부 설정 |
| PUT /{discussionId}/decision | 같은 글의 commentId 또는 null 지정 |
| GET, POST /{discussionId}/comments | 댓글 페이지 조회 및 생성 |
| PATCH /{discussionId}/comments/{commentId} | 본인 댓글 수정 및 version 검사 |
| PUT /{discussionId}/subscription | 본인의 구독 여부 설정 |
| PUT /{discussionId}/comments/{commentId}/reaction | 본인의 댓글 반응 여부 설정 |
| GET /api/projects/{projectId}/issues/{issueId} | 원본 IssueResponse 단건 조회 |

- `purpose`: SHARE/QUESTION/DECISION. `state`: OPEN/CLOSED. ID는 decimal string, 시간은 ISO-8601이다.
- 페이지는 `{items,nextCursor,hasMore}`. 기본 30개, 최대 100개. 글은 pinned/id 내림차순(`1:42` 형식 cursor), 댓글은 id 오름차순이다.
- 제목 200자, 본문 20,000자, 댓글 10,000자, 이슈 링크·멘션 각 50개, 검색어 200자로 제한한다.
- 목록은 excerpt·작성자·개수·상태·최근 활동 시각만 싣는다. 연결 보고서 detail은 필요할 때 별도 조회한다. 집계·작성자·링크를 배치 조회한다.
- 모든 접근에서 현재 project membership과 soft deletion을 검증한다. 작성자/OWNER만 글 편집·논의 종료·결정 지정을, OWNER만 고정을, 댓글 작성자만 댓글 편집을 할 수 있다.
- 연결 이슈와 멘션 사용자는 같은 프로젝트에 속해야 한다. 결정 댓글은 composite FK로 같은 글에 속함을 검증한다.
- 글·댓글 생성은 UUID `clientRequestId`와 정규화된 최초 payload의 SHA-256을 보관한다. unique 제약과 transaction 안의 `INSERT ON CONFLICT DO NOTHING`으로 동시 재전송을 처리한다. 같은 payload는 기존 결과, 다른 payload는 409다.
- 본문 편집 version과 state/pin/decision의 필드 UPDATE를 분리했다. 글 생성 재시도가 후속 편집이나 구독 변경을 되돌리지 않는다.
- 본문은 plain text다. reaction/subscription은 명시적인 enabled 값으로 설정한다.

## Validation

- `./mvnw -q -Dtest=DiscussionHttpIntegrationTest,IssueHttpIntegrationTest,IssueIntegrationTest test`: **24개 통과**. 새 HTTP 테스트 8개에 접근 경계, 권한 회수, soft deletion, 재전송·동시 생성, version 충돌, 결정 FK, 페이지·검색 상한, 댓글 편집, 가벼운 이슈 선택기가 포함된다.
- `./mvnw -q test`: **2,099개 중 실패 108개**. 변경 전 `cc5f0e21`을 별도 임시 디렉터리에서 실행하면 **2,091개 중 동일한 테스트 108개 실패**. class/test 이름 집합 비교에서 추가 실패 0개. 기존 content-map assertion과 여러 HTTP 테스트의 context/connection 오류는 범위 밖이다.
- 기존 OpenAPI와 의미 비교: 새 경로 11개, 기존 경로 변경·삭제 0개.
- 최신 원격 refs 확인 후 migration version collision 검사 통과. `verify-flyway-upgrade.sh`의 base 적용 → V117 적용 → **97개 migration validate 통과**.
- `git diff --check` 통과. 새 Kotlin 파일은 ktfmt Kotlinlang style로 정리했다.

## Context / Constraints

- 승인된 QA 이슈 팀 공유 목업을 구현했다. 기존 checkout과 다른 세션의 branch, 개발 서버는 변경하지 않았다.
- Jira Subtask 생성 화면에 `customfield_10080`, `customfield_10081`, `customfield_10015`, `duedate`가 없어 저장소별 Subtask 생성이 거부되었다. 일반 작업으로 우회하지 않았다.
- 사용자가 “분리된 워크트리로부터 브랜치를 바꾼 후 작업 완수될때까지 진행”하도록 명시하여, 이번 로컬 작업에 한해 Story 키를 사용한 별도 branch에서 구현했다. Jira는 진행 중이며 Subtask 설정은 관리자 작업으로 남아 있다.
- 공개 게시판, 외부 알림, 파일 첨부, AI 요약, agent-server 수정은 포함하지 않는다. 멘션은 프로젝트 구성원 표시, 구독은 구독한 글 필터를 제공한다.

## Review

`plan-review` Fast 검토의 계약·재전송·동시성 지적을 반영하고 Medium/Heavy 검토를 통과했다. `pair-review`에서 서버 댓글 수정 시 최근 활동 갱신, 중복 index 제거, 댓글 편집과 이슈 선택기 테스트를 보완했다. 화면은 댓글 작성·수정 뒤 이미 읽은 페이지 유지와 primary 버튼 대비를 수정해 재검토를 통과했다. 초기 댓글 조회 중 제출도 차단했다.

## Local Preview

- 별도 frontend `http://localhost:5184/projects/2/issues?tab=team`, backend `8094`/`8095`.
- 별도 PostgreSQL `5544`, Redis `6384`, 검증 프로젝트 `2`와 예시 QA 보고서 80개. 기존 DB와 분리되어 있다.
- 인증은 `artel-jwt` skill로 발급하고 별도 cookie 이름을 사용했다. 자격 증명은 저장소 밖에 둔다.
- 실제 PostgreSQL/API에 쓰고 다시 읽는 브라우저 검증이다. QA 실행 자체나 외부 tracker 전송을 검증한 것은 아니다.
- 검증 이미지·로그는 `/private/tmp/artel-team-work/`에만 두며 Git에 추가하지 않는다.

## Rollout / Rollback

서버의 추가 API와 migration을 먼저 배포한 뒤 home을 배포한다. 로컬 구현 완료이며 배포나 merge는 하지 않았다. UI를 이전 버전으로 되돌리면 기존 이슈 조회를 계속 사용할 수 있다. 배포된 migration은 삭제하지 않고 추가 테이블과 데이터를 보존한다.
