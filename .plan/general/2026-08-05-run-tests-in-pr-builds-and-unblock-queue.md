# 2026-08-05 — PR 빌드에서 테스트를 돌리고 큐 적체를 막는다

- Date: 2026-08-05
- Jira: ARTEL-264
- Status: Completed

## Goal

`Jenkinsfile`이 PR을 실제로 검증하게 만들고, 멈춘 빌드가 executor를 무기한 점유해
뒤따르는 PR 체크가 pending에 갇히는 경로를 없앤다.

- `Build` 스테이지가 테스트를 실행한다. 실패하면 PR 체크가 빨강이 된다.
- 빌드에 상한 시간이 붙어 멈춘 빌드가 executor를 반납한다.
- 브랜치당 빌드가 하나만 돈다.
- `docker build` 컨텍스트에서 `target/` 산출물(jar 제외), `.git`, 플랜·에이전트
  문서를 뺀다.

## Non-goals

- Jenkins 인스턴스 설정(노드, executor 수, 플러그인). 저장소 밖 작업이다.
- `Dockerfile`을 멀티스테이지로 바꿔 Maven 빌드를 컨테이너 안으로 옮기는 것.
  통합 테스트가 Testcontainers를 쓰므로 그 안에서 테스트를 돌리려면 DinD가 필요하다.
