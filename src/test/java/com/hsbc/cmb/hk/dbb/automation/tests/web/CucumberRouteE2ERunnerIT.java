package com.hsbc.cmb.hk.dbb.automation.tests.web;

import io.cucumber.junit.CucumberOptions;
import net.serenitybdd.cucumber.CucumberWithSerenity;
import org.junit.runner.RunWith;

/**
 * Route V2 能力真实 E2E 运行器（真浏览器 + 真实 DBB SIT）。
 *
 * <p>与主运行器同构（JUnit4 {@code @RunWith(CucumberWithSerenity.class)}，Serenity 主体接管，
 * tags 确定性写入 TestOutcome），只跑 {@code route_capability_e2e.feature}。</p>
 *
 * <p><b>运行</b>（含 Serenity 聚合与 {@code serenity-summary.html} 生成）：</p>
 * <pre>
 *   mvn verify -Dit.test=CucumberRouteE2ERunnerIT -Dplaywright.browser.headless=true
 * </pre>
 * <p>产物：{@code target/site/serenity/index.html}（Serenity 聚合）与
 * {@code target/site/serenity/serenity-summary.html}（SummaryReportGenerator 汇总）。</p>
 *
 * <p><b>前提</b>：依赖公司内网可达 DBB SIT 站点与 {@code serenity.conf} 中的环境/用户凭据。</p>
 */
@RunWith(CucumberWithSerenity.class)
@CucumberOptions(
        features = "src/test/resources/features/web/route_capability_e2e.feature",
        glue = "com.hsbc.cmb.hk.dbb.automation.tests.glue",
        plugin = {
            "pretty",
            "html:target/cucumber-route-e2e-report.html",
            "json:target/cucumber-route-e2e-report.json"
        },
        tags = "@route-e2e",
        dryRun = false
)
@SuppressWarnings("deprecation")
public class CucumberRouteE2ERunnerIT {

}
