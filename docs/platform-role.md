# Platform role

`app_user.platform_role` 은 프로젝트 밖의 등급이다. 값은 `USER`, `DEVELOPER`, `ADMIN` 세 개이고
기본값은 `USER` 다.

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

## ADMIN 이 여는 것

`ADMIN` 은 위 표의 조회를 `DEVELOPER` 와 똑같이 연다(`PlatformAccessService.seesAllProjects`). 거기에
더해 `/api/admin` 아래 경로를 연다. `USER` 와 `DEVELOPER` 는 그 경로에서 403 `admin_required` 를 받는다.

| 경로 | 무엇 |
|---|---|
| `GET /api/admin/users` | 사용자 전부 |
| `POST /api/admin/users` | 계정을 만들고 임시 비밀번호를 한 번 돌려준다 |
| `POST /api/admin/users/:userId/reset-password` | 비밀번호를 새 임시 비밀번호로 바꾼다 |
| `PATCH /api/admin/users/:userId` | `platformRole` 과 `disabled` 를 바꾼다 |
| `GET /api/admin/settings/llm` | OpenRouter API key 의 출처와 끝 네 글자, 마지막 확인 결과 |
| `PUT /api/admin/settings/llm` | 관리 화면의 key 를 넣거나 지운다 |
| `POST /api/admin/settings/llm/check` | 그 key 로 필요한 model 에 닿는지 OpenRouter 에 묻는다 |

프로젝트 쓰기는 `DEVELOPER` 와 같이 열지 않는다. `ADMIN` 도 참여하지 않은 프로젝트를 지우거나 기획서를
올리지 못한다.

## 등급을 주는 방법

**첫 이메일 가입자가 `ADMIN` 이다.** `POST /api/auth/signup` 이 `app_user` 가 비어 있는 것을 보면 그
계정을 `ADMIN` 으로 만든다. 판정과 행 삽입은 한 트랜잭션 안에서 `pg_advisory_xact_lock` 뒤에 있어,
두 가입이 동시에 와도 `ADMIN` 은 하나다(`FirstUserGate`). 그 뒤로 공개 가입은 `ARTEL_SIGNUP_OPEN=true`
일 때만 열리고, 들어오는 계정은 `USER` 다.

GitHub 로 처음 들어온 사람은 `app_user` 가 비어 있어도 `ADMIN` 이 되지 않는다. 테스트 스위트의
여러 클래스가 `app_user` 를 비우고 GitHub 신원으로 사용자를 만들기 때문이다.

그 뒤의 등급은 `ADMIN` 이 `PATCH /api/admin/users/:userId` 로 바꾼다.

```json
{ "platformRole": "DEVELOPER" }
```

남은 하나뿐인 `ADMIN` 을 내리거나 막는 요청은 409 `last_admin` 이다. 허락하면 설치에 관리자가 없어지고,
되돌리는 길이 DB 를 직접 고치는 것뿐이다.

`ADMIN` 이 하나도 없는 설치(이 기능 전에 사람이 들어온 stage 와 운영, GitHub 로만 들어온 설치)는
여전히 DB 에서 직접 준다.

```sql
-- 누가 어떤 등급인지 먼저 본다. GitHub 로그인으로 사람을 찾는다.
SELECT u.id, u.display_name, u.platform_role, i.provider, i.login
  FROM app_user u
  JOIN oauth_identity i ON i.app_user_id = u.id
 WHERE i.provider = 'github' AND i.login = '<github-login>';

-- 올린다.
UPDATE app_user SET platform_role = 'ADMIN', updated_at = NOW() WHERE id = <app_user_id>;

-- 내린다.
UPDATE app_user SET platform_role = 'USER', updated_at = NOW() WHERE id = <app_user_id>;
```

내리면 즉시 반영된다. 등급은 JWT claim 이 아니라 요청마다 DB 에서 읽기 때문이다
(`PlatformAccessService`). claim 에 실었다면 access 토큰이 만료될 때까지(15분) 그 사람이 계속 전체를
봤을 것이다. `disabled` 와 `must_change_password` 도 같은 이유로 요청마다 DB 에서 읽는다
(`AccountStateWebFilter`).

## 막힌 계정과 임시 비밀번호

- `disabled` 가 true 인 계정은 로그인과 재발급이 거절되고, 아직 살아 있는 access 토큰도 403
  `account_disabled` 를 받는다. GitHub 로그인은 `/login?error=disabled` 로 돌아간다.
- `ADMIN` 이 만든 계정과 초기화한 비밀번호는 `must_change_password` 로 시작한다. 그동안
  `GET /api/auth/me` 와 `POST /api/auth/password` 말고는 전부 403 `password_change_required` 다.
  로그아웃, 재발급, 로그인, 가입, `GET /api/auth/providers` 는 세션을 끝내거나 여는 길이라 막지 않는다.

## admin-page 와 artel-home 은 같은 세션을 쓴다

admin-page 에는 자체 로그인이 없다. `VITE_HOME_URL` 로 보내 artel-home 이 만든 `aud=artel-home`
쿠키를 그대로 받는다. 그래서 `DEVELOPER` 는 admin-page 에서만이 아니라 artel-home 에서도
`DEVELOPER` 다.

넓히는 범위를 조회로 묶어 둔 것이 그 사실을 감당하는 방법이다. 나중에 개발자 전용 **쓰기**가
필요해지면 그때는 범위가 아니라 audience 를 갈라야 한다 — admin-page 전용 로그인을 만들어
`aud=artel-admin` 토큰에서만 그 권한이 서게 한다. 지금 그것을 하지 않은 것은 로그인 흐름을 한 벌 더
만드는 값에 비해 얻는 것이 작기 때문이다.

`ADMIN` 의 `/api/admin` 쓰기는 이 결정을 그대로 두고 같은 세션에 실었다. 그 경로는 등급을 요청마다
DB 에서 읽고, 마지막 `ADMIN` 을 지키며, key 원문을 어느 응답에도 싣지 않는다. 세션 하나가 새어 나가면
그 사람의 `ADMIN` 권한이 함께 나간다는 위험은 남아 있고, admin-page 전용 audience 가 그것을 줄이는 다음
걸음이다.
