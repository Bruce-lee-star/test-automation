package com.hsbc.cmb.hk.dbb.automation.tests.web;

import io.cucumber.junit.CucumberOptions;
import net.serenitybdd.cucumber.CucumberWithSerenity;
import org.junit.runner.RunWith;

/**
 * 本地 route-demo 并行 GREEN 冒烟运行器 —— 验证 CON-1 引擎级并行。
 *
 * <p>2026-09-27 P0 回退：与主运行器一致，由 JUnit5 {@code @Suite} 回退
 * {@code @RunWith(CucumberWithSerenity.class)}（Serenity 主体接管，tags 确定性写入）。</p>
 *
 * <p>前置：route-demo-service 须在 http://localhost:8888 启动；
 * 运行：{@code mvn verify -Dit.test=CucumberRouteDemoParallelSmokeRunnerIT
 * -Dplaywright.browser.headless=true}</p>
 */
@RunWith(CucumberWithSerenity.class)
@CucumberOptions(
        features = "src/test/resources/features/web/route_demo_parallel_smoke.feature",
        glue = "com.hsbc.cmb.hk.dbb.automation.tests.glue.parallel",
        plugin = {
            "pretty",
            "html:target/cucumber-report.html",
            "json:target/cucumber-report.json"
        },
        tags = "@route-parallel-smoke",
        dryRun = false
)
@SuppressWarnings("deprecation")
public class CucumberRouteDemoParallelSmokeRunnerIT {

}
