package com.hsbc.cmb.hk.dbb.automation.tests.ssoplan;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigSource;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.DriverRaceErrors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * §6.3 验证门禁（CI 通道压测 fail-closed）：断言运行期协议竞态计数 == 0。
 *
 * <p>唯一计数来源 = 框架 {@link DriverRaceErrors.Monitor#raceCount()}（由 {@code PageNavigation} 唯一消费点累计，
 * 见设计文档 §6.3 落地状态）。非零即抛 {@link AssertionError}（含诊断：kind / 线程 / 对象 GUID / 文案），
 * 构建失败（fail-closed），推动「跨线程通道对象所有权违规」治理收敛到 0。</p>
 *
 * <p><b>默认关闭</b>（{@code sso.race.gate.enabled=false}）：本地 SIT / 普通回归不受扰；CI 通道压测设
 * {@code -Dsso.race.gate.enabled=true} 经 {@link DriverRaceGateGlue} 在每 scenario 末断言。协议异常零容忍，
 * 残余仅作 SUSPECT 兜底、绝不破坏 SSO 缓存。</p>
 *
 * @apiNote 仅在 CI 真浏览器通道压测启用；本地缺 SIT 不可跑，但 {@link DriverRaceGateTest} 已断言其 fail-closed 逻辑。
 */
public final class DriverRaceGate {

    private static final Logger LOG = LoggerFactory.getLogger(DriverRaceGate.class);

    /** 门禁开关（CI torture 设 {@code -Dsso.race.gate.enabled=true}）。 */
    public static final String GATE_ENABLED_KEY = "sso.race.gate.enabled";

    private DriverRaceGate() {
    }

    /** 门禁是否启用。 */
    public static boolean isEnabled() {
        return "true".equalsIgnoreCase(ConfigSource.resolve(GATE_ENABLED_KEY, "false").trim());
    }

    /** 场景开始：隔离计数（仅启用时）。 */
    public static void beforeScenario() {
        if (isEnabled()) {
            DriverRaceErrors.Monitor.reset();
        }
    }

    /** 场景结束：fail-closed 断言（仅启用时）。 */
    public static void afterScenario() {
        if (isEnabled()) {
            assertZeroRaces();
        }
    }

    /** fail-closed：运行期协议竞态计数 &gt; 0 抛 {@link AssertionError}（含诊断）；count == 0 时 no-op。 */
    public static void assertZeroRaces() {
        long count = DriverRaceErrors.Monitor.raceCount();
        if (count == 0L) {
            return;
        }
        DriverRaceErrors.Record last = DriverRaceErrors.Monitor.last();
        String diag = last == null ? "(no diagnostic recorded)" : String.format(
                "kind=%s, thread=%s, objectGuid=%s, message=%s",
                last.kind, last.threadName, last.objectGuid, last.message);
        String msg = String.format(
                "SSO driver-race gate VIOLATED (design §6.3): %d protocol race(s) detected during run. "
                        + "Zero tolerance for cross-thread channel-object ownership violations. Last: %s",
                count, diag);
        LOG.error(msg);
        throw new AssertionError(msg);
    }
}
