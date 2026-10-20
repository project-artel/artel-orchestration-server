# 2026-10-20 — CI 중복 억제와 검증 이미지 CD 분리

- Date: 2026-10-20
- Jira: ARTEL-1165
- Status: Implemented and validated; human review and rollout pending

## Goal

PR/branch CI에서 중복 checkout과 중복 검증을 줄이고, Spring context 기동을 확인한 이미지로만 별도 CD를 실행한다. 실패한 배포가 성공으로 보이거나 재시작 loop를 남기지 않는다. 실제 검증 내용만 3쪽 PDF에 기록한다.

## Non-goals

registry/agent 신규 인프라, 전체 테스트 suite 수리, 증분 compiler cache, 무중단 배포 보장, 운영 자동 배포, 성능 개선률 추정.

## Context / Constraints

- 4 vCPU Docker host, built-in executor 2개. copyartifact/lock 플러그인 없음.
- 기존 #347의 context test와 #346의 배포 복구 구현을 참고한다. 이 작업은 기존 PR의 전체 merge를 요구하지 않는 독립 pipeline 변경이며 겹치는 파일은 PR에 명시한다.
- 운영 job은 draft 인간 리뷰 전 활성화하지 않는다. 기존 저장소 규칙상 자동 ready-for-review/merge 금지.
- 현재 저장소의 테스트 전체 실패 수리는 #350 담당. 명시적인 context test를 필수화하고 full-suite 통과를 주장하지 않는다.

## Approach (Checklist)
- [x] **Step 0: Recon** 기존 Jenkins/PR/배포 설정과 Docker host 확인.
- [x] **Step 1: Implementation**
  - Jenkinsfile을 CI 전용으로 변경. skipDefaultCheckout, abortPrevious, timestamps.
  - checkout한 실제 SHA, migration 검사 후 확인한 PR base SHA 및 환경 버전으로 CI_INPUT_KEY를 기록. 직전 성공 RunWrapper.buildVariables와 같으면 compile/context 단계만 생략. Flyway 검사는 현재 peer branch 상태가 달라질 수 있어 매번 실행. FORCE_REVALIDATE=false parameter로 수동 재검증 가능. develop은 이미지/CD가 필요하므로 성공 재사용은 PR만 적용.
  - PR base가 변경되면 재검증. 같은 job의 동시 실행은 최신 것으로 취소. 완료된 동일 revision만 재사용하며 완전한 webhook admission 서비스를 구현했다고 주장하지 않음.
  - 기존 Flyway 검사 유지. Maven clean package로 compile 후 surefire:test를 직접 호출해 context test 실행. 중복 compile 방지. JUnit 결과 보관.
  - 이미지/CD는 정확히 artel-orchestration-server/{develop,stage,main,operation}만 허용. develop/stage → stage, main/operation → operation 매핑 유지. PR/feature는 검증만 한다. 동일 Docker host에 immutable image ID 생성 및 태그 유지. image ID/commit/CI job/build 메타데이터를 deployment.json으로 archive. 별도 CD job에 upstream build 번호로 전달.
  - CD는 admin이 설치한 trusted inline pipeline(sandbox=false). 위 allowlist의 upstream 원본 job/직접 UpstreamCause 일치/SUCCESS와 image labels를 검증하고 입력 임의 주입을 거절. 정확한 build의 ArtifactManager에서 deployment.json만 읽으며 upstream 스크립트는 실행하지 않는다. CI 종료 대기는 node 밖에서 최대 5분.
  - CD는 하나의 serial job, abortPrevious 없음. 큐에서 기다린 오래된 upstream은 최신 성공 branch build와 비교해 생략. 같은 host label로 image 접근. credential은 CD 내부에서만 사용.
  - deploy script는 image ID 입력을 검증. 이전 container를 stop+rename하여 설정을 보존. 새 container start/health 실패 또는 TERM에 기존 container 복구. endpoint는 컨테이너 내부 localhost:8080/api/auth/providers, 180초 내 HTTP 200 연속 3회·3초 간격·RestartCount=0. 이전 container 복구도 동일 검증(복구 시작 restart count를 baseline으로 사용). 첫 배포 실패/복구 실패는 실패로 종료. DB schema rollback은 보장하지 않음.
  - job 설치용 Groovy는 대상 job만 생성/변경, 기본 disabled. 명시적 활성화 필요. CI migration 후 기존 in-flight legacy 배포가 없는지 확인하는 rollout 문서.
- [x] **Step 2: Tests** Python Docker stub으로 성공/실패/rollback/first deploy/invalid input/TERM 경계 검증. Groovy helper/격리 Jenkins 검증으로 reuse hit/miss/base 변경/force/UNSTABLE 차단/upstream 위조/stale skip 확인. Jenkins Declarative lint. 격리 validation job에서 context test 실행. pair review.
- [ ] **Step 3: Rollout / Rollback** draft PR과 disabled CD job 준비. 인간 리뷰 후 merge/기존 CI drain/CD enable 순서. 코드 revert와 job disable로 rollback.

## Validation
- **Commands to run:** bash -n, python3 -m unittest, Jenkins Declarative validator, validation CI (배포 금지).
- **Expected output:** 경계 테스트 통과, Declarative valid, context test 실제 실행 또는 원인까지 보고.

## Risks & Rollback
- **Risks:** 동일 Docker host에만 image 존재. 강제 SIGKILL/host 장애의 자동 복구 한계. 이전 schema와 호환되지 않는 migration은 컨테이너 복구만으로 해결되지 않음.
- **Rollback steps:** CD disable, pipeline commit revert. 운영 적용 전 기존 배포 흐름 drain.

## Open Questions
- 사용자 요청 범위의 구현·격리 검증은 완료하되, 저장소가 요구하는 인간 draft 리뷰/ready/merge 이후 실서비스 활성화한다. PDF에는 구현/격리 검증/실서비스 미적용을 구분한다.
- 동시 도착의 완전한 webhook admission은 도입하지 않음. abortPrevious와 성공 PR 재사용을 제공하며 이 한계를 명시한다.

## Validation results

- Fast/medium findings incorporated; heavy review PASS before implementation.
- Pair review PASS after correcting the absolute downstream job path and operation approval boundary.
- Deployment shell boundary tests: 13 passed, including failed run/health/update, TERM, rename/stop failure and rollback failure.
- Groovy policy: 16 assertions passed. CI/CD Declarative validators both passed.
- Actual ARTEL-1165 feature CI #2: SUCCESS; duration 184257 ms; JUnit 1 test, 0 failures, 0 skips. This is a different sample from the incident baseline and not a controlled speedup measurement.
- Isolated downstream dispatch #2: direct upstream mismatch rejected before deployment; upstream test SUCCESS. Verification jobs disabled after completion.
- Exact reviewed trusted CD script approved after explicit user permission. Production CD job remains disabled; no stage/operation deployment performed by this change.
- First CI found Python absent in the Jenkins image. Removed that unnecessary runtime dependency; Python boundary tests remain local verification.
