package com.hsbc.cmb.hk.dbb.automation.framework.common.config;

import org.junit.After;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * P2-1「生效值单一视图」门禁：
 * <ul>
 *   <li>视图必须覆盖<b>全部</b>登记键，且每键只有一个生效值 + 一个来源层（不存在"同一键多个值"）；</li>
 *   <li>系统属性覆盖必须<b>可见且带来源标注</b>（这是"同键三层不同值可被观察"的最小证据）；</li>
 *   <li>不得存在<b>环境变量名冲突</b>（两个不同键归一后同名 ⇒ 静默共享同一个值）。</li>
 * </ul>
 */
public class ConfigEffectiveViewTest {

    private static final String PROBE_KEY = ConfigKeys.ASYNC_CORE_THREADS.key();

    @After
    public void tearDown() {
        System.clearProperty(PROBE_KEY);
    }

    @Test
    public void snapshotCoversEveryRegisteredKeyExactlyOnce() {
        Map<String, ConfigEffectiveView.Entry> view = ConfigEffectiveView.snapshot();

        assertTrue("注册表条目数异常偏少（实际 " + ConfigKeys.values().length + "），疑似采集失效", ConfigKeys.values().length > 100);
        assertEquals("视图必须与注册表一一对应（键数相等）", ConfigKeys.values().length, view.size());
        for (ConfigKeys key : ConfigKeys.values()) {
            ConfigEffectiveView.Entry entry = view.get(key.key());
            assertNotNull("视图缺少登记键：" + key.key(), entry);
            assertNotNull("每个键必须标注来源层：" + key.key(), entry.source());
            assertNotNull("生效值不得为 null（未配置应回落注册表默认值）：" + key.key(), entry.value());
        }
    }

    @Test
    public void systemPropertyOverrideIsVisibleWithProvenance() {
        ConfigEffectiveView.Entry before = ConfigEffectiveView.entry(ConfigKeys.ASYNC_CORE_THREADS);
        assertEquals("前置：探测键未被系统属性覆盖时应等于注册表默认值", ConfigKeys.ASYNC_CORE_THREADS.defaultValue(), before.value());

        System.setProperty(PROBE_KEY, "7");

        ConfigEffectiveView.Entry after = ConfigEffectiveView.entry(ConfigKeys.ASYNC_CORE_THREADS);
        assertEquals("P2-1：键被系统属性覆盖时，来源层必须显式可查（否则\"同键三层不同值\"无从观察）", ConfigEffectiveView.Source.SYSTEM_PROPERTY, after.source());
        assertEquals("生效值应为系统属性值", "7", after.value());
    }

    @Test
    public void registryDefaultIsUsedWhenNoOverridePresent() {
        ConfigEffectiveView.Entry entry = ConfigEffectiveView.entry(ConfigKeys.ASYNC_MAX_PENDING_TIMEOUTS);
        //  系统属性 / 环境变量都未设置时（若 CI 设了则来源会变，故按来源断言而非硬编码值）
        if (entry.source() == ConfigEffectiveView.Source.REGISTRY_DEFAULT) {
            assertEquals("未覆盖时必须回落注册表默认值（SSoT）", ConfigKeys.ASYNC_MAX_PENDING_TIMEOUTS.defaultValue(), entry.value());
        }
    }

    @Test
    public void noEnvNameCollisionsAmongRegisteredKeys() {
        assertTrue("存在环境变量名冲突：两个不同登记键归一后同名 ⇒ 会静默共享同一个 env 值：\n"
                        + String.join("\n", ConfigEffectiveView.envNameCollisions()), ConfigEffectiveView.envNameCollisions().isEmpty());
    }

    @Test
    public void strictModeKeyIsPrefixedAndLegacyAliasIsRegistered() {
        assertTrue("P2-1：框架自有开关必须统一 framework. 前缀，实际："
                        + ConfigKeys.SECURITY_SECRET_STRICT.key(), ConfigKeys.SECURITY_SECRET_STRICT.key().startsWith(FrameworkFlags.PREFIX));
        assertFalse("旧键本就是无前缀形态（作为兼容别名保留）", ConfigKeys.LEGACY_SECURITY_SECRET_STRICT.key().startsWith(FrameworkFlags.PREFIX));
        assertNotEquals("新旧键必须映射到不同环境变量名，否则兼容解析会互相覆盖", FrameworkFlags.toEnvKey(ConfigKeys.SECURITY_SECRET_STRICT.key()), FrameworkFlags.toEnvKey(ConfigKeys.LEGACY_SECURITY_SECRET_STRICT.key()));
        //  ASYNC_* 亦已登记（此前只在 AsyncPool 内以字面量存在）
        assertNotNull("P2-1：ASYNC_* 必须登记进注册表", ConfigKeys.valueOf("ASYNC_CORE_THREADS"));
    }
}
