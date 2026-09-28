import io.cucumber.junit.CucumberOptions;
import net.serenitybdd.cucumber.CucumberWithSerenity;
import org.junit.runner.RunWith;

/**
 * Cucumber Test Runner (Serenity + JUnit 4)。
 *
 * <p>2026-09-27 P0 回退：执行器由 JUnit5 {@code @Suite + cucumber 引擎}（Serenity 旁挂
 * SerenityReporterParallel，TestOutcome.getTags() 恒空 → @firefox/@edge 覆盖失效 → 回落
 * chromium → DBB 登录白屏）回退至 master 方案 {@code @RunWith(CucumberWithSerenity.class)}：
 * Serenity 作为执行主体接管 Cucumber 生命周期，scenario tags 天然写入 TestOutcome，
 * AutoBrowser 浏览器覆盖在启动决策点确定性生效。</p>
 *
 * <p>标签：默认 {@code @test}（对齐 master）。如需动态覆盖，用
 * {@code -Dtags=@xxx}（failsafe 注入 cucumber.filter.tags，覆盖本注解；见 test-automation pom）。</p>
 */
@RunWith(CucumberWithSerenity.class)
@CucumberOptions(
        features = "src/test/resources/features",
        glue = {"com.hsbc.cmb.hk.dbb.automation.tests.glue",
                "com.hsbc.cmb.hk.dbb.automation.tests.api.steps"},
        plugin = {
            "pretty",
            "html:target/cucumber-report.html",
            "json:target/cucumber-report.json",
            "rerun:target/rerun.txt"
        },
        tags = "@test",
        dryRun = false
)
@SuppressWarnings("deprecation")
public class CucumberTestRunnerIT {

}
