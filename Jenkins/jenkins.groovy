pipeline {
    agent any

    environment {
        DOCKER_IMAGE = "pranavkedar/todo"
        SONARQUBE_ENV = "SonarScanner"
        IMAGE_TAG = "${BUILD_NUMBER}"
    }

    stages {

        stage('Checkout Code') {
            steps {
                git 'https://github.com/pkedar2/django-todo-cicd.git'
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
                withSonarQubeEnv("${SONARQUBE_ENV}") {
                    sh '''
                    sonar-scanner \
                    -Dsonar.projectKey=django-todo \
                    -Dsonar.sources=.
                    '''
                }
            }
        }

        stage('Build Docker Image') {
            steps {
                sh '''
                docker build -t $DOCKER_IMAGE:$IMAGE_TAG .
                '''
            }
        }

        stage('Run Container for ZAP Scan') {
            steps {
                sh '''
                docker run -d -p 8000:8000 --name django-test $DOCKER_IMAGE:$IMAGE_TAG
                sleep 15
                '''
            }
        }

        stage('Security Scans') {

            parallel {

                stage('Trivy Scan') {
                    steps {
                        sh '''
                        trivy image --exit-code 1 --severity HIGH,CRITICAL $DOCKER_IMAGE:$IMAGE_TAG
                        '''
                    }
                }

                stage('OWASP ZAP Scan') {
                    steps {
                        sh '''
                        docker run --rm \
                        owasp/zap2docker-stable \
                        zap-baseline.py \
                        -t http://host.docker.internal:8000
                        '''
                    }
                }

            }
        }

        stage('Stop Test Container') {
            steps {
                sh '''
                docker stop django-test || true
                docker rm django-test || true
                '''
            }
        }

        stage('Docker Login') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'dockerhub-creds',
                    usernameVariable: 'DOCKER_USER',
                    passwordVariable: 'DOCKER_PASS'
                )]) {
                    sh '''
                    echo $DOCKER_PASS | docker login -u $DOCKER_USER --password-stdin
                    '''
                }
            }
        }

        stage('Push Image to Docker Hub') {
            steps {
                sh '''
                docker push $DOCKER_IMAGE:$IMAGE_TAG
                '''
            }
        }
    }

    post {
        success {
            echo "Pipeline completed successfully"
        }

        failure {
            echo "Pipeline failed"
        }
    }
}