- 실제 Postgres 업그레이드 경로 검증 — ARTEL-263.
- Flyway 버전 충돌 검사 — ARTEL-262(PR #80).
- 배포 브랜치 규칙(`resolveTargetEnv`) 변경.
- 다른 저장소.

## Context / Constraints

현재 `Jenkinsfile`의 `Build`는 `./mvnw clean package -DskipTests`다. `src/test`에
테스트 45개가 있으나 CI에서 한 번도 실행되지 않는다. PR 체크가 초록이 되어도
검증된 것은 컴파일뿐이다.

같은 Jenkins에서 agent-server는 정상 동작한다. 차이는 파이프라인 구조가 아니라
**한 빌드가 executor를 무는 시간과 캐시가 사는 위치**다.

| | agent-server | orchestration-server |
|---|---|---|
| 캐시 | Docker layer, 데몬 전역 | Maven `target/`, 워크스페이스 지역 |
| 브랜치 전환 시 | 재사용됨 | 멀티브랜치가 워크스페이스를 새로 파므로 콜드 |
| `clean` | 없음 | 매 빌드 실행 |
| `.dockerignore` | 있음 | 없음 (컨텍스트 91MB) |

### 제약

- 통합 테스트는 Testcontainers로 PostgreSQL과 Redis를 띄운다. 에이전트의 docker
  소켓이 필요하다. `Deploy` 스테이지가 이미 그 소켓으로 `docker run`을 하므로
  존재 자체는 확인되지만, Ryuk 기동은 실제 파이프라인에서만 검증된다.
- `Dockerfile`은 `ARG JAR_FILE=target/*.jar`를 복사한다. `.dockerignore`가
  `target/`을 통째로 제외하면 배포 빌드가 깨진다. 부정 패턴으로 jar만 남겨야 한다.
- ARTEL-262(PR #80)가 같은 파일에 `Flyway Migration Check` 스테이지를 추가한다.
  두 변경은 스테이지가 달라 의미 충돌은 없지만 텍스트는 겹친다. 그래서 이 브랜치는
  develop이 아니라 **#80 위에 쌓았고**, PR도 #80의 브랜치를 base로 연다.
  충돌은 이 브랜치에서 이미 해소했다.

  해소하면서 `Flyway Migration Check`의 주석 한 줄을 고쳤다. "Build는 -DskipTests라
  마이그레이션을 한 번도 실행하지 않으므로 이 스테이지가 유일한 지점"이라는 근거가
  이 변경 뒤에는 사실이 아니게 된다. 검사 자체는 여전히 필요하다 — 빈 DB에서는 어떤
  순서도 어떤 checksum도 성공하므로 순서 엉킴과 병합된 마이그레이션 변조는 테스트로
  잡히지 않는다. 주석을 그 근거로 바꿨다.

### 기각한 대안

- **`disableConcurrentBuilds(abortPrevious: true)`** — PR 피드백만 보면 옳지만
  배포 브랜치에서 위험하다. `Deploy`는 `docker stop && docker rm && docker run`
  세 단계이고 원자적이지 않다. `rm`과 `run` 사이에서 중단되면 컨테이너가 사라진
  채로 남아 서비스가 죽는다. 인자 없는 `disableConcurrentBuilds()`만 쓴다. 같은
  잡의 큐 항목은 Jenkins가 하나로 합치므로 적체 방지 효과는 그대로 얻는다.
- **`clean` 제거** — 증분 컴파일로 빌드가 빨라지지만, 삭제된 소스의 클래스 파일이
  `target/`에 남아 CI만 통과하는 상태를 만들 수 있다. 지배적인 비용은 컴파일이
  아니라 테스트와 의존성 해석이므로 이득 대비 위험이 크다.
- **`Dockerfile` 멀티스테이지로 Maven 캐시를 레이어로 옮기기** — agent-server와
  같은 구조가 되지만, `docker build` 안에는 docker 데몬이 없어 Testcontainers가
  돌지 않는다. 테스트를 호스트에 두고 빌드만 컨테이너로 옮기면 컴파일이 두 번
  일어난다. 별도 판단으로 남긴다.

## Approach (Checklist)
- [x] **Step 0: Recon** — `Jenkinsfile`, `Dockerfile`, `pom.xml` surefire 설정,
      `src/test/.../support/DockerEnvironment.kt`, agent-server `Jenkinsfile`·
      `.dockerignore` 비교. PR #80 diff 확인.
- [x] **Step 1: Implementation**
      - `Jenkinsfile`: `agent any` 아래 `options` 블록 추가
        (`disableConcurrentBuilds()`, `timeout(45, MINUTES)`,
        `buildDiscarder(logRotator(numToKeepStr: '20'))`).
      - `Jenkinsfile`: `Build` → `Build & Test`, 명령을
        `./mvnw -B clean verify`로 교체. `-DskipTests` 제거.
      - `.dockerignore` 신규. `target/*` 제외 + `!target/*.jar` 복원.
- [x] **Step 2: Tests** — 로컬에서 `./mvnw -B clean verify`. `.dockerignore`
      적용 후 `docker build`가 jar를 찾는지 확인.
- [ ] **Step 3: Rollout / Rollback** — 브랜치 푸시 후 Jenkins 스테이지가 실제로
      테스트를 돌고 체크가 pending에서 풀리는지 확인. 문제가 생기면 revert.

## Validation

### 실행한 것

- `./mvnw -B clean verify` — **실패**. `Tests run: 318, Errors: 239`.
  원인은 이 변경이 아니라 develop 자체다.

  ```text
  Caused by: org.flywaydb.core.api.FlywayException: Found more than one migration with version 26
  ```

  `V26__add_issue_resolution.sql`과 `V26__create_knowledge_history_and_usage.sql`이
  둘 다 develop에 있다. 컨텍스트 로딩이 Flyway 단계에서 죽어 통합 테스트가 전멸한다.
  ARTEL-260(PR #78)이 고치는 중이며, 이 변경이 없어서 아무도 병합 전에 보지 못한
  바로 그 사고다.

- ARTEL-260 브랜치를 로컬에만 얹고 재실행 — **통과**. `Tests run: 318, Failures: 0,
  Errors: 0`, `BUILD SUCCESS`, `Total time: 01:37 min`. 검증 뒤 병합은 되돌렸고
  이 브랜치에는 남기지 않았다.

- `docker build -t artel-orchestration-server:dockerignore-check .` — 통과.
  컨텍스트 전송이 `83.46MB`(fat jar 하나)로, 97MB 워크트리에서 `.git`, `src/`,
  `docs/`, `.plan/`, `target/`의 클래스·test-classes가 빠졌다.
  `COPY target/*.jar app.jar`는 그대로 성공한다. `*.jar.original`은 두 패턴
  어디에도 걸리지 않아 이미지에 들어가지 않는다.

### 실행하지 않은 것

- Jenkins 에이전트에서의 실제 실행. Testcontainers 기동과 45분 상한의 적정성은
  첫 파이프라인 빌드에서만 확인된다.
- `plan-review`·`pair-review` 스킬. 두 스킬은 서브에이전트를 띄우는데 이 세션은
  에이전트 호출이 막혀 있어 리뷰를 인라인으로 대신했다.

## Risks & Rollback
- **Risks:**
  - **이 PR의 체크는 ARTEL-260(PR #78)이 병합되기 전까지 빨강이다.** develop의
    V26 중복 때문이다. 병합 순서는 #78 → #80 → 이 PR이다. 이 PR이 develop에 먼저
    닿으면 develop이 고쳐질 때까지 모든 브랜치의 빌드가 실패한다.
  - #80 위에 쌓았으므로 #80이 바뀌면 이 브랜치를 리베이스해야 한다. #80이 병합되면
    base가 자동으로 develop으로 내려간다.
  - Jenkins 에이전트에서 Testcontainers가 뜨지 않으면 지금까지 통과하던 빌드가
    전부 실패한다. `DockerEnvironment.verify()`가 원인을 명시적으로 던지므로
    진단은 되지만, 첫 배포 브랜치 빌드가 막힐 수 있다.
  - 테스트가 붙어 빌드 시간이 늘어난다. 통합 테스트가 컨텍스트마다 커넥션 풀을
    잡으므로 콜드 워크스페이스에서는 `timeout` 45분에 근접할 수 있다.
  - PR #80과 `Jenkinsfile` 충돌. 나중에 병합되는 쪽이 해소한다.
- **Rollback steps:** `git revert`. 파이프라인 3파일 변경뿐이고 런타임 코드와
  스키마는 건드리지 않으므로 되돌림이 즉시 유효하다.

## Open Questions
- Jenkins 에이전트에서 Ryuk이 기동하는가. 실패하면
  `TESTCONTAINERS_RYUK_DISABLED=true`를 파이프라인 `environment`에 넣어야 한다.
  실제 빌드 로그를 보기 전에는 확정할 수 없다.
