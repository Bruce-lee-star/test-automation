package com.hsbc.cmb.hk.dbb.automation.tests.web;

import io.cucumber.junit.Cucumber;
import io.cucumber.junit.CucumberOptions;
import org.junit.runner.RunWith;

/**
 * 并发登录运行器 —— 验证 SSO 感知并发闸门（ConcurrencyGate）+ 框架自建并发执行器（每线程独立 Browser）。
 *
 * <p><b>M2（DEV-V5 去 Serenity）</b>：由 {@code @RunWith(CucumberWithSerenity.class)} 迁至
 * {@code @RunWith(Cucumber.class)} + {@code FrameworkCucumberEventListener}，与主运行器对齐。</p>
 *
 * <p>真正并发由框架自建，而非 Serenity 并行：{@code serenity.parallel.for.tests} 在此运行器
 * 语义为框架并发执行器的并行度（经框架配置映射）。</p>
 */
@RunWith(Cucumber.class)
@CucumberOptions(
        features = "src/test/resources/features/web/concurrent_logon_dbb.feature",
        glue = {"com.hsbc.cmb.hk.dbb.automation.tests.glue",
                "com.hsbc.cmb.hk.dbb.automation.framework.web.concurrent"},
        plugin = {
            "pretty",
            "html:target/cucumber-report.html",
            "json:target/cucumber-report.json",
            "rerun:target/rerun.txt",
            // M2：框架 Cucumber 事件适配器。JUnit4 @RunWith(Cucumber.class) 不做插件 SPI 发现，必须显式声明。
            "com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.FrameworkCucumberEventListener"
        },
        tags = "@concurrent-logon",
        dryRun = false
)
@SuppressWarnings("deprecation")
public class CucumberConcurrentLogonRunnerIT {

}
