# CI와 CD 운영

ARTEL-1165는 기존 multibranch job을 CI 전용으로 바꾸고 `artel-orchestration-deploy`라는 별도 trusted CD job을 준비한다. 현재 동일 Docker host(`built-in`)의 image ID를 사용한다. registry나 다른 agent로 이동하려면 image pull/digest 검증을 먼저 추가해야 한다.

## CI

- `skipDefaultCheckout`으로 checkout은 한 번만 한다. `abortPrevious`는 같은 branch/PR job의 이전 CI만 취소하며 다른 repository job은 건드리지 않는다.
- Flyway 검사는 항상 실행한다. peer branch의 migration 상태가 head SHA와 무관하게 달라질 수 있기 때문이다.
- PR은 실제 checkout SHA, 현재 target SHA, 명시적인 toolchain version, node, Java version이 직전 성공의 `CI_INPUT_KEY`와 일치하면 compile/context만 재사용한다. 설명에 원본 build 링크를 남긴다. `FORCE_REVALIDATE`로 다시 실행할 수 있다.
- 이 정책은 모든 webhook을 admission 단계에서 제거하지 않는다. 같은 입력이 실행 중이면 최신 실행이 이전 것을 중단할 수 있다. completed-success 재사용과 동시 실행 억제의 범위만 보장한다.
- Maven package는 테스트 코드를 한 번 컴파일하고, `surefire:test -Dtest=ApplicationContextStartupTest`는 재컴파일 없이 필수 Spring context 검사를 수행한다. PostgreSQL/Redis는 기존 Testcontainers 인프라를 사용한다. 전체 suite 통과를 뜻하지 않으며 ARTEL-1163/PR #350의 suite 수리와 독립적이다.
- 기존 PR #347의 context test를 재사용한다. 겹치는 파일은 해당 PR이 merge될 때 대조해야 한다.
- feature/PR은 배포하지 않는다. `develop`, `stage`는 stage, `main`, `operation`은 operation용 이미지를 생성한다. SUCCESS만 manifest를 게시하고 CD를 요청한다. UNSTABLE은 요청하지 않는다.
- `deployment.json`은 source job/build, commit, immutable local image ID, environment, node를 담는다. image labels도 같은 값을 보존한다. CI 전역 prune는 CD의 대기 image를 지울 수 있어 제거했다. 이미지 정리는 별도 유지보수로 active/rollback/pending manifest 참조를 제외하고 수행해야 한다. 디스크 사용량은 rollout 전에 확인한다.

## CD 신뢰 경계

`ci/Jenkinsfile.cd`는 administrator가 설치하는 trusted inline pipeline이다. PR에서 load하거나 upstream script를 실행하지 않는다. installer는 policy와 deploy script를 검토 시점의 내용으로 embed하며 기본 disabled 상태로 만든다. 코드 변경 후에는 재설치가 필요하다. Jenkins Script Approval에 정확한 reviewed script hash의 실행 승인도 필요하며, 전역 sandbox 해제는 하지 않는다.

CD는 정확한 allowlist의 job, 직접 `UpstreamCause`, build 번호와 SUCCESS를 확인한다. CI가 post 단계에서 요청하므로 CI 종료를 node 없이 최대 5분 기다린다. manifest는 그 build의 artifact storage에서 읽으며, 전달받은 임의 image parameter는 사용하지 않는다. image label도 교차 확인한다. 같은 host의 Docker socket에 대한 권한 자체가 강력하므로 이것이 악성 PR을 격리하는 보안 sandbox라고 주장하지 않는다.

하나의 CD job이 네 branch의 배포를 직렬화한다. operation 승인 대기 중에는 stage CD도 최대 1시간 대기할 수 있다. stage와 operation을 동시에 배포하지 않는 보수적 선택이다. source보다 늦게 시작해 성공한 동일 환경의 CI가 있으면 이전 요청을 건너뛰며, agent 획득 직후 다시 확인한다. CI build 번호를 서로 다른 job 사이에서 비교하지 않는다. job이 성공했다고 서비스 health가 보장되지는 않으므로 CD 상태를 별도 확인해야 한다.

배포는 이전 container를 rename하고 stop하여 환경·설정을 보존한다. 새 container는 restart=no로 기동한다. 내부 `GET http://localhost:8080/api/auth/providers` HTTP 200을 최대 180초 내 연속 3회(3초 간격) 확인하며 RestartCount 증가를 실패로 본다. 성공 후 restart=unless-stopped로 바꾼다. run/health/update 실패 또는 TERM에는 새 container를 제거하고 이전 container를 복구한다. 복구 health 실패도 CD 실패로 남긴다. 첫 배포에는 복구 대상이 없다.

