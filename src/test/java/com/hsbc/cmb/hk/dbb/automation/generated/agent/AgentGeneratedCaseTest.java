package com.hsbc.cmb.hk.dbb.automation.generated.agent;

import io.cucumber.junit.Cucumber;
import io.cucumber.junit.CucumberOptions;
import org.junit.runner.RunWith;

/**
 * 路径 C 生成 case 的独立验证 Runner（不启动浏览器）。
 *
 * <p>刻意使用原生 {@link Cucumber} 而非 {@code CucumberWithPw}：本验证只跑
 * 「Feature → Glue → Steps → StepPlanActionSink」委派链，不需要框架生命周期监听器、
 * 浏览器与报告插件，避免引入与生成物本身无关的失败源。</p>
 *
 * <p><b>零标签</b>：feature 位于 {@code src/test/resources/agent-cases/}（主 features 目录之外），
 * 既保留生成物「无 tag」原貌，又不进入 {@code FeatureTagTaxonomyTest} 的三维标签门禁与主
 * {@code CucumberTestRunnerIT} 的 {@code tags="@test"} 选择范围；本 Runner 不加 tags 过滤，直接执行。</p>
 *
 * <p>运行：{@code mvn -o test -Dskip.unit.tests=false -Dtest=AgentGeneratedCaseTest}</p>
 */
@RunWith(Cucumber.class)
@CucumberOptions(
        features = "src/test/resources/agent-cases",
        glue = "com.hsbc.cmb.hk.dbb.automation.generated.agent",
        plugin = {"pretty"},
        dryRun = false
)
public class AgentGeneratedCaseTest {
}
