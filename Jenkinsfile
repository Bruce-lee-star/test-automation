// =============================================================================
//  test-automation — Jenkins Pipeline (Declarative)
//  流程：Cleanup → Checkout → Run CucumberTestRunnerIT → Verify report → 发布报告
//  不重新编译框架，依赖 agent 本地仓已 install 的框架产物。
// =============================================================================

pipeline {
    agent any

    options {
        timestamps()
        timeout(time: 120, unit: 'MINUTES')
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '5'))
        skipDefaultCheckout()
    }

    parameters {
        string(name: 'TAGS', defaultValue: '@test', description: 'Cucumber tag 过滤')
        string(name: 'EMAIL_TO', defaultValue: '1668464993@qq.com', description: '报告收件人（留空则跳过邮件）')
        string(name: 'EMAIL_FROM', defaultValue: '1274542814@qq.com', description: '发件人（须等于 SMTP 认证账号）')
        string(name: 'SCM_URL', defaultValue: 'https://github.com/Bruce-lee-star/test-automation.git', description: 'test-automation 仓库 URL')
        string(name: 'SCM_BRANCH', defaultValue: 'DEV-V5', description: 'test-automation 分支')
    }

    environment {
        MAVEN_HOME = 'D:\\Software\\apache-maven-3.9.9'
        JAVA_HOME = 'D:\\Software\\JDK\\JDK-21.0.9'
        PATH = "${env.MAVEN_HOME}\\bin;${env.JAVA_HOME}\\bin;${env.PATH}"
        REPORT_DIR = 'target/site/serenity'
    }

    stages {

        stage('Cleanup') {
            steps {
                powershell(returnStatus: true, script: '''
                    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue -Path 'target','_e2e_artifacts*'
                    Remove-Item -Force -ErrorAction SilentlyContinue -Path '*.log','*.tmp'
                    Write-Output 'cleanup done'
                ''')
            }
        }

        stage('Checkout test-automation') {
            steps {
                checkout([$class: 'GitSCM',
                    branches: [[name: "*/${params.SCM_BRANCH}"]],
                    userRemoteConfigs: [[url: params.SCM_URL]],
                    extensions: [[$class: 'CleanCheckout']]
                ])
            }
        }

        stage('Run tests') {
            steps {
                catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                    bat "mvn -q -B test -Dtest=CucumberTestRunnerIT -Dcucumber.filter.tags=\"${params.TAGS}\" -Dsurefire.failIfNoSpecifiedTests=false -Dcheckstyle.skip=true -Dspotbugs.skip=true -Dcve.gate.skip=true"
                }
            }
        }

        stage('Verify report') {
            steps {
                script {
                    bat """
                        echo ==================== report tree ====================
                        if exist "${REPORT_DIR}" (dir /b "${REPORT_DIR}") else (echo MISSING REPORT DIR: ${REPORT_DIR})
                        echo ---------------------- assets ----------------------
                        if exist "${REPORT_DIR}\\assets" (dir /b "${REPORT_DIR}\\assets") else (echo MISSING: ${REPORT_DIR}\\assets)
                    """
                    if (!fileExists("${REPORT_DIR}/index.html")) {
                        error("报告目录里没有 index.html（${REPORT_DIR}）")
                    }
                    if (!fileExists("${REPORT_DIR}/assets/report-v2.css")) {
                        echo "WARN: missing ${REPORT_DIR}/assets/report-v2.css"
                    } else {
                        echo "OK: report artifacts complete"
                    }
                }
            }
        }
    }

    post {
        always {
            script {
                try {
                    junit testResults: "${REPORT_DIR}/serenity-junit.xml",
                          allowEmptyResults: true, healthScaleFactor: 1.0
                } catch (e) { echo "junit parse failed (non-fatal): ${e.getMessage()}" }
            }

            script {
                try {
                    publishHTML(target: [
                        allowMissing: true, alwaysLinkToLastBuild: true, keepAll: true,
                        reportDir: "${REPORT_DIR}", reportFiles: 'index.html',
                        reportName: 'Test Report', escapeUnderscores: false
                    ])
                } catch (e) { echo "HTML Publisher not available: ${REPORT_DIR}" }
            }

            archiveArtifacts artifacts: "${REPORT_DIR}/**/*",
                             followSymlinks: false, allowEmptyArchive: true

            script {
                def reportUrl = "${BUILD_URL}artifact/${REPORT_DIR}/index.html"
                def emailUrl  = "${BUILD_URL}artifact/${REPORT_DIR}/email-report.html"
                currentBuild.displayName = "#${env.BUILD_NUMBER} ${params.TAGS} · ${currentBuild.currentResult}"
                currentBuild.description =
                        "tags ${params.TAGS} · ${currentBuild.currentResult}" +
                        " · <a href='${reportUrl}' target='_blank'>Test Report</a>" +
                        " · <a href='${emailUrl}' target='_blank'>Email Report</a>"
            }

            script {
                if (params.EMAIL_TO?.trim()) {
                    def emailFile = "${REPORT_DIR}/email-report.html"
                    def onlineUrl = "${BUILD_URL}artifact/${REPORT_DIR}/index.html"
                    try {
                        if (fileExists(emailFile)) {
                            emailext(
                                to: params.EMAIL_TO,
                                from: params.EMAIL_FROM,
                                subject: "Test Report ${env.JOB_NAME} #${env.BUILD_NUMBER} - ${currentBuild.currentResult}",
                                body: readFile(file: emailFile),
                                mimeType: 'text/html'
                            )
                        } else {
                            emailext(
                                to: params.EMAIL_TO, from: params.EMAIL_FROM,
                                subject: "Test Report ${env.JOB_NAME} #${env.BUILD_NUMBER} - ${currentBuild.currentResult}",
                                body: "Report HTML missing. View online: ${onlineUrl}",
                                mimeType: 'text/plain'
                            )
                        }
                    } catch (e) {
                        echo "email send failed (non-fatal): ${e.getMessage()}"
                    }
                }
            }

            deleteDir()
        }
    }
}
