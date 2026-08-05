pipeline {
    agent any

    // 멈춘 빌드는 executor를 무기한 점유하고, 그러면 뒤따르는 PR 빌드가 큐에 갇혀
    // GitHub 체크가 'Waiting for status to be reported'에서 풀리지 않는다. 상한을
    // 두어 잘려 나가게 한다.
    //
    // disableConcurrentBuilds에 abortPrevious는 쓰지 않는다. Deploy는
    // stop → rm → run 세 단계라 원자적이지 않고, rm과 run 사이에서 중단되면
    // 컨테이너가 사라진 채 남는다. 같은 잡의 큐 항목은 Jenkins가 하나로 합치므로
    // 인자 없이도 적체는 막힌다.
    //
    // buildDiscarder는 브랜치마다 워크스페이스가 새로 파이는 멀티브랜치에서
    // 컨트롤러 디스크가 차는 것을 막는다. 디스크가 임계 밑으로 내려가면 Jenkins가
    // 노드를 offline으로 돌리고, executor는 있는데 아무 빌드도 시작되지 않는다.
    options {
        disableConcurrentBuilds()
        timeout(time: 45, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    environment {
        APP_NAME = 'artel-orchestration-server'
        IMAGE_NAME = 'artel-orchestration-server'
        APP_PORT = '8080'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        // Flyway 버전 충돌은 병합까지 조용하다가 배포된 컨테이너의 기동 실패로만 드러난다.
        // Build & Test가 빈 DB에 마이그레이션을 새로 적용하지만 그것으로는 부족하다.
        // 빈 DB에서는 어떤 순서도 어떤 checksum도 성공하므로, 순서 엉킴과 병합된
        // 마이그레이션 변조는 이 스테이지에서만 드러난다. Deploy Pipeline보다 앞에 두어
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

        // verify는 package와 테스트를 한 번에 돈다. -DskipTests를 쓰던 동안
        // 파이프라인에는 검증 지점이 없었고, PR 체크가 초록이어도 통과한 것은
        // 컴파일뿐이었다. 이 스테이지는 배포 브랜치 가드 바깥이므로 PR과 피처
        // 브랜치에서도 돈다 — PR을 검증하는 것이 목적이다.
        //
        // 통합 테스트는 Testcontainers로 PostgreSQL과 Redis를 띄우므로 에이전트의
        // docker 소켓이 필요하다. Deploy가 docker run에 쓰는 그 소켓이다.
        stage('Build & Test') {
            steps {
                sh 'chmod +x mvnw'
                sh './mvnw -B clean verify'
            }
        }

        // 배포 대상 브랜치에서만 실행한다. PR job의 BRANCH_NAME은 'PR-<번호>'라
        // 아래 branch 조건에 걸리지 않고, 피처 브랜치도 마찬가지다. 이 가드가
        // 없으면 두 경우 모두 resolveTargetEnv에서 error가 나 빌드가 실패한다.
        stage('Deploy Pipeline') {
            when {
                anyOf {
                    branch 'main'
                    branch 'operation'
                    branch 'develop'
                    branch 'stage'
                }
            }

            stages {
                stage('Docker Build') {
                    steps {
                        script {
                            env.TARGET_ENV = resolveTargetEnv(env.BRANCH_NAME)
                            env.CONTAINER_NAME = "${APP_NAME}-${env.TARGET_ENV}"
                            env.IMAGE_TAG = "${IMAGE_NAME}:${env.TARGET_ENV}-${env.BUILD_NUMBER}"
                        }

                        sh 'docker build -t $IMAGE_TAG .'
                    }
                }

                stage('Deploy') {
                    steps {
                        // 애플리케이션 환경변수는 Jenkins Credentials에 Secret file로 등록한
                        // .env에서 온다. 등록 절차는 docs/deployment.md 참조.
                        withCredentials([file(credentialsId: "${env.APP_NAME}-env-${env.TARGET_ENV}", variable: 'ENV_FILE')]) {
                            sh '''
                                docker stop $CONTAINER_NAME || true
                                docker rm $CONTAINER_NAME || true

                                docker run -d \
                                  --name $CONTAINER_NAME \
                                  --restart unless-stopped \
                                  --network app-net \
                                  --env-file "$ENV_FILE" \
                                  -e SPRING_PROFILES_ACTIVE=$TARGET_ENV \
                                  $IMAGE_TAG
                            '''
                        }
                    }
                }
            }
        }
    }
}

def resolveTargetEnv(String branchName) {
    if (branchName == 'main' || branchName == 'operation') {
        return 'operation'
    }

    if (branchName == 'develop' || branchName == 'stage') {
        return 'stage'
    }

    error "Unsupported branch for deployment: ${branchName}"
}
