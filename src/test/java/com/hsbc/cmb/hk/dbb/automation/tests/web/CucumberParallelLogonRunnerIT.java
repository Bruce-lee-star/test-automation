package com.hsbc.cmb.hk.dbb.automation.tests.web;

import io.cucumber.junit.Cucumber;
import io.cucumber.junit.CucumberOptions;
import org.junit.runner.RunWith;

/**
 * 并行浏览器隔离运行器 —— 参照 logon DBB，验证 T3-2「每线程独立 Browser」。
 *
 * <p><b>M2（DEV-V5 去 Serenity）</b>：由 {@code @RunWith(CucumberWithSerenity.class)} 迁至
 * {@code @RunWith(Cucumber.class)} + {@code FrameworkCucumberEventListener}，与主运行器对齐。
 * 注：{@code serenity.parallel.for.tests} 本就是历史空操作（Serenity 无 JVM 内并行），迁移无功能损失；
 * 每线程独立 Browser 由框架 ConcurrencyGate / 浏览器隔离保证。</p>
 *
 * <p><b>运行方式</b>：
 * {@code mvn verify -Dit.test=CucumberParallelLogonRunnerIT -Dplaywright.browser.headless=true}</p>
 */
@RunWith(Cucumber.class)
@CucumberOptions(
        features = "src/test/resources/features/web/parallel_logon_dbb.feature",
        glue = "com.hsbc.cmb.hk.dbb.automation.tests.glue",
        plugin = {
            "pretty",
            "html:target/cucumber-report.html",
            "json:target/cucumber-report.json",
            "rerun:target/rerun.txt",
            // M2：框架 Cucumber 事件适配器。JUnit4 @RunWith(Cucumber.class) 不做插件 SPI 发现，必须显式声明。
            "com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.FrameworkCucumberEventListener"
        },
        tags = "@parallel-logon",
        dryRun = false
)
@SuppressWarnings("deprecation")
public class CucumberParallelLogonRunnerIT {

}
