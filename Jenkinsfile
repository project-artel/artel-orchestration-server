pipeline {
    agent any

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

        stage('Build') {
            steps {
                sh 'chmod +x mvnw'
                sh './mvnw clean package -DskipTests'
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
