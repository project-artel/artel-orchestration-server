# 2026-07-22 — Jenkins 배포에 env 파일 주입

- Date: 2026-07-22
- Jira: ARTEL-74
- Status: Completed

## Goal

Jenkins Deploy 스테이지가 Jenkins Credentials에 Secret file로 등록된 `.env`를 받아
`docker run --env-file`로 컨테이너에 주입한다. 시크릿은 저장소에도 이미지 레이어에도
남기지 않는다.

자격 증명 ID는 `${APP_NAME}-env-${TARGET_ENV}` 규칙으로 조합한다.

| TARGET_ENV | Credential ID |
|---|---|
| `stage` | `artel-orchestration-server-env-stage` |
| `operation` | `artel-orchestration-server-env-operation` |

즉 **자격 증명 참조에는 환경별 분기가 없다**. `TARGET_ENV`가 무엇이든 같은 한 줄이
해당 환경의 자격 증명을 집는다. 브랜치→환경 매핑은 기존대로 `resolveTargetEnv`에
남으므로, 완전히 새로운 환경을 추가할 때는 그 함수를 고쳐야 한다. 이 규칙이 없애는
것은 "환경마다 credentialsId를 하드코딩한 if 분기"이지 브랜치 매핑이 아니다.

## Non-goals

- 시크릿 저장소(Vault, AWS Secrets Manager) 도입
- 배포 대상 브랜치 규칙(`resolveTargetEnv`) 변경
- Dockerfile 런타임 구조 변경
- `.dockerignore` 도입 (아래 "기각한 피드백" 참조)
- Jenkins 인스턴스에 자격 증명을 실제로 등록하는 작업 (저장소 밖 관리 작업)

## Context / Constraints

기존 `Jenkinsfile` Deploy 스테이지는 `docker run`에 `SPRING_PROFILES_ACTIVE`만
넘긴다. 애플리케이션이 요구하는 나머지 값은 어디서도 주입되지 않는다.

기본값이 없어 미해결 시 컨텍스트 로딩이 실패하는 키
(`src/main/resources/application.yml`):

- `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`
- `ARTEL_JWT_SECRET`

기본값은 있으나 그 값이 개발용이라 배포 환경에서 반드시 덮어써야 하는 키
(`src/main/resources/application-db.yml`): `DB_HOST`, `DB_PORT`, `DB_NAME`,
`DB_USERNAME`, `DB_PASSWORD`. 기본값이 `localhost`/`postgres`이므로 주입하지 않으면
컨테이너가 자기 자신에게 붙으려 한다.

로컬에서는 `me.paulschwarz:spring-dotenv`가 작업 디렉터리의 `.env`를 읽어 해결하지만
`.env`는 gitignore 대상이라 이미지에도 컨테이너에도 존재하지 않는다.

### 제약

- `docker --env-file`은 docker **CLI**가 읽는다. 데몬이 아니다. 따라서 파일은
  `docker` 명령을 실행하는 Jenkins 에이전트에 있으면 된다. `withCredentials`가
  만드는 임시 파일 경로가 그 조건을 만족한다.
- `--env-file` 파싱 규칙:
  - `KEY=VALUE` 한 줄에 하나.
  - **줄 전체가 `#`으로 시작하면 주석으로 무시된다. 빈 줄도 무시된다.** 따라서
    `.env.example`의 섹션 주석은 그대로 둬도 된다.
  - 따옴표는 값의 일부가 된다. `K="v"` → 값이 `"v"`. **조용히** 오염된다.
  - 인라인 주석은 값의 일부가 된다. `K=v # note` → 값이 `v # note`. 이것도 조용히
    오염된다.
  - 키에 공백이 있으면 docker CLI가 즉시 실패한다:
    `invalid env file (...): variable 'export FOO' contains whitespaces`.
    `export K=v`가 여기 걸린다. 컨테이너는 아예 뜨지 않는다.
  - `=` 없이 `KEY`만 쓰면 값이 아니라 **Jenkins 에이전트 프로세스의 동명 환경변수**를
    통과시킨다. 의도치 않은 값이 새는 경로이므로 쓰지 않는다.
  - 줄바꿈을 포함하는 값은 표현할 수 없다.
