package com.hsbc.cmb.hk.dbb.automation.tests.ssoplan;

import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.DriverRaceErrors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link DriverRaceGate} 契约守卫（§6.3 fail-closed 逻辑，本地可跑、不依赖 SIT）。
 *
 * <p>锁定：
 * <ol>
 *   <li>count == 0 → {@link #assertZeroRaces()} 不抛；</li>
 *   <li>count &gt; 0 → 抛 {@link AssertionError} 且消息含 §6.3 标记 + 对象 GUID + 竞态类别（诊断可追溯）；</li>
 *   <li>{@link #isEnabled()} 默认 false，{@code -Dsso.race.gate.enabled=true} 时 true。</li>
 * </ol>
 * 计数器经 {@link DriverRaceErrors.Monitor#reset()} 隔离，避免跨测试污染。
 */
public class DriverRaceGateTest {

    @Before
    public void reset() {
        DriverRaceErrors.Monitor.reset();
    }

    @After
    public void resetAfter() {
        DriverRaceErrors.Monitor.reset();
    }

    @Test
    // @DisplayName: "零竞态：assertZeroRaces 不抛"
    public void zeroRaces_passes() {
        DriverRaceErrors.Monitor.reset();
        DriverRaceGate.assertZeroRaces(); // 不抛
    }

    @Test
    // @DisplayName: "非零竞态：抛 AssertionError 且含诊断（§6.3 / GUID / 类别）"
    public void nonZeroRaces_throwsWithDiagnostics() {
        DriverRaceErrors.Monitor.record(DriverRaceErrors.Kind.OBJECT_LIFECYCLE_RACE,
                "Object doesn't exist: response@1256921968353cbde3445ff7a129b1ce", "main");
        AssertionError ex = assertThrows(AssertionError.class, DriverRaceGate::assertZeroRaces);
        assertTrue(ex.getMessage().contains("§6.3"));
        assertTrue(ex.getMessage().contains("response@1256921968353cbde3445ff7a129b1ce"));
        assertTrue(ex.getMessage().contains("OBJECT_LIFECYCLE_RACE"));
    }

    @Test
    // @DisplayName: "isEnabled 默认 false"
    public void isEnabled_defaultFalse() {
        System.setProperty(DriverRaceGate.GATE_ENABLED_KEY, "");
        assertFalse(DriverRaceGate.isEnabled());
    }

    @Test
    // @DisplayName: "isEnabled 设 true 时生效"
    public void isEnabled_trueWhenSet() {
        System.setProperty(DriverRaceGate.GATE_ENABLED_KEY, "true");
        assertTrue(DriverRaceGate.isEnabled());
        System.setProperty(DriverRaceGate.GATE_ENABLED_KEY, "");
    }
}