SIGKILL, host 장애나 Docker daemon 장애는 trap으로 복구를 보장할 수 없다. 남은 `*-rollback` container가 있으면 후속 배포는 덮어쓰지 않고 실패한다. DB migration은 자동 rollback하지 않는다. 이전 앱과 schema 호환성 확인 및 forward fix가 필요하다. 무중단 배포를 보장하지 않는다.

## 설치와 전환

1. Jenkins Declarative lint, policy contract, deployment boundary 테스트와 격리 CI를 실행한다.
2. `scripts/jenkins/install-cd.groovy`를 관리자 script console에서 실행한다. bindings는 `pipelineText`=`ci/Jenkinsfile.cd`, `policyText`=`ci/policy.groovy`, `deployText`=`scripts/deploy-verified-image.sh`의 UTF-8 내용이다. 기존 job은 기본적으로 덮어쓰지 않는다. 의도한 update만 `replaceExisting=true`를 지정한다.
3. draft PR은 사람이 검토하고 ready-for-review로 변경해야 한다. 변경 승인 뒤 CI branch를 병합한다.
4. 기존 Jenkinsfile로 시작한 legacy build와 기존 배포가 남아 있지 않은지 확인한다. 실행 중 배포를 무조건 중단하지 말고 완료·복구까지 기다린다. 기존 다른 PR의 Jenkinsfile도 rollout 대상이며 과거 script가 남은 build는 별도 drain한다.
5. 모든 CD 경로가 이 job을 사용하도록 전환한 뒤 job을 enable한다. 테스트 환경에서 정확한 upstream SUCCESS → artifact → CD → readiness 연결을 확인한다. operation은 trusted CD의 `Approve operation`에서 `admin`이 명시적으로 승인해야 하며, 1시간 이내 승인하지 않으면 실패한다. 승인 대기는 executor를 점유하지 않는다.
6. 중복 branch discovery는 기존 설정과 PR 목록을 확인한 뒤 별도 변경한다. 이 코드가 branch/PR discovery를 변경하지는 않는다.

CD를 먼저 disable하면 CI의 downstream 요청은 실패할 수 있지만 서비스는 변경되지 않는다. 롤백은 CD disable → 진행 중 배포 완료/복구 확인 → pipeline commit revert 순서다. 기존 trusted job definition과 정상 image는 보존한다.

이미지 보존은 ARTEL-1165 담당자가 rollout 시 디스크를 확인하고, 이후 주 1회 사용량과 pending manifest를 점검하는 수동 절차로 시작한다. active/rollback/pending image는 삭제하지 않으며 정상 배포 최근 3개는 남긴다. 무제한 보존을 장기 정책으로 사용하지 않는다. 자동 GC는 아직 구현하지 않았다.

## 검증

```sh
bash -n scripts/deploy-verified-image.sh
python3 -m unittest discover -s scripts/tests -v
```

`ci/policy-test.groovy`는 binding `policy`에 `ci/policy.groovy`를 평가한 객체를 주어 GroovyShell에서 실행한다. Jenkinsfile과 CD는 `pipeline-model-converter/validate`로 검사한다. Docker stub 테스트는 실제 shell의 성공/실패/rollback/TERM/입력 거절 경계를 검사하지만 실제 Docker 네트워크 및 application 기동을 대체하지 않는다. 격리 CI는 배포 금지 branch 이름으로 실행한다.

실제 적용 전후 PR 대기/실행 시간, 중복 취소/재사용 비율, CD health 실패/복구 결과를 각각 측정한다. 현재 문서에는 성능 개선률을 약속하지 않는다.

배포 경계의 Python 테스트는 개발/검증 환경에서 실행한다. 현재 Jenkins image에는 Python이 없으므로 Java CI에 Python 의존성을 추가하지 않는다.

## ARTEL-1165 검증 기록

2026-10-20에 deployment boundary 13개와 policy assertion 16개가 통과했고, CI/CD Declarative validator가 모두 성공했다. 실제 feature CI #2는 SUCCESS이며 context JUnit 1개가 실행돼 failure 0, skip 0이었다. 184257 ms는 해당 실행의 시간일 뿐, 진단 때 PR #344와 입력·환경이 같지 않아 성능 절감률로 비교하지 않는다.

격리 dispatch 검증은 절대 root job 호출에 성공했으며, 위조된 upstream은 `A matching direct upstream CI cause is required`로 거절했다. CD job은 disabled로 준비했고 실제 stage/operation 배포·rollback 실험은 아직 하지 않았다. Docker stub 테스트는 이 실서비스 검증을 대체하지 않는다.
