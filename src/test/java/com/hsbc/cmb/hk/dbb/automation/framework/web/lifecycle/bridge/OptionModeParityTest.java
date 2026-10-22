package com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.bridge;

import com.hsbc.cmb.hk.dbb.automation.framework.core.context.TestContextHolder;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.PlaywrightManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.context.CustomOptionsManager;
import com.hsbc.cmb.hk.dbb.automation.framework.web.lifecycle.scenario.ScenarioLifecycle;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertNull;

/**
 * 偏差 B-2 表征测试（浏览器选项治理专项，doc18 §4.2）：固化自定义选项在 scenario 级清理下的<b>当前</b>契约，不改行为。
 *
 * <p>2026-09-30 收口：框架已统一为每 case 重建 Context（scenario-scoped），不再有 feature 模式
 * "跨 scenario 保留登录态"的单独分支；故本测试仅覆盖 scenario 模式——
 * {@code ScenarioLifecycle.cleanupForScenario()} 应清除全部自定义配置（含 storageState / locale / flag），
 * 作为回归基线防止后续误改破坏该契约。</p>
 */
public class OptionModeParityTest {

    private static final String STORAGE_STATE_JSON =
            "{\"cookies\":[{\"name\":\"sid\",\"value\":\"abc\"}],\"origins\":[]}";

    private final CustomOptionsManager customOptions = CustomOptionsManager.getInstance();

    @After
    public void tearDown() {
        TestContextHolder.get().remove(PlaywrightManager.CONTEXT_KEY);
        TestContextHolder.get().remove(PlaywrightManager.PAGE_KEY);
        customOptions.removeAllThreadLocals();
    }

    @Test
    public void scenarioMode_clearsAllCustomOptions_includingStorageState() {
        customOptions.setStorageState(STORAGE_STATE_JSON);
        customOptions.setLocale("en-US");

        ScenarioLifecycle.cleanupForScenario();

        assertNull("Scenario 模式不应保留 storageState", customOptions.getStorageState());
        assertNull("Scenario 模式应清除 locale", customOptions.getLocale());
        // cleanupForScenario → removeAllThreadLocals 清除 flag；消费者 customFlag!=null&&customFlag 安全处理 null
        assertNull("Scenario 模式 flag 应被清除（返回 null）", customOptions.isCustomContextOptionsFlag());
    }
}