- `file()` 바인딩은 파일 **내용을 마스킹하지 않는다.** 파이프라인이 파일을
  `cat`/`echo` 하면 시크릿이 그대로 콘솔에 찍힌다. 출력하지 않는 것이 유일한 방어다.
- 경로는 로그에 찍힌다. Jenkins `sh`는 `sh -xe`로 실행되어 **확장된** 명령을 xtrace로
  출력하므로, 따옴표를 어떻게 쓰든 `--env-file /…/secretFiles/<uuid>/…`가 로그에
  나타난다. 경로 비노출은 전적으로 credentials-binding의 콘솔 마스킹에 의존한다.
- 주입 방식은 환경변수다. `spring-dotenv`는 관여하지 않는다. Spring이 환경변수로
  직접 해석한다.

### 선행 조건 (저장소 밖)

병합 전에 Jenkins 쪽에서 끝나 있어야 한다:

1. Credentials Binding 플러그인 설치 확인. 없으면 `withCredentials`를 못 찾아
   파이프라인이 깨진다. Jenkins 기본 설치에 포함되지만 확인이 필요하다.
2. 위 표의 두 자격 증명을 Secret file로 등록.

## Approach (Checklist)

- [x] **Step 0: Recon**
  - `Jenkinsfile` 구조 확인 — `environment` 블록의 `APP_NAME`, Docker Build
    스테이지가 설정하는 `env.TARGET_ENV`.
  - `application.yml`, `application-db.yml`에서 각 키의 기본값 유무 확인.
  - `Dockerfile`이 `.env`를 복사하지 않는지 확인.

- [x] **Step 1: Jenkinsfile Deploy 스테이지 수정**
  - `withCredentials([file(credentialsId: "${env.APP_NAME}-env-${env.TARGET_ENV}", variable: 'ENV_FILE')])`로
    `docker run`을 감싼다.
  - `docker run`에 `--env-file "$ENV_FILE"` 추가. **큰따옴표 필수.** Jenkins
    시크릿 파일은 `<workspace>@tmp/secretFiles/…`에 놓이고 작업 공간 경로는 잡 이름의
    공백을 물려받는다. 따옴표가 없으면 docker가 잘린 경로를 읽고 죽는다.
  - `sh`는 작은따옴표 블록을 유지한다. 기존 `$CONTAINER_NAME`/`$IMAGE_TAG`와 같은
    셸 확장 규칙을 그대로 쓰기 위해서다. 로그 노출과는 무관하다 (위 제약 참조).
  - `-e SPRING_PROFILES_ACTIVE=$TARGET_ENV`는 유지한다. 프로파일은 브랜치에서
    파생되는 값이지 시크릿이 아니므로 .env가 아니라 파이프라인이 결정해야 한다.
    docker CLI는 `-e` 값을 항상 env-file 값 뒤에 붙이므로 **플래그 순서와 무관하게**
    `-e`가 이긴다. .env에 같은 키가 들어 있어도 브랜치 기준 프로파일이 유지된다.

- [x] **Step 2: 배포 문서 작성**
  - `docs/deployment.md` 신규 작성. 범위는 이 주입 메커니즘으로 한정한다. 일반
    배포 런북으로 키우지 않는다.
  - 담을 것:
    - 자격 증명 ID 규칙과 두 개의 실제 ID
    - Secret file 등록 체크리스트 (아래)
    - `--env-file` 파싱 규칙과 금지 형식
    - 새 환경 추가 시 절차 (자격 증명 등록 + `resolveTargetEnv` 매핑 추가)
  - 등록 체크리스트:
    1. `.env.example`을 복사한다.
    2. 각 키의 플레이스홀더를 실제 값으로 바꾼다. 주석 줄은 그대로 둬도 된다.
    3. 따옴표, `export`, 인라인 주석이 없는지 확인한다.
    4. `docker run --rm --env-file <file> alpine env`로 파싱 결과를 눈으로 확인한다.
    5. Jenkins > Credentials > Secret file로 등록하고 ID를 규칙대로 넣는다.

- [x] **Step 3: 검증**
  - Jenkinsfile Groovy 문법 파싱.
  - `--env-file` 주입 동작을 로컬 docker로 재현.

## Validation

