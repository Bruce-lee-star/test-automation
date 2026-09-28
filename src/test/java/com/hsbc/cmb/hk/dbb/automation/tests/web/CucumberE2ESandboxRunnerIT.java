package com.hsbc.cmb.hk.dbb.automation.tests.web;

import io.cucumber.junit.CucumberOptions;
import net.serenitybdd.cucumber.CucumberWithSerenity;
import org.junit.runner.RunWith;

/**
 * E2E 真实浏览器沙箱运行器。
 *
 * <p>2026-09-27 P0 回退：与主运行器一致，由 JUnit5 {@code @Suite} 回退
 * {@code @RunWith(CucumberWithSerenity.class)}（Serenity 主体接管，tags 确定性写入）。</p>
 *
 * <p>页面由 {@code setContent} 注入本地 HTML，无需外网 / DBB 环境 / REST。</p>
 */
@RunWith(CucumberWithSerenity.class)
@CucumberOptions(
        features = "src/test/resources/features/web/e2e_sandbox.feature",
        glue = "com.hsbc.cmb.hk.dbb.automation.tests.glue",
        plugin = {
            "pretty",
            "html:target/cucumber-report.html",
            "json:target/cucumber-report.json"
        },
        tags = "@e2e-sandbox",
        dryRun = false
)
@SuppressWarnings("deprecation")
public class CucumberE2ESandboxRunnerIT {

}
