package com.hsbc.cmb.hk.dbb.automation.tests.web;

import io.cucumber.junit.CucumberOptions;
import net.serenitybdd.cucumber.CucumberWithSerenity;
import org.junit.runner.RunWith;

/**
 * 并行浏览器隔离运行器 —— 参照 logon DBB，验证 T3-2「每线程独立 Browser」。
 *
 * <p>2026-09-27 P0 回退：与主运行器一致，由 JUnit5 {@code @Suite} 回退
 * {@code @RunWith(CucumberWithSerenity.class)}（Serenity 主体接管，tags 确定性写入）。</p>
 *
 * <p><b>运行方式（关键：开启 Serenity 场景级并行）</b>：
 * {@code mvn verify -Dit.test=CucumberParallelLogonRunnerIT -Dserenity.parallel.for.tests=4
 * -Dplaywright.browser.headless=true}</p>
 */
@RunWith(CucumberWithSerenity.class)
@CucumberOptions(
        features = "src/test/resources/features/web/parallel_logon_dbb.feature",
        glue = "com.hsbc.cmb.hk.dbb.automation.tests.glue",
        plugin = {
            "pretty",
            "html:target/cucumber-report.html",
            "json:target/cucumber-report.json"
        },
        tags = "@parallel-logon",
        dryRun = false
)
@SuppressWarnings("deprecation")
public class CucumberParallelLogonRunnerIT {

}