- **Commands to run:**

  ```bash
  # 1. Jenkinsfile Groovy 문법 파싱 (문법만 본다. 선언형 파이프라인 구조 검증 아님)
  docker run --rm -v "$PWD/Jenkinsfile:/Jenkinsfile:ro" groovy:4-jdk21 \
    groovy -e 'new GroovyShell().parse(new File("/Jenkinsfile"))'
  ```

  ```bash
  # 2. --env-file 주입 재현. 주석 줄이 무시되고 인라인 주석이 값에 섞이는 것까지 확인
  printf '# comment line\nDB_HOST=example.internal\n\nDB_PORT=5432\n' > /tmp/artel-envfile-check
  docker run --rm --env-file /tmp/artel-envfile-check alpine env | grep '^DB_'
  rm -f /tmp/artel-envfile-check
  ```

- **Expected output:**
  - 1: 파싱 성공, 출력 없음. 문법 오류 시 `groovy.lang.GroovyRuntimeException`.
  - 2: `DB_HOST=example.internal`, `DB_PORT=5432` 두 줄. 주석 줄은 나타나지 않는다.

  1번은 어휘·문법 오류만 잡는다. `withCredentials` 사용이 실제 Jenkins에서 유효한지는
  확인하지 못한다. 파이프라인 자체 검증은 stage 배포 실행이 필요하므로 아래 항목은
  병합 후 stage 빌드에서 확인한다:

  - 컨테이너가 기동하고 Flyway 마이그레이션이 통과한다.
  - 콘솔 로그에 시크릿 값이 없다.
  - 자격 증명을 등록하지 않은 상태에서 빌드하면 `withCredentials`가 던져 실패한다.
    조용히 통과하지 않는다.

  "이미지 레이어에 .env가 포함되지 않는다"는 수용 기준은 변경 없이 이미 만족한다.
  `Dockerfile`은 `COPY ${JAR_FILE} app.jar` 한 줄만 복사하며, 애초에 `.env`는
  gitignore 대상이라 `checkout scm` 이후 작업 공간에 존재하지도 않는다.

## Risks & Rollback

- **Risks:**
  - 자격 증명 미등록 상태에서 develop에 병합되면 다음 stage 배포가 실패한다.
    기존 동작도 컨테이너 기동 실패였으므로 손실은 없지만, 병합 전에 자격 증명을
    먼저 등록해야 배포가 살아난다. 위 "선행 조건" 참조.
  - 따옴표나 인라인 주석이 섞이면 값이 **조용히** 오염된다. docker는 경고하지 않고
    컨테이너는 정상 기동한 뒤 잘못된 값으로 동작한다. (키 공백/`export`는 반대로
    즉시 실패하므로 이 위험에 해당하지 않는다.) Step 2의 등록 체크리스트
    4번(`alpine env`로 파싱 결과 확인)이 이 위험을 문서 문장이 아니라 실행 가능한
    절차로 막는다.
  - 줄바꿈을 포함하는 값이 필요해지면 이 방식이 막힌다. 현재 키 목록에는 없다.

- **Rollback steps:**
  - `git revert`로 Jenkinsfile 변경을 되돌린다. 파이프라인이 이전 동작으로 돌아가며
    Jenkins 쪽 자격 증명은 그대로 두어도 무해하다.
  - 저장소에 시크릿이 들어가지 않으므로 롤백에 시크릿 회수 절차가 없다.

## 기각한 피드백

- **`.dockerignore` 추가** — 기각. 초안에 있었으나 뺐다. `Dockerfile`은 jar 하나만
  복사하고, `.env`는 gitignore 대상이라 Jenkins 작업 공간에 존재조차 하지 않는다.
  막을 유출 경로가 현재 없다. "나중에 `COPY . .`이 들어올 수도 있다"는 가정만으로
  파일을 추가하는 것은 ARTEL-74 범위 밖이다. 빌드 컨텍스트 크기 문제는 별도 이슈로
  다룬다.
- **`application-db.yml`에 DB 키 기본값이 없다는 지적** — 기각. 기본값은 있다.
  다만 개발용(`localhost`)이라 배포에서 덮어써야 한다. 초안 문구가 이미 그 뜻이었고,
  본문에서 더 명확히 서술했다.

## Open Questions

- 자격 증명 스코프를 글로벌로 둘지 폴더 스코프로 둘지는 Jenkins 운영 정책에 따른다.
  Jenkinsfile은 ID만 참조하므로 어느 쪽이든 동작한다.
