# 2026-08-13 — ISSUE 프레임에도 답한다

- Date: 2026-08-13
- Jira: ARTEL-366
- Status: Implemented

## Goal

이슈 보고가 성공하면 저장된 id를 실어 답하고, 거절되면 요청의 correlation을 문 ERROR로 답한다.
ARTEL-331이 지식 쓰기에 세운 계약을 이슈에도 적용하는 것이다.

## Non-goals

- Agent 쪽 수신 처리와 `report_issue`의 결과 문구 — 별도.
- 이슈 스키마·severity 사다리 변경.
- `report_issue`의 로컬 검증 제거.

## 결정

### 결정 1 — 응답 타입은 새로 만든다. `ISSUE_RESULT`.

`KNOWLEDGE_WRITE_RESULT`를 일반화해 같이 쓰지 않는다. payload 모양은 똑같지만(요청 타입 echo +
id 하나) 그 이름은 **지식 쓰기 한 가족**을 뜻하고 이슈는 다른 도메인이다 — 테이블도 수명도
소비자도 다르다. 이름을 넓히면 그 뜻이 사라지고, 사라진 뒤에는 다음 사람이 아무 프레임의 답이나
그 타입으로 보내게 된다.

ARTEL-332가 아직 리뷰 중이라는 사정도 있다. 그 타입 이름을 지금 바꾸면 진행 중인 PR을 기능 이득
없이 흔든다.

### 결정 2 — 저장 실패도 거절로 답한다

`recordAgentIssue`는 1 MiB 상한을 `require`로 걸어 예외를 던진다. 그 전에는 그 예외가 receive
체인 밖으로 나가 **런을 죽였다** — 보고 하나가 실행을 끝내는 것은 이 파일의 다른 어떤 경로도
하지 않는 일이다. 값으로 바꿔 거절로 답한다.

계획에 없던 것이고, 응답을 붙이며 그 경로를 읽다 발견했다.

### 결정 3 — 멱등은 이미 있고, 응답까지 오는지만 확인한다

`uk_issue_message`가 `(qa_try_id, message_id)`에 걸리고 `IssueService`가 위반을 기존 행으로
되돌린다. 필요한 것은 `recordAgentIssue`가 그 행의 id를 **돌려주게** 하는 것뿐이었다(원래 Unit).
재전송이 같은 id로 두 번 다 성공하는지가 테스트로 고정됐다.

## Approach (Checklist)

- [x] **Step 0: Recon** — `routeIssue`, `IssueService.recordAgentIssue`, `uk_issue_message`(V12)
- [x] **Step 1: 서비스** — `recordAgentIssue`가 저장된 id를 돌려준다
- [x] **Step 2: 라우터** — `ISSUE_RESULT_TYPE`, `routeIssue`가 `qaTry`를 받아 성공·거절 모두 답한다,
      `rejectIssue` 헬퍼
- [x] **Step 3: 테스트** — `IssueResultRouterIntegrationTest`

## Validation

- `./mvnw -o test -Dtest=IssueResultRouterIntegrationTest` — 5/5
- `./mvnw -o test -Dtest="Issue*"` — 14/14
- `./mvnw -o test` — **488/488**

기존 `IssueIntegrationTest`가 **수정 없이** 통과한다. 그 스위트는 세션 없이 시드하므로 프레임이
나가지 않는 경로를 그대로 탄다 — 구버전 Agent 호환의 회귀 방어다.

## Risks & Rollback

- **Risks:**
  - `ISSUE_RESULT`를 읽지 않는 Agent에 프레임이 하나 더 내려간다. `deliver`가 모르는 타입에
    `False`를 돌려주며 흘리므로 런에 영향이 없다.
  - 라우팅 전에 버려지는 프레임은 여전히 답이 없다. ARTEL-331과 같은 구멍이고 같은 이유로 남긴다.
- **Rollback steps:** `git revert`. 스키마·마이그레이션 없음.

## Open Questions

- 없음.
