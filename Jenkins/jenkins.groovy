pipeline {
    agent any

    environment {
        DOCKER_IMAGE = "pranavkedar/todo"
        IMAGE_TAG = "${BUILD_NUMBER}"
        SONAR_SCANNER_HOME = tool 'SonarScanner'
    }

    stages {

        stage('Checkout Code') {
            steps {
                git branch: 'main',
                    credentialsId: 'github-cred',
                    url: 'https://github.com/pkedar2/django-todo-cicd.git'
            }
        }

        stage('Install Dependencies') {
            steps {
                sh '''
                python3 -m venv venv
                . venv/bin/activate
                pip install django
                '''
            }
        }

        stage('Run Tests') {
            steps {
                sh '''
                . venv/bin/activate
                python manage.py test
                '''
            }
        }

        stage('SonarQube Scan') {
            steps {
                withSonarQubeEnv('SonarQube') {
                    sh """
                    ${SONAR_SCANNER_HOME}/bin/sonar-scanner \
                    -Dsonar.projectKey=django-todo \
                    -Dsonar.sources=. \
                    -Dsonar.python.version=3
                    """
                }
            }
        }

        stage('Quality Gate') {
            steps {
                timeout(time: 5, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        stage('Build Docker Image') {
            steps {
                sh '''
                docker build -t $DOCKER_IMAGE:$IMAGE_TAG .
                docker tag $DOCKER_IMAGE:$IMAGE_TAG $DOCKER_IMAGE:latest
                '''
            }
        }

        stage('Trivy Image Scan') {
            steps {
                sh '''
                trivy image \
                --exit-code 0 \
                --severity LOW,MEDIUM \
                --format table \
                $DOCKER_IMAGE:$IMAGE_TAG

                trivy image \
                --exit-code 1 \
                --severity HIGH,CRITICAL \
                --format table \
                $DOCKER_IMAGE:$IMAGE_TAG
                '''
            }
            post {
                always {
                    sh '''
                    trivy image \
                    --format json \
                    --output trivy-report.json \
                    $DOCKER_IMAGE:$IMAGE_TAG || true
                    '''
                    archiveArtifacts artifacts: 'trivy-report.json',
                                     allowEmptyArchive: true
                }
            }
        }

        stage('Run Container for ZAP Scan') {
            steps {
                sh '''
                docker stop django-test || true
                docker rm django-test   || true
                docker run -d \
                -p 8000:8000 \
                --name django-test \
                $DOCKER_IMAGE:$IMAGE_TAG
                sleep 15
                '''
            }
        }

        stage('OWASP ZAP Scan') {
            steps {
                sh '''
                docker run --rm \
                owasp/zap2docker-stable \
                zap-baseline.py \
                -t http://host.docker.internal:8000 \
                -I
                '''
            }
        }

        stage('Stop Test Container') {
            steps {
                sh '''
                docker stop django-test || true
                docker rm django-test   || true
                '''
            }
        }

        stage('Docker Login & Push') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'dockerhub-creds',
                    usernameVariable: 'DOCKER_USER',
                    passwordVariable: 'DOCKER_PASS'
                )]) {
                    sh '''
                    echo $DOCKER_PASS | docker login \
                    -u $DOCKER_USER --password-stdin

                    docker push $DOCKER_IMAGE:$IMAGE_TAG
                    docker push $DOCKER_IMAGE:latest
                    '''
                }
            }
        }

    }

    post {
        success {
            echo '✅ Pipeline completed successfully!'
            echo "🐳 Image pushed: ${DOCKER_IMAGE}:${IMAGE_TAG}"
        }
        failure {
            echo '❌ Pipeline failed — check logs above'
        }
        always {
            sh 'docker logout || true'
            sh 'docker rmi $DOCKER_IMAGE:$IMAGE_TAG || true'
        }
    }
}
