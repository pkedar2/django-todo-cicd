pipeline {
    agent any

    environment {
        SONAR_SCANNER    = tool 'sonar-scanner'
        DOCKER_IMAGE     = "pranavkedar/django-todo-cicd"
        DOCKER_TAG       = "${BUILD_NUMBER}"
        SONAR_PROJECT    = "django-todo-cicd"
    }

    options {
        timeout(time: 30, unit: 'MINUTES')
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10'))
        timestamps()
    }

    stages {

        stage('Git Clone') {
            steps {
                cleanWs()
                git credentialsId: 'github-creds',
                    url: 'https://github.com/pkedar2/django-todo-cicd',
                    branch: 'main'
            }
        }

        stage('Docker Build') {
            steps {
                sh """
                    docker build \
                        --no-cache \
                        --build-arg BUILD_NUMBER=${BUILD_NUMBER} \
                        -t ${DOCKER_IMAGE}:${DOCKER_TAG} \
                        -t ${DOCKER_IMAGE}:${DOCKER_TAG} \
                        .
                """
            }
        }

        stage('Security Scans') {
            parallel {

                stage('OWASP Dependency Check') {
                    steps {
                        script {
                            try {
                                dependencyCheck(
                                    additionalArguments: '--scan ./ --format HTML --format XML --prettyPrint',
                                    odcInstallation: 'OWASP-DC'
                                )
                            } catch (Exception e) {
                                echo "OWASP scan failed: ${e.message} — continuing pipeline"
                            }
                        }
                    }
                    post {
                        always {
                            script {
                                try {
                                    dependencyCheckPublisher pattern: '**/dependency-check-report.xml'
                                } catch (Exception e) {
                                    echo "No OWASP report found — skipping publish"
                                }
                            }
                        }
                    }
                }

                stage('SonarQube Analysis') {
                    steps {
                        withSonarQubeEnv('SonarQube') {
                            sh """
                                ${SONAR_SCANNER}/bin/sonar-scanner \
                                -Dsonar.projectKey=${SONAR_PROJECT} \
                                -Dsonar.projectName=${SONAR_PROJECT} \
                                -Dsonar.sources=. \
                                -Dsonar.exclusions=**/owasp-reports/**,**/dependency-check-report.*,**/.scannerwork/**
                            """
                        }
                    }
                }

                stage('Trivy Image Scan') {
                    steps {
                        sh """
                            trivy image \
                                --exit-code 0 \
                                --severity LOW,MEDIUM,HIGH,CRITICAL \
                                --ignorefile .trivyignore \
                                --no-progress \
                                --format table \
                                --output trivy-report.txt \
                                ${DOCKER_IMAGE}:${DOCKER_TAG}

                            echo "========== CVE SUMMARY =========="
                            echo "CRITICAL : \$(grep -c 'CRITICAL' trivy-report.txt || true)"
                            echo "HIGH     : \$(grep -c 'HIGH'     trivy-report.txt || true)"
                            echo "MEDIUM   : \$(grep -c 'MEDIUM'   trivy-report.txt || true)"
                            echo "LOW      : \$(grep -c 'LOW'      trivy-report.txt || true)"
                            echo "=================================="

                            # Exit only on critical CVEs
                            trivy image \
                                --exit-code 1 \
                                --severity CRITICAL \
                                --ignorefile .trivyignore \
                                --no-progress \
                                ${DOCKER_IMAGE}:${DOCKER_TAG}
                        """
                    }
                    post {
                        always {
                            archiveArtifacts artifacts: 'trivy-report.txt',
                                             fingerprint: true
                        }
                        failure {
                            echo "Unacknowledged CRITICAL CVEs found — fix or add to .trivyignore with justification"
                        }
                    }
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

        stage('Push to DockerHub') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'dockerhub-cred',
                    usernameVariable: 'DOCKER_USER',
                    passwordVariable: 'DOCKER_PASS'
                )]) {
                    sh """
                        echo ${DOCKER_PASS} | docker login -u ${DOCKER_USER} --password-stdin
                        docker push ${DOCKER_IMAGE}:${DOCKER_TAG}
                    """
                }
            }
        }

    }

    post {
        success {
            echo "Pipeline SUCCESS! Image pushed: ${DOCKER_IMAGE}:${DOCKER_TAG}"
        }
        failure {
            echo "Pipeline FAILED! Job: ${JOB_NAME} | Build: ${BUILD_NUMBER} | Logs: ${BUILD_URL}console"
        }
        always {
            sh "docker rmi ${DOCKER_IMAGE}:${DOCKER_TAG} || true"
            sh "docker rmi ${DOCKER_IMAGE}:${DOCKER_TAG} || true"
            sh "docker image prune -f || true"
            cleanWs()
        }
    }
}
