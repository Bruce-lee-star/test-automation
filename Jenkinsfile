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
        string(name: 'PLAYWRIGHT_BROWSERS_PATH', defaultValue: 'C:\\ProgramData\\Jenkins\\ms-playwright',
            description: 'Playwright 浏览器/ffmpeg 持久缓存目录（须在工作区之外，避免 git clean / deleteDir 清掉；按机器级预置）')
        string(name: 'BROWSER_CACHE_MAX_AGE_DAYS', defaultValue: '7',
            description: '持久缓存中超过该天数未使用的旧版浏览器/ffmpeg 目录将被清理，防止磁盘堆积')
    }

    environment {
        MAVEN_HOME = 'D:\\Software\\apache-maven-3.9.9'
        JAVA_HOME = 'D:\\Software\\JDK\\JDK-21.0.9'
        PATH = "${env.MAVEN_HOME}\\bin;${env.JAVA_HOME}\\bin;${env.PATH}"
        // 须与 test-automation/src/test/resources/pw.properties 的 framework.output.directory 一致——
        // 框架报告（index.html / email-report.html / pw-junit.xml / assets）落盘到此目录。
        REPORT_DIR = 'target/site/serenity'

        // ── CI 专属覆盖（不写进仓库配置文件，靠环境层生效）──
        // 浏览器/ffmpeg 落到工作区之外的持久目录，git clean -fdx 与 deleteDir 都清不到，跨构建复用
        PLAYWRIGHT_BROWSERS_PATH = "${params.PLAYWRIGHT_BROWSERS_PATH}"
        // CI 必须无头（Jenkins 服务无桌面），本地保持仓库默认 false
        PLAYWRIGHT_BROWSER_HEADLESS = 'true'
        // 锁定走系统 Chrome（channel 模式不下载浏览器包，仅需 ffmpeg）
        PLAYWRIGHT_BROWSER_CHANNEL = 'chrome'
        // 供清理脚本读取的保留天数
        BROWSER_CACHE_MAX_AGE_DAYS = "${params.BROWSER_CACHE_MAX_AGE_DAYS}"
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

        stage('Provision & Prune Browsers') {
            steps {
                script {
                    // 1) 幂等预装：CI 强制 channel=chrome（environment 已设），系统提供 chromium，
                    //    故只预装 ffmpeg（录视频编码所需）。仅当持久缓存缺失时下载，已存在则秒过。
                    //    若将来关闭 channel，框架非 channel 分支会在运行时自动下载 chromium+ffmpeg，无需在此兜底。
                    //    （依赖本 job 已 install 的框架/playwright 依赖；若公司网需代理，给 environment 加 HTTP_PROXY/HTTPS_PROXY）
                    //    非致命：预装失败（如临时网络受限）不阻断流水线，运行时框架会再次尝试安装并给出明确报错。
                    def installRc = bat(returnStatus: true, script: "mvn -q -B exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args=\"install ffmpeg\"")
                    if (installRc != 0) {
                        echo "WARN: browser/ffmpeg pre-install failed (rc=${installRc}); runtime will attempt install or fail with a clear message"
                    }

                    // 2) 即时清理：删除持久缓存里超过 N 天未使用的旧版浏览器/ffmpeg 目录。
                    //    Playwright 升级后会留下 chromium-XXXX / ffmpeg-XXXX 旧目录，长期堆积撑爆磁盘。
                    //    按"最后写入时间"裁剪——当前版刚写入不会被删，仅清历史旧版；且本 job 设了
                    //    disableConcurrentBuilds，不会出现并发构建误删正在使用的版本。
                    powershell(returnStatus: true, script: '''
                        $cache = $env:PLAYWRIGHT_BROWSERS_PATH
                        if (-not (Test-Path $cache)) { Write-Output "cache dir not found: $cache"; exit 0 }
                        $maxAge = [int]$env:BROWSER_CACHE_MAX_AGE_DAYS
                        $cutoff = (Get-Date).AddDays(-$maxAge)
                        $prefixes = @('chromium','chromium_headless_shell','chrome','msedge','firefox','webkit','ffmpeg')
                        $removed = 0
                        foreach ($p in $prefixes) {
                            Get-ChildItem $cache -Directory -Filter "$p-*" |
                                Where-Object { $_.LastWriteTime -lt $cutoff } |
                                ForEach-Object { Remove-Item $_.FullName -Recurse -Force -ErrorAction SilentlyContinue; $removed++ }
                        }
                        Write-Output "browser cache pruned: removed $removed stale dir(s) older than $maxAge day(s)"
                    ''')
                }
            }
        }

        stage('Run tests') {
            steps {
                catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                    bat """
                        @chcp 65001 >NUL
                        // 注：test-automation 已自包含（无 checkstyle/spotbugs/cve 插件），原先的
                        // -Dcheckstyle.skip/-Dspotbugs.skip/-Dcve.gate.skip 均为死参数，已移除。
                        mvn -q -B test -Dtest=CucumberTestRunnerIT -Dcucumber.filter.tags="${params.TAGS}" -Dsurefire.failIfNoSpecifiedTests=false -Dfile.encoding=UTF-8
                    """
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
                    // 框架报告聚合 junit 结果（原 Serenity 的 serenity-junit.xml 已随 Serenity 移除）
                    junit testResults: "${REPORT_DIR}/pw-junit.xml",
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
                                subject: "[E2E] ${env.JOB_NAME} #${env.BUILD_NUMBER} - ${currentBuild.currentResult}",
                                body: readFile(file: emailFile),
                                mimeType: 'text/html'
                            )
                        } else {
                            emailext(
                                to: params.EMAIL_TO, from: params.EMAIL_FROM,
                                subject: "[E2E] ${env.JOB_NAME} #${env.BUILD_NUMBER} - ${currentBuild.currentResult}",
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
