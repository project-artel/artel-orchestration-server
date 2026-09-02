# Platform role

`app_user.platform_role` 은 프로젝트 밖의 등급이다. 값은 `USER` 와 `DEVELOPER` 두 개이고 기본값은
`USER` 다.

`project_member` 의 `OWNER` 와 `MEMBER` 는 한 프로젝트 안에서 무엇을 할 수 있는지를 정한다. 그
층으로는 "모든 프로젝트를 본다" 를 쓸 수 없다 — 그 문장은 프로젝트 하나에 관한 말이 아니기
때문이다. 이 컬럼이 그 자리다.

## DEVELOPER 가 여는 것

조회뿐이다. 참여하지 않은 프로젝트에 대해 다음이 열린다.

| 경로 | 무엇 |
|---|---|
| `GET /api/qa-stats` | QA 런을 실행 설정 4-튜플로 접은 집계 |
| `GET /api/qa-tries` | 프로젝트의 QA 실행 목록 |
| `GET /api/llm-usage/stats` | 지출을 service·model·project·일자로 접은 집계 |
| `GET /api/llm-usage/qa-runs` | QA 런 한 건씩의 토큰과 비용 |
| `GET /api/llm-usage/qa-runs/:qaTryId` | 그 목록의 단건 |
| `GET /api/knowledge-stats` | 지식 버전 집계 |
| `GET /api/projects/:projectId/knowledge-graph` | 지식 그래프 |
| `GET /api/projects/:projectId/test-scenario` | 시나리오 목록 |
| `GET /api/projects/:projectId/test-scenario/:testScenarioId` | 시나리오 단건 |
| `GET /api/projects?scope=ALL` | 삭제되지 않은 전 프로젝트 |

## DEVELOPER 가 열지 않는 것

**쓰기는 전부 그대로다.** 프로젝트 삭제, 기획서 업로드, 시나리오 수정과 승인과 삭제,
`PUT /api/test-scenario/:testScenarioId/expected-labels` 는 이 등급과 무관하게 `project_member` 행을
요구한다.

기대 판정 라벨을 특히 열지 않은 것은 없어서 못 한 것이 아니라 열지 않기로 한 것이다. 그 라벨은 QA
화면의 미탐과 오탐 숫자가 대조하는 정답지라, 그 프로젝트에 참여하지 않은 사람이 남의 벤치마크
기준을 고칠 수 있게 만들지 않는다.

코드에서 이 경계를 지키는 것은 두 쌍의 함수다.

- `ProjectAccessService.requireMember` 는 등급을 모른다. 프로젝트 삭제와 기획서 업로드가 그 함수를
  거쳐 가므로, 거기서 등급을 통과시키면 조회를 열려던 한 줄이 쓰기까지 연다
- `TestScenarioAccessService` 는 `accessibleScenario`(쓰기)와 `readableScenario`(읽기)로 갈라져
  있다. 앞의 것만 멤버십을 요구한다

새 호출부를 붙일 때 그 자리가 무엇을 하는지 보고 고른다.

`GET /api/llm-usage/stats` 의 `unattributedCalls` 는 등급과 무관하게 건수로만 나간다. 그 행들은
`reference_id` 가 비었거나 가리키던 행이 지워져 어느 프로젝트의 지출인지 모르는 것들이라, 등급이
정하는 "어느 프로젝트를 보느냐" 로는 열 수도 닫을 수도 없다.

## 등급을 주는 방법

주는 화면도 API 도 없다. 운영 DB 에 직접 친다.

```sql
-- 누가 어떤 등급인지 먼저 본다. GitHub 로그인으로 사람을 찾는다.
SELECT u.id, u.display_name, u.platform_role, i.provider, i.login
  FROM app_user u
  JOIN oauth_identity i ON i.app_user_id = u.id
 WHERE i.provider = 'github' AND i.login = '<github-login>';

-- 올린다.
UPDATE app_user SET platform_role = 'DEVELOPER', updated_at = NOW() WHERE id = <app_user_id>;

-- 내린다.
UPDATE app_user SET platform_role = 'USER', updated_at = NOW() WHERE id = <app_user_id>;
```

내리면 즉시 반영된다. 등급은 JWT claim 이 아니라 요청마다 DB 에서 읽기 때문이다
(`PlatformAccessService`). claim 에 실었다면 access 토큰이 만료될 때까지(15분) 그 사람이 계속 전체를
봤을 것이다.

화면을 만들지 않은 이유는 등급을 주는 일이 드물고, 그 화면 자체가 지켜야 할 또 하나의 쓰기
경로이기 때문이다. 등급을 주는 일이 잦아지면 그때 만든다.

## admin-page 와 artel-home 은 같은 세션을 쓴다

admin-page 에는 자체 로그인이 없다. `VITE_HOME_URL` 로 보내 artel-home 이 만든 `aud=artel-home`
쿠키를 그대로 받는다. 그래서 `DEVELOPER` 는 admin-page 에서만이 아니라 artel-home 에서도
`DEVELOPER` 다.

넓히는 범위를 조회로 묶어 둔 것이 그 사실을 감당하는 방법이다. 나중에 개발자 전용 **쓰기**가
필요해지면 그때는 범위가 아니라 audience 를 갈라야 한다 — admin-page 전용 로그인을 만들어
`aud=artel-admin` 토큰에서만 그 권한이 서게 한다. 지금 그것을 하지 않은 것은 로그인 흐름을 한 벌 더
만드는 값에 비해 얻는 것이 작기 때문이다.
