# 2026-08-07 — CI에서 실제 Postgres로 마이그레이션 업그레이드 경로를 검증한다

- Date: 2026-08-07
- Jira: ARTEL-263
- Status: Reviewed

## Goal

CI가 base 브랜치의 마이그레이션을 실제 PostgreSQL에 적용한 뒤, 이 브랜치의 마이그레이션을
그 위에 얹고 `validate`까지 통과하는지 확인한다. SQL 문법 오류와 스키마 참조 오류가
stage 배포까지 살아남지 않게 한다.

## Non-goals

- ARTEL-262의 정적 검사(`scripts/check-flyway-migrations.sh`) 대체. 둘은 다른 것을 잡는다.
- 롤백(`undo`) 마이그레이션. Flyway Community에 없다.
- 운영 데이터 기반 검증.
- ARTEL-264(`Build & Test`, `-DskipTests` 제거)의 복구. develop에 반영되지 않은 상태지만
  이 이슈의 범위가 아니다.

## Context / Constraints

- `Jenkinsfile`의 `Build`는 여전히 `./mvnw clean package -DskipTests`다. 마이그레이션 SQL이
  파이프라인에서 한 번도 실행되지 않는다.
- `scripts/check-flyway-migrations.sh`(ARTEL-262)는 git 트리만 비교한다. 파일 이름이 맞고
  번호가 맞으면 내용이 깨져 있어도 통과한다.
- `pgvector/pgvector:pg16`을 써야 한다. `V18__create_knowledge_embedding.sql`이
  `CREATE EXTENSION IF NOT EXISTS vector`를 요구하므로 순정 `postgres` 이미지에서는
  마이그레이션 체인 전체가 실패한다. `PostgresTestContainer`가 같은 이유로 같은 이미지를 쓴다.
- 앱이 쓰는 Flyway는 Spring Boot 3.3.1 BOM이 관리하는 **10.10.0**이다. CLI 이미지를 같은
  버전으로 고정해 CI가 검증하는 엔진과 런타임 엔진을 일치시킨다.
- base 시점과 HEAD 시점 마이그레이션을 **두 단계로** 적용해야 한다. 한 번에 다 적용하면 빈 DB
  시나리오가 되어 업그레이드 경로를 검증하지 못한다.
- Jenkins 에이전트의 Docker에 의존한다. `Deploy Pipeline`의 `docker build`가 이미 쓰는 그
  소켓이므로 새 의존은 아니다.
- develop의 `Jenkinsfile`에는 `disableConcurrentBuilds()`가 없다. 같은 에이전트에서 다른
  브랜치 잡이 동시에 돌 수 있으므로, 정리는 **이 실행이 만든 자원만** 지워야 한다.

## Approach (Checklist)

- [ ] **Step 0: Recon** — 완료.
  - `Jenkinsfile` — `Flyway Migration Check` 스테이지 뒤에 붙일 자리를 확인했다.
  - `scripts/check-flyway-migrations.sh` — base ref 해석 방식(`CHANGE_TARGET` → fetch →
    로컬 폴백)과 배포 브랜치 판정을 그대로 따른다.
  - `src/main/resources/application-db.yml` — Flyway locations는 `classpath:db/migration`,
    `baseline-on-migrate: true`, out-of-order 미설정(기본 `false`).
  - `src/test/kotlin/.../support/PostgresTestContainer.kt` — 이미지 선택 근거.
  - `.claude/skills/`는 `.agents/skills/`로 가는 심링크다. 한 번만 고치면 된다.

- [ ] **Step 1: Implementation**
  - `scripts/verify-flyway-upgrade.sh` 신규.
    1. **base ref 해석.** 인자 → `CHANGE_TARGET` → `develop` 순.
       `check-flyway-migrations.sh`와 같은 순서를 쓰지만 공용 라이브러리로 빼지는 않는다.
       두 스크립트가 공유하는 것은 열 줄 남짓이고, 병합된 스크립트를 건드리는 비용이 더 크다.
       스크립트 주석에 이 판단을 남긴다.
    2. **컨테이너.** `pgvector/pgvector:pg16`을 이름 유일하게(`$$`+타임스탬프) 띄운다.
       포트는 노출하지 않는다. Flyway CLI 컨테이너는 `--network container:<pg>`로 같은
       네트워크 네임스페이스에 붙여 `localhost:5432`로 접속한다. 전용 네트워크를 만들지
       않으므로 정리할 자원이 컨테이너 둘과 임시 디렉터리뿐이다.
       DB/유저/비밀번호는 `PostgresTestContainer`와 같은 `artel`을 쓴다.
       SQL은 바인드 마운트가 아니라 `docker create` → `docker cp` → `docker start -a`로
       넣는다. 에이전트가 컨테이너 안에서 소켓만 공유받는 구성이면 워크스페이스 경로가
       데몬 쪽에 존재하지 않아 마운트가 빈 디렉터리로 붙는다. `docker cp`는 데몬이 어디에
       있든 성립하고, `docker build`가 컨텍스트를 스트리밍하는 것과 같은 이유다.
    3. `pg_isready`로 준비를 기다린다(상한 60초). Flyway에는 `-connectRetries=10`도 준다.
    4. **Phase 1 — base.** base ref의 마이그레이션 디렉터리를 `git archive`로 임시 디렉터리에
       풀고 `flyway migrate`. 여기서 실패하면 base가 이미 깨진 것이므로 **exit 2**로
       구분한다(Jenkins는 `unstable`). 이 브랜치의 결함이 아닌 것으로 빌드를 세우지 않는다.
       base에 마이그레이션 디렉터리가 없으면 Phase 1을 건너뛴다.
    5. **Phase 2 — head.** 작업 트리의 마이그레이션 디렉터리를 그대로 마운트해
       `flyway migrate` 후 `flyway validate`. 새 것만 적용되고, 이미 적용된 것의 checksum이
       검사된다. 실패는 **exit 1**.
    6. **폴백.** base ref를 fetch도 못 하고 로컬에도 없으면 Phase 1을 건너뛰고 경고를 남긴 뒤
       Phase 2만 돈다(빈 DB에 전체 적용). 문법 검사는 그래도 성립한다.
    7. **배포 브랜치.** `CHANGE_TARGET`이 없고 브랜치가 `main`/`operation`/`develop`/`stage`
       이면 자기 자신을 base로 삼는 것이 무의미하므로 Phase 1을 건너뛴다. 병합된 트리를
       실제 DB에서 한 번 실행해 보는 유일한 지점이므로 스테이지 자체는 남긴다.
    8. `-baselineOnMigrate=true`, `-outOfOrder=false`로 런타임 설정과 맞춘다.
    9. `trap ... EXIT INT TERM`으로 컨테이너와 임시 디렉터리를 반드시 정리한다.
       도커가 없거나 소켓 권한이 없으면 시작 시점에 분명한 메시지로 실패한다.
  - `Jenkinsfile` — `Flyway Upgrade Verify` 스테이지를 `Flyway Migration Check` 뒤,
    `Build` 앞에 추가한다. exit 2는 `unstable`, 그 외 0이 아닌 코드는 `error`.
    `post { always { ... } }`에 **이 빌드가 붙인 라벨 값**(`artel-flyway-upgrade=<BUILD_TAG>`)
    기준 정리를 둔다. 잡이 강제 종료돼 `trap`이 돌지 못한 경우의 안전망이며, 동시 실행 중인
    다른 빌드의 컨테이너는 건드리지 않는다.
  - `docs/flyway-migrations.md` — 두 검사의 역할 분담, 실행 방법, 종료 코드를 더한다.
  - `.agents/skills/flyway-migration/SKILL.md` — Step 4에 로컬 실행 명령을 더한다.

