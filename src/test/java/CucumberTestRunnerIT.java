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
 * <p><b>标签来源（2026-10-10 实测）</b>：动态覆盖 {@code -Dtags=@xxx} 时，<b>实际生效的是系统属性
 * {@code tags}</b>（Serenity 的 Cucumber 集成读取它）；pom 注入的 {@code cucumber.filter.tags}
 * 不决定结果 —— {@code mvn -X} 显示两个属性同存（{@code cucumber.filter.tags=@__guard__} 与
 * {@code tags=@route-e2e}）时，仍按 {@code tags} 过滤，跑了 7 个场景。</p>
 *
 * <p><b>为什么 E2E 必须由 failsafe 执行（不能落在 surefire 的 test 阶段）</b>：
 * {@code -Dtest=<本类>} 会覆盖 surefire 的 {@code excludes} 把它拉进 test 阶段；该阶段一旦失败，
 * 构建立即中止 ⇒ {@code pre-integration-test} / {@code integration-test} /
 * {@code post-integration-test} 全部不执行 ⇒ {@code serenity:aggregate} 与
 * {@code SummaryReportGenerator} 都不运行 ⇒ <b>没有测试报告</b>（实测：test 阶段 7 errors ⇒
 * BUILD FAILURE，报告阶段 0 次执行）。</p>
 *
 * <p>因此 E2E 的运行方式固定为 {@code mvn clean verify -Dskip.unit.tests=true -Dtags='@xxx'}
 * （{@code skipTests} 早于 {@code -Dtest} 生效，surefire 整个跳过 ⇒ 只跑 Cucumber）。
 * 运行器内自锁（缺标记时改标签属性）经实测<b>无效</b>：Serenity 在本类初始化之前就已解析 tags，
 * 仍跑了全部场景 —— 故不采用该做法。</p>
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
        tags = "@route-e2e",
        dryRun = false
)
@SuppressWarnings("deprecation")
public class CucumberTestRunnerIT {

}
