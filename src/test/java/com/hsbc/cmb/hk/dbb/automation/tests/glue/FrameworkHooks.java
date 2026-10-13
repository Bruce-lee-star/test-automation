package com.hsbc.cmb.hk.dbb.automation.tests.glue;

import com.hsbc.cmb.hk.dbb.automation.framework.common.assertion.SoftAssertions;
import com.hsbc.cmb.hk.dbb.automation.framework.common.cleanstate.CleanStateRegistry;
import com.hsbc.cmb.hk.dbb.automation.framework.common.context.LanguageState;
import com.hsbc.cmb.hk.dbb.automation.framework.core.context.ScenarioContext;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 框架级场景钩子（C-4 修复 + C-1 用例级隔离闭环 + B-6 统一状态清理）。
 *
 * <p><b>C-4（软断言收集器清理）</b>：{@link SoftAssertions} 基于 ThreadLocal 收集场景内软断言失败；
 * 若某场景收集失败却未 {@code assertAll()}（或中途 skip / 抛错），残留失败会随线程复用泄漏到下一场景。
 * 本钩子在 scenario 结束后无条件 {@code clearForCurrentThread()} 切断该残留传递。</p>
 *
 * <p><b>C-1（用例级隔离闭环）</b>：将上下文隔离从"线程纪律"升级为"机器强制"——
 * 每个 scenario 经 {@link ScenarioContext#begin(String)} / {@link ScenarioContext#end(String)} 绑定到
 * 用例 id（而非仅靠 per-thread），{@link ScenarioContext#assertUnbound()} 在 {@code @Before} 与
 * {@code @After} 两处校验：{@code @Before} 前置校验本线程无上一用例残留绑定（真实泄漏守卫），
 * {@code @After} 后置校验 end 已释放绑定（id 错配 / 腐败即抛）。并发子用例的等价边界由
 * {@code ConcurrentContextExecutor.runOnce} 经同一 {@link ScenarioContext} 强制，二者共用同一主键模型。</p>
 *
 * <p><b>B-6（统一状态清理）</b>：{@link CleanStateRegistry#resetAll()} 在 scenario 前后统一复位所有经 SPI
 * 注册的用例级状态（如 API 共享态 {@code api.baseStep}，由 {@code ApiTestContextResolver} 提供），
 * 替代各模块手写 {@code @After} 清理钩子；{@code @After} 另调 {@link CleanStateRegistry#dirtyNames()}
 * 做断言式清理（正常不应有残留）。</p>
 *
 * <p><b>顺序约定</b>：{@code @Before} 取最低 order 抢在其它钩子之前建好用例上下文；
 * {@code @After} 取最高 order 确保所有消费方（含 Serenity 报告钩子）仍可见上下文后再解绑。
 * 二者均对所有场景生效（skip / 失败 / 抛错场景 Cucumber 仍会执行 {@code @After}），故 skip 路径同样被强制解绑。</p>
 *
 * <p>注：本类即框架预留的"场景钩子"收口点，所有 scenario 经 {@code tests.glue} 均触发；
 * 用例级共享态的清理已下沉到 {@link CleanStateRegistry}，无需业务再维护 {@code ApiScenarioHooks} 之类的钩子。</p>
 */
public class FrameworkHooks {

    private static final Logger logger = LoggerFactory.getLogger(FrameworkHooks.class);

    /** scenario 开始前：先校验本线程无残留用例绑定（防上一用例泄漏），再绑定本用例上下文，并复位语言态与全部注册状态。 */
    @Before(order = Integer.MIN_VALUE)
    public void beforeScenario(Scenario scenario) {
        ScenarioContext.assertUnbound();
        ScenarioContext.begin(scenario.getId());
        clearScenarioScopedState();
    }

    /** scenario 结束后：先断言无残留、再复位全部注册状态与语言态，解除本用例绑定（防脏上下文泄漏到下一用例）。 */
    @After(order = Integer.MAX_VALUE)
    public void afterScenario(Scenario scenario) {
        try {
            //  B-6 断言式清理：先检查再复位。正常流程下当前场景态不判脏（BaseStep 戳==当前场景），
            //  仅当真实跨场景残留（@Before 复位未生效）时才触发告警；随后 resetAll() 兜底清除。
            List<String> residue = CleanStateRegistry.dirtyNames();
            if (!residue.isEmpty()) {
                logger.warn("CleanState residue detected before reset (unexpected cross-scenario leak): {}", residue);
            }
            clearScenarioScopedState();
        } finally {
            ScenarioContext.end(scenario.getId());
            ScenarioContext.assertUnbound();
        }
    }

    /**
     * 复位「scenario 级、且不受 {@code TestContextHolder} 托管」的共享状态：软断言收集器 + 语言态 + 框架注册状态。
     *
     * <p><b>C-8</b>：{@code LanguageState.globalLang} 是进程级全局值（跨线程可见性所必需），不在
     * {@code TestContextHolder} 清理范围内，会跨用例残留并在并行下造成语言态串扰；故在 scenario 前后
     * 显式复位，保证「当前语言」从干净态开始、且不泄漏到下一用例。</p>
     *
     * <p><b>B-6</b>：{@link CleanStateRegistry#resetAll()} 统一复位所有经 SPI 注册的用例级状态
     * （如 API 共享态 {@code api.baseStep}），框架钩子一处收口，业务零维护；该调用对未注册实现为空操作，幂等、无副作用。</p>
     *
     * <p>抽为包可见静态方法以便单测直接校验复位行为（Cucumber 的 {@code io.cucumber.java.Scenario}
     * 是 final 类，无法以桩对象驱动 {@code @Before}/{@code @After}）。</p>
     */
    static void clearScenarioScopedState() {
        SoftAssertions.clearForCurrentThread();
        LanguageState.reset();
        CleanStateRegistry.resetAll();
    }
}
