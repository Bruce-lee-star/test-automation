package com.hsbc.cmb.hk.dbb.automation.tests.glue;

import com.hsbc.cmb.hk.dbb.automation.tests.ssoplan.DriverRaceGate;

import io.cucumber.java.After;
import io.cucumber.java.Before;

/**
 * §6.3 CI 通道压测门禁接线（opt-in）：启用 {@code -Dsso.race.gate.enabled=true} 后，
 * 每 scenario 开始重置协议竞态计数、结束断言 == 0（fail-closed）。
 *
 * <p>默认关闭，普通运行零回归。挂于共享 glue 包，对所有 runner（SIT / 并发登录 / 并行登录）生效；
 * 仅当 CI 真浏览器压测显式开启开关时激活，作为「运行期协议异常计数 == 0」的硬门禁。</p>
 *
 * <p>顺序：{@code @Before(order=-1000)} 抢在业务钩子前重置计数；{@code @After(order=1000)} 在场景收尾后断言，
 * 确保捕获本 scenario 内 {@code PageNavigation} 触发的全部驱动竞态。</p>
 */
public class DriverRaceGateGlue {

    @Before(order = -1000)
    public void resetRaceCounter() {
        DriverRaceGate.beforeScenario();
    }

    @After(order = 1000)
    public void assertNoRaces() {
        DriverRaceGate.afterScenario();
    }
}