- [ ] **Step 2: Tests**
  - 자동화 테스트는 두지 않는다. 검증 대상이 셸 스크립트와 파이프라인 정의다.
  - 로컬 수동 검증으로 대신한다(아래 Validation).

- [ ] **Step 3: Rollout / Rollback**
  - 플래그 없이 파이프라인에 바로 붙는다. `git revert` 한 번으로 되돌아간다.

## Validation

- **Commands to run:**
  - `./scripts/verify-flyway-upgrade.sh develop` — 정상 브랜치에서 초록인지.
  - 문법이 깨진 마이그레이션을 임시로 두고 재실행 — exit 1로 실패하는지.
  - 이미 병합된 마이그레이션을 고친 뒤 재실행 — `validate`가 checksum 불일치를 잡는지.
  - base를 일부러 깨서 재실행 — exit 2로 구분되는지.
  - `docker ps -a --filter label=artel-flyway-upgrade` — 실행 후 남는 컨테이너가 없는지.
    중단(`Ctrl-C`) 후에도 확인한다.
  - `time ./scripts/verify-flyway-upgrade.sh develop` — 파이프라인에 붙는 시간 측정.
- **Expected output:**
  - 정상: `OK: base migrations applied, branch migrations applied on top, validate passed.`, exit 0.
  - 문법 오류: Flyway의 원문 오류와 함께 exit 1.
  - base 결함: 원문 오류와 함께 exit 2.
  - 정리 확인: 컨테이너 목록 비어 있음.

## Risks & Rollback

- **Risks:**
  - 파이프라인 시간 증가(이미지 pull 포함 최초 실행이 가장 느리다). 측정해 판단한다.
  - Jenkins 에이전트에 Docker가 없거나 소켓 권한이 없으면 스테이지가 실패한다. `Deploy`가
    이미 같은 소켓을 쓰므로 새 가정은 아니고, 없으면 조용히 넘기기보다 세우는 편이 맞다.
  - Docker Hub rate limit. 이미지 두 개(pgvector, flyway)를 당긴다. 에이전트에 캐시되면
    이후에는 당기지 않는다.
  - develop이 깨져 있으면 모든 PR이 Phase 1에서 걸린다. exit 2/`unstable`로 구분해 두어
    빌드가 서지는 않는다.
- **Rollback steps:** `git revert` — 스크립트와 스테이지가 함께 빠진다. 다른 코드에 의존이 없다.

## Rejected feedback

- **`FLYWAY_UPGRADE_SKIP=1` 탈출구.** 뺐다. `Deploy Pipeline`이 이미 같은 에이전트의 Docker를
  쓰므로 없는 상황을 가정한 스위치이고, 있으면 검증이 조용히 꺼진 채 초록으로 남는 길이 생긴다.
- **base ref 해석을 공용 셸 라이브러리로 추출.** 뺐다. 스크립트 두 개가 공유하는 코드가 열 줄
  남짓이고, 이미 병합된 검사 스크립트를 함께 수정해야 한다. 세 번째 사용처가 생기면 그때 뺀다.
- **`flyway migrate validate` 한 번의 호출로 합치기.** 뺐다. 컨테이너 기동 1초를 아끼자고
  CLI의 다중 명령 문법에 기대게 된다. 두 호출이 로그에서도 어느 단계가 실패했는지 분명하다.

## Open Questions

- ARTEL-264(PR #81)의 `Build & Test`가 develop에 없다. #81이 develop이 아니라 ARTEL-262
  브랜치를 base로 병합됐고, #80이 그보다 먼저 develop에 들어가 남겨졌다. 별도 이슈로 복구해야
  한다. 이 이슈의 범위 밖.
