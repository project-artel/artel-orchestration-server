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

        stage('Build') {
            steps {
                sh 'chmod +x mvnw'
                sh './mvnw clean package -DskipTests'
            }
        }

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

def resolveTargetEnv(String branchName) {
    if (branchName == 'main' || branchName == 'operation') {
        return 'operation'
    }

    if (branchName == 'develop' || branchName == 'stage') {
        return 'stage'
    }

    error "Unsupported branch for deployment: ${branchName}"
}
