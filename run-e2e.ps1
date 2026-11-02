Set-Location D:\IdeaProject\test-automation
mvn verify -Dtest=CucumberTestRunnerIT -Ppw-report-v2 "-Dcve.gate.skip=true" "-Dcheckstyle.skip=true" "-Dspotbugs.skip=true" *> D:\IdeaProject\test-automation\1.txt
Write-Output "MVN_EXIT=$LASTEXITCODE"
