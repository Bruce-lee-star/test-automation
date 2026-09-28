package com.hsbc.cmb.hk.dbb.automation.tests.web;

import io.cucumber.junit.CucumberOptions;
import net.serenitybdd.cucumber.CucumberWithSerenity;
import org.junit.runner.RunWith;

/**
 * 并发登录运行器 —— 验证 SSO 感知并发闸门（ConcurrencyGate）+ 框架自建并发执行器（每线程独立 Browser）。
 *
 * <p>2026-09-27 P0 回退：与主运行器一致，由 JUnit5 {@code @Suite} 回退
 * {@code @RunWith(CucumberWithSerenity.class)}（Serenity 主体接管，tags 确定性写入）。</p>
 *
 * <p>真正并发由框架自建，而非 Serenity 并行：{@code serenity.parallel.for.tests} 在此运行器
 * 语义为框架并发执行器的并行度（经框架配置映射）。</p>
 */
@RunWith(CucumberWithSerenity.class)
@CucumberOptions(
        features = "src/test/resources/features/web/concurrent_logon_dbb.feature",
        glue = {"com.hsbc.cmb.hk.dbb.automation.tests.glue",
                "com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent"},
        plugin = {
            "pretty",
            "html:target/cucumber-report.html",
            "json:target/cucumber-report.json"
        },
        tags = "@concurrent-logon",
        dryRun = false
)
@SuppressWarnings("deprecation")
public class CucumberConcurrentLogonRunnerIT {

}
