#!/usr/bin/groovy

@Library(['github.com/indigo-dc/jenkins-pipeline-library']) _

pipeline {
    agent {
        label 'java'
    }

    environment {
        dockerhub_repo = "indigodatacloud/orchestrator"
    }

    stages {
        stage('Fetch code') {
            steps {
                checkout scm
            }
        }

        stage('Style Analysis') {
            steps {
                MavenRun('checkstyle')
            }
            post {
                always {
                    CheckstyleReport()
                    dir("$WORKSPACE/target") {
                        deleteDir()
                    }
                }
            }
        }

        stage('Unit testing coverage') {
            steps {
                MavenRun('cobertura')
            }
            post {
                success {
                    CoberturaReport('**/target/site/cobertura/coverage.xml')
                    JUnitReport()
                    dir("$WORKSPACE/target") {
                        deleteDir()
                    }
                }
            }
        }

        stage('Integration tests') {
            steps {
                MavenRun('integration-test')
            }
            post {
                success {
                    JUnitReport()
                }
            }
        }

        stage('Metrics') {
            agent {
                label 'sloc'
            }
            steps {
                checkout scm
                SLOCRun()
            }
            post {
                success {
                    SLOCPublish()
                }
            }
        }

		stage('DockerHub delivery') {
			when {
				anyOf {
				    branch 'master'
				    buildingTag()
				}
			}
			agent {
				label 'docker-build'
			}
			steps {
				checkout scm
				dir("$WORKSPACE/docker") {
					script {
						image_id = DockerBuild(dockerhub_repo, env.BRANCH_NAME)
					}
				}
			}
			post {
				success {
					DockerPush(image_id)
				}
				failure {
					DockerClean()
				}
				always {
					cleanWs()
				}
			}
        }
    } // stages
} // pipeline