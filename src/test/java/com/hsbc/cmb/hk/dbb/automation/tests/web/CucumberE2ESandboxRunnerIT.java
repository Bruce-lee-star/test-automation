package com.hsbc.cmb.hk.dbb.automation.tests.web;

import io.cucumber.junit.Cucumber;
import io.cucumber.junit.CucumberOptions;
import org.junit.runner.RunWith;

/**
 * E2E 真实浏览器沙箱运行器。
 *
 * <p><b>M2（DEV-V5 去 Serenity）</b>：由 {@code @RunWith(CucumberWithSerenity.class)} 迁至
 * {@code @RunWith(Cucumber.class)} + {@code FrameworkCucumberEventListener}，与主运行器
 * {@code CucumberTestRunnerIT} 对齐——框架自有生命周期总线驱动（driver 初始化 / 截图 / 浏览器 / 上报）。
 * Serenity 仅在默认构建共存；{@code -Ppw-report-v2} 下完全走框架路径。</p>
 *
 * <p>页面由 {@code setContent} 注入本地 HTML，无需外网 / DBB 环境 / REST。</p>
 */
@RunWith(Cucumber.class)
@CucumberOptions(
        features = "src/test/resources/features/web/e2e_sandbox.feature",
        glue = "com.hsbc.cmb.hk.dbb.automation.tests.glue",
        plugin = {
            "pretty",
            "html:target/cucumber-report.html",
            "json:target/cucumber-report.json",
            "rerun:target/rerun.txt",
            // M2：框架 Cucumber 事件适配器。JUnit4 @RunWith(Cucumber.class) 不做插件 SPI 发现，
            // 必须显式声明，否则框架生命周期总线不被驱动（driver 初始化/截图/浏览器/上报）。
            "com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.event.FrameworkCucumberEventListener"
        },
        tags = "@e2e-sandbox",
        dryRun = false
)
@SuppressWarnings("deprecation")
public class CucumberE2ESandboxRunnerIT {

}
