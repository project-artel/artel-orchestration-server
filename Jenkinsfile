pipeline {
    agent any

    // 빌드 기록에 상한이 없었다. 이 잡의 로그는 maven 출력과 Flyway 검증 전량을 담는데,
    // 아무것도 그것을 지우지 않는다. 상한을 두지 않으면 디스크가 유일한 상한이고, 그 상한은
    // 상관없는 다른 잡을 실패시키는 방식으로 말한다.
    //
    // 30 은 지금 되돌아볼 만한 폭이다. 그보다 오래된 빌드는 로그를 열어 본 적이 없다.
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        skipDefaultCheckout(true)
        disableConcurrentBuilds(abortPrevious: true)
        timestamps()
        timeout(time: 30, unit: 'MINUTES')
    }

    parameters {
        booleanParam(name: 'FORCE_REVALIDATE', defaultValue: false,
            description: 'Run compilation and context test even when this PR input already passed')
    }

    environment {
        MAVEN_OPTS = '-Xmx2g'
        CI_TOOLCHAIN_VERSION = 'jdk21-kotlin-inprocess-context-v1'
        APP_NAME = 'artel-orchestration-server'
        IMAGE_NAME = 'artel-orchestration-server'
        APP_PORT = '8080'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    env.GIT_COMMIT = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                    if (!(env.GIT_COMMIT ==~ /[a-f0-9]{40}/)) {
                        error 'Checkout commit SHA is invalid'
                    }
                }
            }
        }

        // Flyway 버전 충돌은 병합까지 조용하다가 배포된 컨테이너의 기동 실패로만 드러난다.
        // Build는 -DskipTests라 마이그레이션을 한 번도 실행하지 않으므로, 이 스테이지가
        // 파이프라인에서 충돌을 볼 수 있는 유일한 지점이다. Deploy Pipeline보다 앞에 두어
        // 이미 깨진 develop이 배포되지 않게 한다.
        //
        // 종료 코드 1은 실제 충돌(빌드 실패), 2는 아직 병합되지 않은 다른 브랜치가 같은
        // 번호를 선점한 경우다. 후자는 상대가 영영 병합되지 않을 수도 있어 차단 근거로
        // 약하므로 unstable로만 남긴다. 상대가 먼저 병합되면 그때 1로 승격된다.
        stage('Flyway Migration Check') {
            steps {
                sh 'chmod +x scripts/check-flyway-migrations.sh'
                script {
                    def status = sh(
                        returnStatus: true,
                        script: "./scripts/check-flyway-migrations.sh '${env.CHANGE_TARGET ?: 'develop'}'",
                    )

                    if (status == 2) {
                        unstable 'Flyway 마이그레이션 버전을 다른 브랜치가 선점했다'
                    } else if (status != 0) {
                        error 'Flyway 마이그레이션 버전 검사 실패'
                    }
                }
            }
        }

        // 앞 스테이지는 git 트리만 본다. 파일 이름과 번호가 맞고 내용이 깨진 마이그레이션은
        // 그대로 통과하고, Build가 -DskipTests라 SQL은 파이프라인 어디에서도 실행되지 않는다.
        // 그 결함은 배포된 컨테이너가 Flyway 단계에서 죽을 때 처음 드러난다.
        //
        // 빈 DB에 전부 적용해 보는 것으로는 부족하다. 그것은 신규 설치 경로이고, 실제 배포는
        // 이미 마이그레이션된 DB 위에 새 것만 얹는다. 스크립트가 base의 마이그레이션을 먼저
        // 적용한 뒤 이 브랜치의 것을 그 위에 얹는 이유다.
        //
        // 종료 코드 2는 base 자체가 깨진 경우다. 이 브랜치의 결함이 아니므로 unstable로만
        // 남긴다. 그렇지 않으면 develop이 깨진 동안 모든 PR이 자기 잘못으로 빨갛게 선다.
        stage('Flyway Upgrade Verify') {
            steps {
                sh 'chmod +x scripts/verify-flyway-upgrade.sh'
                script {
                    def status = sh(
                        returnStatus: true,
                        script: "./scripts/verify-flyway-upgrade.sh '${env.CHANGE_TARGET ?: 'develop'}'",
                    )

                    if (status == 2) {
                        unstable 'base 브랜치의 마이그레이션이 이미 깨져 있다'
                    } else if (status != 0) {
                        error 'Flyway 마이그레이션 업그레이드 검증 실패'
                    }
                }
            }
        }

        stage('Reuse successful PR verification') {
            steps {
                script {
                    def policy = load 'ci/policy.groovy'
                    // Flyway checks above always rerun: peer migration state can change independently.
                    def base = env.CHANGE_TARGET ?: 'develop'
                    withEnv(["CI_BASE_BRANCH=${base}"]) {
                        env.CI_INPUT_KEY = sh(returnStdout: true, script: '''
                            set -eu
                            git check-ref-format "refs/heads/$CI_BASE_BRANCH"
                            git fetch --no-tags origin "+refs/heads/$CI_BASE_BRANCH:refs/remotes/origin/$CI_BASE_BRANCH"
                            { git rev-parse HEAD; git rev-parse "refs/remotes/origin/$CI_BASE_BRANCH";
                              printf '%s\n' "$CI_TOOLCHAIN_VERSION" "$NODE_NAME";
                              java -version 2>&1; } | sha256sum | cut -d ' ' -f 1
                        ''').trim()
                    }
                    def previous = currentBuild.previousSuccessfulBuild
                    env.REUSE_CI = policy.reuse(env.CHANGE_ID != null, params.FORCE_REVALIDATE,
                        currentBuild.currentResult, env.CI_INPUT_KEY,
                        previous?.buildVariables?.get('CI_INPUT_KEY')).toString()
                    if (env.REUSE_CI == 'true') {
                        currentBuild.description = "Reused verification from #${previous.number}"
                        echo "Compilation/context result reused from ${previous.absoluteUrl}"
                    }
                }
            }
        }

        // **Kotlin 컴파일을 별도 데몬에 맡기지 않는다.** 기본값은 컴파일러를 자기 JVM 으로
        // 띄우는데, `agent any` 라 세 레포의 잡이 같은 에이전트를 나눠 쓰고 그 JVM 이 먼저
        // 죽는다. 죽는 모양이 컴파일 오류가 아니라 RMI 연결 끊김이라 원인이 안 보인다.
        //
        //     [ERROR] Internal Kotlin compilation error
        //     java.rmi.UnmarshalException: ... nested exception is: java.io.EOFException
        //
        // 진단이 한 줄도 없이 저것만 나오고, main 컴파일은 통과한 뒤 `test-compile` 에서
        // 터진다 — 같은 데몬을 두 번째로 쓸 때다. 2026-09-03 에 열려 있던 PR 이 전부 이걸로
        // 막혔다(ARTEL-795).
        //
        // `in-process` 는 JVM 을 하나만 쓴다. 붐비는 에이전트에서 총 사용량이 줄고, 컴파일러가
        // 죽으면 그것이 Maven 의 실패로 그대로 보인다. 대신 그 JVM 이 힙을 감당해야 하므로
        // `MAVEN_OPTS` 로 명시한다 — 안 주면 컨테이너가 주는 기본값에 맡기게 되고, 그 값이
        // 바뀌는 날 같은 실패가 다시 난다.
        stage('Build') {
            when { expression { env.REUSE_CI != 'true' } }
            environment {
                MAVEN_OPTS = '-Xmx2g'
            }
            steps {
                sh 'chmod +x mvnw'
                // 같은 에이전트에서 도는 빌드들이 Kotlin compile daemon 하나를 나눠 쓰다가, 먼저 끝난
                // 빌드 쪽에서 daemon 이 내려가면 남은 빌드가 `java.rmi.UnmarshalException` 으로 죽는다
                // (ARTEL-795). kotlin-maven-plugin 2.3 은 `kotlin.compiler.execution.strategy` 를
                // 읽지 않고 daemon 을 그대로 띄우므로, daemon 을 끄는 `kotlin.compiler.daemon` 을 쓴다.
                sh './mvnw -B clean package -DskipTests -Dkotlin.compiler.daemon=false'
            }
        }

        stage('Context Startup Check') {
            when { expression { env.REUSE_CI != 'true' } }
            steps {
                // package already compiled tests. Calling the goal avoids a second compile.
                sh './mvnw -B surefire:test -Dtest=ApplicationContextStartupTest'
            }
            post { always { junit allowEmptyResults: false, testResults: 'target/surefire-reports/TEST-*.xml' } }
        }

        stage('Publish deployment image') {
            when {
                expression {
                    currentBuild.currentResult == 'SUCCESS' &&
                        env.BRANCH_NAME in ['develop', 'stage', 'main', 'operation']
                }
            }
            steps {
                script {
                    def target = env.BRANCH_NAME in ['develop', 'stage'] ? 'stage' : 'operation'
                    env.DEPLOY_IMAGE_TAG = "artel-orchestration-server:ci-${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
                    sh '''
                        docker build --label "artel.ci.job=$JOB_NAME" \
                          --label "artel.ci.build=$BUILD_NUMBER" \
                          --label "artel.ci.commit=$GIT_COMMIT" \
                          -t "$DEPLOY_IMAGE_TAG" .
                        docker image inspect --format '{{.Id}}' "$DEPLOY_IMAGE_TAG" > image-id.txt
                    '''
                    def manifest = [version: 1, job: env.JOB_NAME, build: env.BUILD_NUMBER.toInteger(),
                        commit: env.GIT_COMMIT, image: readFile('image-id.txt').trim(),
                        environment: target, node: env.NODE_NAME]
                    writeFile file: 'deployment.json', text: groovy.json.JsonOutput.toJson(manifest)
                    archiveArtifacts artifacts: 'deployment.json', fingerprint: true
                }
            }
        }

    }

    // verify-flyway-upgrade.sh는 trap으로 스스로 정리한다. 이것은 잡이 강제 종료돼 trap이
    // 돌지 못한 경우의 안전망이다. 이 빌드가 붙인 라벨 값만 지운다 —
    // 다른 브랜치 job은 같은 에이전트에서 동시에 돌 수 있으므로,
    // 라벨 키만으로 지우면 남의 검증용 DB를 죽인다.
    post {
        success {
            script {
                if (env.DEPLOY_IMAGE_TAG) {
                    build job: '/artel-orchestration-deploy', wait: false, propagate: false,
                        parameters: [string(name: 'SOURCE_JOB', value: env.JOB_NAME),
                                     string(name: 'SOURCE_BUILD', value: env.BUILD_NUMBER)]
                }
            }
        }
        always {
            script {
                def runLabel = env.BUILD_TAG ? env.BUILD_TAG.replaceAll(/[^A-Za-z0-9._-]/, '_') : 'local'
                sh(
                    returnStatus: true,
                    script: "docker ps -aq --filter 'label=artel-flyway-upgrade=${runLabel}' | xargs -r docker rm -fv",
                )
            }

            // CI owns only its workspace and its labeled Flyway containers.
            // Published images must remain available to the separate CD job.
            deleteDir()
        }
    }
}
